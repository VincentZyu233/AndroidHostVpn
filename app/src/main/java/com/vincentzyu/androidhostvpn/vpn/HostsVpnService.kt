package com.vincentzyu.androidhostvpn.vpn

import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.vincentzyu.androidhostvpn.hosts.HostsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS-only 隧道服务：
 *
 * 系统把所有 DNS 查询发到隧道内部地址 [TUN_DNS]:53（由 addRoute 只路由这一个地址，
 * 其余流量不受影响）。本服务从 tun0 读到 DNS 查询：
 *   - 命中 hosts → 直接伪造应答写回 tun0
 *   - 未命中     → 用 protect 后的 socket 转发到真实上游 DNS，收到后写回 tun0
 */
class HostsVpnService : AbstractHostsVpnService() {

    companion object {
        const val TAG = "HostsVpn"
        const val ACTION_START = "com.vincentzyu.androidhostvpn.START"
        const val ACTION_STOP = "com.vincentzyu.androidhostvpn.STOP"
        const val ACTION_RELOAD = "com.vincentzyu.androidhostvpn.RELOAD"

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readJob: Job? = null

    private val upstreamSockets = ConcurrentHashMap<String, DatagramSocket>()

    private lateinit var prefs: SharedPreferences
    private var hostsMap: Map<String, InetAddress> = emptyMap()
    private var upstreamDns: String = "223.5.5.5"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reload()
                return START_STICKY
            }
            else -> start()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("hosts", MODE_PRIVATE)
        reload()
    }

    private fun reload() {
        val hostsText = prefs.getString("hosts_text", "") ?: ""
        upstreamDns = prefs.getString("upstream_dns", "223.5.5.5") ?: "223.5.5.5"
        hostsMap = try {
            HostsParser.parse(hostsText)
        } catch (_: Exception) {
            emptyMap()
        }
        Log.i(TAG, "reload: ${hostsMap.size} hosts, upstream=$upstreamDns, dns=$TUN_DNS")
    }

    private fun start() {
        if (vpnInterface != null) return
        reload()
        val ok = establishTunnel()
        if (!ok) {
            Log.e(TAG, "establish tunnel failed")
            stopSelf()
            return
        }
        isRunning = true

        val fd = vpnInterface ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)

        readJob = scope.launch {
            val buffer = ByteArray(65535)
            while (isActive) {
                val length = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (length <= 0) continue
                val packet = buffer.copyOf(length)
                try {
                    handlePacket(packet, output)
                } catch (e: Exception) {
                    Log.e(TAG, "handle failed", e)
                }
            }
        }
    }

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        if (packet.isEmpty()) return
        val version = (packet[0].toInt() shr 4) and 0xF
        when (version) {
            4 -> handleIpv4(packet, output)
            6 -> handleIpv6(packet, output)
        }
    }

    private fun handleIpv4(packet: ByteArray, output: FileOutputStream) {
        if (packet.size < 20) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val protocol = packet[9].toInt() and 0xFF
        val src = packet.copyOfRange(12, 16)
        val dst = packet.copyOfRange(16, 20)
        when (protocol) {
            17 -> handleUdp(packet, ihl, src, dst, false, output)
        }
    }

    private fun handleIpv6(packet: ByteArray, output: FileOutputStream) {
        if (packet.size < 40) return
        val nextHeader = packet[6].toInt() and 0xFF
        val src = packet.copyOfRange(8, 24)
        val dst = packet.copyOfRange(24, 40)
        when (nextHeader) {
            17 -> handleUdp(packet, 40, src, dst, true, output)
        }
    }

    private fun handleUdp(
        packet: ByteArray,
        udpOffset: Int,
        src: ByteArray,
        dst: ByteArray,
        isV6: Boolean,
        output: FileOutputStream,
    ) {
        if (packet.size < udpOffset + 8) return
        val srcPort = u16(packet, udpOffset)
        val dstPort = u16(packet, udpOffset + 2)
        if (dstPort != 53) return

        val dnsOffset = udpOffset + 8
        if (packet.size <= dnsOffset) return
        val dnsQuery = packet.copyOfRange(dnsOffset, packet.size)
        val name = DnsPacket.parseQueryName(dnsQuery)

        if (name != null) {
            val answer = hostsMap[name]
            if (answer != null) {
                val resp = DnsPacket.buildResponse(dnsQuery, answer)
                if (resp != null) {
                    Log.i(TAG, "hit hosts: $name -> ${answer.hostAddress}")
                    val out = buildUdpPacket(src, dst, srcPort, dstPort, isV6, resp)
                    writePacket(out, output)
                    return
                }
            }
        }

        // 未命中：转发到真实 DNS
        forwardToUpstream(dnsQuery, src, srcPort, dstPort, isV6, output, name)
    }

    private fun forwardToUpstream(
        dnsQuery: ByteArray,
        clientSrc: ByteArray,
        clientPort: Int,
        dnsPort: Int,
        isV6: Boolean,
        output: FileOutputStream,
        name: String?,
    ) {
        val key = InetAddress.getByAddress(clientSrc).hostAddress + ":" + clientPort
        val socket = upstreamSockets[key] ?: run {
            val s = try {
                DatagramSocket().apply { soTimeout = 10000 }
            } catch (_: Exception) {
                null
            }
            if (s != null) {
                protectSocket(s) // 关键：转发流量不走隧道
                upstreamSockets[key] = s
            }
            s
        } ?: return

        scope.launch {
            try {
                val request = DatagramPacket(dnsQuery, dnsQuery.size, InetSocketAddress(upstreamDns, 53))
                socket.send(request)
                val buf = ByteArray(4096)
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                val respData = recv.data.copyOfRange(recv.offset, recv.offset + recv.length)
                Log.i(TAG, "forward: $name -> upstream(${respData.size}B)")
                val out = buildUdpPacket(clientSrc, dnsIpBytes(isV6), clientPort, dnsPort, isV6, respData)
                writePacket(out, output)
            } catch (_: Exception) {
                // 超时/错误丢弃
            }
        }
    }

    /** 构造发回给客户端的 UDP over IP 包（源=tunnel dns，目的=客户端） */
    private fun buildUdpPacket(
        clientIp: ByteArray,
        dnsIp: ByteArray,
        clientPort: Int,
        dnsPort: Int,
        isV6: Boolean,
        dnsPayload: ByteArray,
    ): ByteArray {
        val ipHeaderSize = if (isV6) 40 else 20
        val udpLen = 8 + dnsPayload.size
        val totalLen = ipHeaderSize + udpLen
        val out = ByteArray(totalLen)

        if (isV6) {
            out[0] = 0x60
            out[6] = 17 // UDP
            out[7] = 64
            out[4] = ((udpLen shr 8) and 0xFF).toByte()
            out[5] = (udpLen and 0xFF).toByte()
            System.arraycopy(dnsIp, 0, out, 8, 16)   // 源 = DNS 服务器（隧道地址）
            System.arraycopy(clientIp, 0, out, 24, 16) // 目的 = 客户端
        } else {
            out[0] = 0x45
            out[8] = 64 // TTL
            out[9] = 17 // UDP
            out[2] = ((totalLen shr 8) and 0xFF).toByte()
            out[3] = (totalLen and 0xFF).toByte()
            System.arraycopy(dnsIp, 0, out, 12, 4)
            System.arraycopy(clientIp, 0, out, 16, 4)
        }

        val t = ipHeaderSize
        val us = dnsPort
        val ud = clientPort
        out[t] = ((us shr 8) and 0xFF).toByte()
        out[t + 1] = (us and 0xFF).toByte()
        out[t + 2] = ((ud shr 8) and 0xFF).toByte()
        out[t + 3] = (ud and 0xFF).toByte()
        out[t + 4] = ((udpLen shr 8) and 0xFF).toByte()
        out[t + 5] = (udpLen and 0xFF).toByte()
        System.arraycopy(dnsPayload, 0, out, t + 8, dnsPayload.size)

        if (!isV6) {
            val checksum = ipv4Checksum(out, 0, ipHeaderSize)
            out[10] = ((checksum shr 8) and 0xFF).toByte()
            out[11] = (checksum and 0xFF).toByte()
        }
        return out
    }

    private fun dnsIpBytes(isV6: Boolean): ByteArray {
        val addr = InetAddress.getByName(TUN_DNS)
        return addr.address
    }

    private fun u16(data: ByteArray, off: Int): Int {
        return ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
    }

    private fun ipv4Checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun writePacket(data: ByteArray, output: FileOutputStream) {
        if (data.isEmpty()) return
        try {
            output.write(data)
        } catch (e: Exception) {
            Log.e(TAG, "write failed", e)
        }
    }

    override fun onDestroy() {
        isRunning = false
        readJob?.cancel()
        scope.cancel()
        for (s in upstreamSockets.values) {
            try { s.close() } catch (_: Exception) {}
        }
        upstreamSockets.clear()
        closeTunnel()
        super.onDestroy()
    }
}
