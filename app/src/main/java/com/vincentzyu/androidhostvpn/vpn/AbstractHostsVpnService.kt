package com.vincentzyu.androidhostvpn.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.net.DatagramSocket
import java.net.Socket

/**
 * DNS-only 隧道：只把 DNS 查询导入本服务处理，其余流量走物理网络。
 *
 * 关键点：
 * - `addDnsServer(TUN_DNS)` 把系统 DNS 指向隧道内部地址；只有发往该地址的 53 端口流量
 *   进入 tun0，其他流量因为没有匹配路由，正常走物理网卡（不会断网）。
 * - 转发真实 DNS 时用 [protect] 保护 socket，避免转发流量又被导回隧道造成递归。
 */
abstract class AbstractHostsVpnService : VpnService() {

    companion object {
        /** 隧道内部地址（同时也是 DNS 服务器地址） */
        const val TUN_ADDRESS = "10.0.0.2"
        const val TUN_PREFIX = 32
        const val TUN_DNS = TUN_ADDRESS
        const val TUN_MTU = 1500
    }

    protected var vpnInterface: ParcelFileDescriptor? = null

    protected fun establishTunnel(): Boolean {
        val builder = Builder()
            .setSession("AndroidHostVpn")
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            // 系统所有 DNS 查询发到隧道内部地址，只有这部分流量进 tun0
            .addDnsServer(TUN_DNS)
            .setBlocking(true)

        // 只路由 DNS 服务器地址，不抢默认路由，保证其余流量正常
        builder.addRoute(TUN_DNS, 32)

        vpnInterface?.close()
        vpnInterface = try {
            builder.establish()
        } catch (_: Exception) {
            null
        }
        return vpnInterface != null
    }

    /** 保护 socket，使其流量不走隧道（用于转发到真实 DNS） */
    protected fun protectSocket(socket: DatagramSocket): Boolean {
        return try {
            protect(socket)
        } catch (_: Exception) {
            false
        }
    }

    protected fun protectTcpSocket(socket: Socket): Boolean {
        return try {
            protect(socket)
        } catch (_: Exception) {
            false
        }
    }

    protected fun closeTunnel() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }
}
