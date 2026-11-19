package com.vincentzyu.androidhostvpn.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * hosts 重定向的 VpnService 基类：建立隧道、读写 IP 包。
 *
 * 具体的 DNS 拦截逻辑在 [HostsVpnService] 中实现。
 */
abstract class AbstractHostsVpnService : VpnService() {

    protected var vpnInterface: ParcelFileDescriptor? = null

    protected fun establishTunnel(dnsServers: List<String>): Boolean {
        val builder = Builder()
            .setSession("AndroidHostVpn")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)

        for (dns in dnsServers) {
            try {
                builder.addDnsServer(dns)
            } catch (_: Exception) {
                // 忽略非法 DNS 地址
            }
        }

        vpnInterface?.close()
        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
        return vpnInterface != null
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
