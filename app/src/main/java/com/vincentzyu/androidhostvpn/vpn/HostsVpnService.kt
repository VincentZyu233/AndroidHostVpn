package com.vincentzyu.androidhostvpn.vpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.util.Log
import com.vincentzyu.androidhostvpn.hosts.HostsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * 拦截 DNS 查询，匹配 hosts 则伪造应答，否则转发到真实 DNS。
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
    private var writeJob: Job? = null

    // 每个客户端（源 IP:端口）对应一个真实 DNS 的 socket
    private val upstreamSockets = ConcurrentHashMap<String, DatagramSocket>()

    private lateinit var prefs: SharedPreferences
    private var hostsMap: Map<String, InetAddress> = emptyMap()
    private var upstreamDns: String = "8.8.8.8"

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
        upstreamDns = prefs.getString("upstream_dns", "8.8.8.8") ?: "8.8.8.8"
        hostsMap = try {
            HostsParser.parse(hostsText)
        } catch (e: Exception) {
            emptyMap()
        }
        Log.i(TAG, "reload: ${hostsMap.size} hosts, upstream=$upstreamDns")
    }

    private fun start() {
        if (vpnInterface != null) return
        reload()
        val ok = establishTunnel(listOf(upstreamDns))
        if (!ok) {
            Log.e(TAG, "establish tunnel failed")
            stopSelf()
            return
        }
        isRunning = true

        val fd = vpnInterface ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)

        // 读线程：从隧道读入 IP 包
        readJob = scope.launch {
            val buffer = ByteArray(32767)
            while (isActive) {
                val length = try {
                    input.read(buffer)
                } catch (e: Exception) {
                    break
                }
                if (length <= 0) continue
                val packet = buffer.copyOf(length)
                handlePacket(packet, output)
            }
        }
    }

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        // 解析 IPv4 / IPv6 头，找到传输层协议与载荷
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
        val src = byteArrayOf(packet[12], packet[13], packet[14], packet[15])
        val dst = byteArrayOf(packet[16], packet[17], packet[18], packet[19])
        val transportOffset = ihl
        when (protocol) {
            17 -> handleUdp(packet, transportOffset, src, dst, false, output)
            6 -> handleTcp(packet, transportOffset, src, dst, false, output)
        }
    }

    private fun handleIpv6(packet: ByteArray, output: FileOutputStream) {
        if (packet.size < 40) return
        val nextHeader = packet[6].toInt() and 0xFF
        val src = packet.copyOfRange(8, 24)
        val dst = packet.copyOfRange(24, 40)
        // 只处理没有扩展头的简单情况（next header 直接是 UDP/TCP）
        when (nextHeader) {
            17 -> handleUdp(packet, 40, src, dst, true, output)
            6 -> handleTcp(packet, 40, src, dst, true, output)
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
        val srcPort = ((packet[udpOffset].toInt() and 0xFF) shl 8) or (packet[udpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val dnsOffset = udpOffset + 8
        val dnsQuery = packet.copyOfRange(dnsOffset, packet.size)
        val name = DnsPacket.parseQueryName(dnsQuery)

        if (name == null) {
            forwardToUpstream(packet, output, src, srcPort, dst, dstPort, isV6)
            return
        }

        // 匹配 hosts
        val answer = hostsMap[name]
        if (answer != null) {
            val resp = DnsPacket.buildResponse(dnsQuery, answer)
            if (resp != null) {
                val out = buildUdpPacket(packet, udpOffset, src, dst, srcPort, dstPort, isV6, resp)
                writePacket(out, output)
                return
            }
        }

        forwardToUpstream(packet, output, src, srcPort, dst, dstPort, isV6)
    }

    private fun handleTcp(
        packet: ByteArray,
        tcpOffset: Int,
        src: ByteArray,
        dst: ByteArray,
        isV6: Boolean,
        output: FileOutputStream,
    ) {
        if (packet.size < tcpOffset + 20) return
        val dataOffset = ((packet[tcpOffset + 12].toInt() shr 4) and 0x0F) * 4
        val srcPort = ((packet[tcpOffset].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[tcpOffset + 2].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val flags = ((packet[tcpOffset + 12].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 13].toInt() and 0xFF)
        val syn = flags and 0x02 != 0
        if (syn) {
            // TCP DNS 暂不处理 SYN 握手，直接丢弃（大多数客户端回退到 UDP）
            return
        }
        val dnsOffset = tcpOffset + dataOffset
        if (packet.size <= dnsOffset + 2) return
        // TCP DNS 前面有 2 字节长度前缀
        val dnsQuery = packet.copyOfRange(dnsOffset + 2, packet.size)
        val name = DnsPacket.parseQueryName(dnsQuery)

        if (name != null) {
            val answer = hostsMap[name]
            if (answer != null) {
                val resp = DnsPacket.buildResponse(dnsQuery, answer)
                if (resp != null) {
                    val dnsWithLen = ByteBuffer.allocate(2 + resp.size).order(ByteOrder.BIG_ENDIAN)
                        .putShort(resp.size.toShort()).put(resp).array()
                    val out = buildTcpPacket(packet, tcpOffset, src, dst, srcPort, dstPort, isV6, dnsWithLen)
                    writePacket(out, output)
                    return
                }
            }
        }
        // TCP DNS 转发未实现，直接丢弃（客户端会回退 UDP）
    }

    private fun forwardToUpstream(
        packet: ByteArray,
        output: FileOutputStream,
        src: ByteArray,
        srcPort: Int,
        dst: ByteArray,
        dstPort: Int,
        isV6: Boolean,
    ) {
        // 提取原始 DNS 载荷，交给真实 DNS，收到应答再封装回 IP 包
        val udpOffset = if (isV6) 40 else ((packet[0].toInt() and 0x0F) * 4)
        val dnsOffset = udpOffset + 8
        val dnsQuery = packet.copyOfRange(dnsOffset, packet.size)
        if (dnsQuery.isEmpty()) return

        val key = InetAddress.getByAddress(src).hostAddress + ":" + srcPort
        val socket = upstreamSockets.getOrPut(key) {
            try {
                DatagramSocket().apply { soTimeout = 15000 }
            } catch (e: Exception) {
                null
            }
        } ?: return

        scope.launch {
            try {
                val request = DatagramPacket(dnsQuery, dnsQuery.size, InetSocketAddress(upstreamDns, 53))
                socket.send(request)
                val buf = ByteArray(4096)
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                val respData = recv.data.copyOfRange(recv.offset, recv.offset + recv.length)
                val out = buildUdpPacket(packet, udpOffset, src, dst, srcPort, dstPort, isV6, respData)
                writePacket(out, output)
            } catch (e: Exception) {
                // 超时或错误，丢弃
            }
        }
    }

    private fun buildUdpPacket(
        original: ByteArray,
        udpOffset: Int,
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        isV6: Boolean,
        dnsPayload: ByteArray,
    ): ByteArray {
        val is6 = isV6
        val ipHeaderSize = if (is6) 40 else ((original[0].toInt() and 0x0F) * 4)
        val udpLen = 8 + dnsPayload.size
        val totalLen = ipHeaderSize + udpLen

        val out = ByteArray(totalLen)

        if (is6) {
            out[0] = 0x60.toByte() // version=6
            out[6] = 17 // next header = UDP
            out[7] = 64 // hop limit
            System.arraycopy(dst, 0, out, 8, 16)  // 源地址=原目的地址
            System.arraycopy(src, 0, out, 24, 16) // 目的地址=原源地址
            // payload length
            out[4] = ((udpLen shr 8) and 0xFF).toByte()
            out[5] = (udpLen and 0xFF).toByte()
        } else {
            out[0] = 0x45 // version=4, ihl=5
            out[8] = 64 // TTL
            out[9] = 17 // protocol = UDP
            // total length
            out[2] = ((totalLen shr 8) and 0xFF).toByte()
            out[3] = (totalLen and 0xFF).toByte()
            System.arraycopy(dst, 0, out, 12, 4) // 源地址
            System.arraycopy(src, 0, out, 16, 4) // 目的地址
            // 校验和交给系统（置 0）
        }

        val u = udpOffset
        val t = ipHeaderSize
        // UDP 头：源端口=原目的端口，目的端口=原源端口
        out[t] = ((dstPort shr 8) and 0xFF).toByte()
        out[t + 1] = (dstPort and 0xFF).toByte()
        out[t + 2] = ((srcPort shr 8) and 0xFF).toByte()
        out[t + 3] = (srcPort and 0xFF).toByte()
        // UDP 长度
        out[t + 4] = ((udpLen shr 8) and 0xFF).toByte()
        out[t + 5] = (udpLen and 0xFF).toByte()
        // UDP 校验和置 0（多数系统对 0 校验和容忍，IPv6 下需计算）
        out[t + 6] = 0
        out[t + 7] = 0

        System.arraycopy(dnsPayload, 0, out, t + 8, dnsPayload.size)

        // IPv4 头部校验和
        if (!is6) {
            val checksum = ipv4Checksum(out, 0, ipHeaderSize)
            out[10] = ((checksum shr 8) and 0xFF).toByte()
            out[11] = (checksum and 0xFF).toByte()
        }

        return out
    }

    private fun buildTcpPacket(
        original: ByteArray,
        tcpOffset: Int,
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        isV6: Boolean,
        dnsPayload: ByteArray,
    ): ByteArray {
        // TCP DNS 应答构造较复杂，这里简化：不返回有效包（客户端回退 UDP）
        return ByteArray(0)
    }

    private fun ipv4Checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
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
        writeJob?.cancel()
        scope.cancel()
        for (s in upstreamSockets.values) {
            try { s.close() } catch (_: Exception) {}
        }
        upstreamSockets.clear()
        closeTunnel()
        super.onDestroy()
    }
}
