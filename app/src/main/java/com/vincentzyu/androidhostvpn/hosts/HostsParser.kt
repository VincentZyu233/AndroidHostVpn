package com.vincentzyu.androidhostvpn.hosts

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * 解析 /etc/hosts 风格的文本。
 *
 * 格式：每行 `ip domain`，`#` 开头为注释，忽略空行。
 * 支持一个 IP 映射多个域名，也支持 IPv4 / IPv6。
 */
object HostsParser {

    /** @return domain(lowercase) -> InetAddress */
    fun parse(text: String): Map<String, InetAddress> {
        val result = LinkedHashMap<String, InetAddress>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // 去掉行内注释（# 后若前面有空格则视为注释）
            val cleaned = line.substringBefore(" #").substringBefore("\t#").trim()
            if (cleaned.isEmpty()) continue

            val parts = cleaned.split(Regex("\\s+"))
            if (parts.size < 2) continue

            val ipText = parts[0]
            val addr = try {
                InetAddress.getByName(ipText)
            } catch (e: Exception) {
                continue
            }
            // 只处理 IPv4 / IPv6 单播地址
            if (addr !is Inet4Address && addr !is Inet6Address) continue

            for (i in 1 until parts.size) {
                val domain = parts[i].trim().lowercase()
                if (domain.isEmpty()) continue
                result[domain] = addr
            }
        }
        return result
    }
}
