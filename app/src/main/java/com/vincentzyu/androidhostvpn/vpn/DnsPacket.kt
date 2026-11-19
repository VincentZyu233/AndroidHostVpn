package com.vincentzyu.androidhostvpn.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DNS 包解析与构造（只支持最常见的 A / AAAA 查询）。
 *
 * 仅解析标准 DNS 头 + question 段，用于从 VPN 隧道中截获的 IP 包里提取域名，
 * 以及在命中 hosts 时伪造一个 DNS 应答。
 */
object DnsPacket {

    private const val DNS_HEADER_SIZE = 12

    /**
     * 尝试从 UDP 载荷中解析 DNS 查询。
     *
     * @return 解析出的域名（小写），若不是合法 DNS 查询返回 null。
     */
    fun parseQueryName(packet: ByteArray): String? {
        if (packet.size < DNS_HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(packet)

        // 只处理标准查询（QR=0）
        val flags = buf.getShort(2).toInt() and 0xFFFF
        val qr = (flags shr 15) and 1
        if (qr != 0) return null

        val qdCount = buf.getShort(4).toInt() and 0xFFFF
        if (qdCount < 1) return null

        var pos = DNS_HEADER_SIZE
        val sb = StringBuilder()
        var nameLen = 0
        while (pos < packet.size) {
            val len = packet[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                // 压缩指针，暂不支持，直接失败
                return null
            }
            if (pos + len > packet.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                val c = packet[pos + i].toInt() and 0xFF
                sb.append(c.toChar())
            }
            pos += len
            nameLen++
            if (nameLen > 64) return null
        }
        if (sb.isEmpty()) return null
        return sb.toString().lowercase()
    }

    /**
     * 为某个 A / AAAA 查询伪造应答。
     *
     * @param query 原始查询包（用于回填 transaction id 与 question 段）
     * @param answer 目标地址
     * @return 构造好的 DNS 应答（可直接塞回 UDP 载荷）
     */
    fun buildResponse(query: ByteArray, answer: InetAddress): ByteArray? {
        if (query.size < DNS_HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(query)
        val id = buf.getShort(0)
        val qdCount = buf.getShort(4).toInt() and 0xFFFF

        // 取 question 段的原始字节（跳过域名+类型+类）
        var pos = DNS_HEADER_SIZE
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            if (len and 0xC0 == 0xC0) { pos += 2; break }
            pos += len
        }
        pos += 1 // 终止字节
        if (pos + 4 > query.size) return null
        val question = query.copyOfRange(DNS_HEADER_SIZE, pos + 4)

        val isIpv6 = answer is Inet6Address
        val answerType = if (isIpv6) 0x1C else 0x01 // AAAA / A

        val rdata = answer.address
        val rdataLen = rdata.size
        val answerSectionSize = 2 + 2 + 4 + 2 + rdataLen // name(2)+type(2)+class(2)+ttl(4)+rdlen(2)+rdata

        // 构造响应
        val resp = ByteBuffer.allocate(DNS_HEADER_SIZE + question.size + answerSectionSize)
            .order(ByteOrder.BIG_ENDIAN)

        resp.putShort(id)
        // flags: QR=1, opcode=0, AA=1, TC=0, RD=1, RA=1, rcode=0
        resp.putShort(0x8180.toShort())
        resp.putShort(qdCount.toShort())
        resp.putShort(1) // anCount = 1
        resp.putShort(0) // nsCount
        resp.putShort(0) // arCount
        resp.put(question)
        // answer: name 指针指向 question 域名开头（0xC00C）
        resp.putShort(0xC00C.toShort())
        resp.putShort(answerType.toShort())
        resp.putShort(1) // class IN
        resp.putInt(60)  // TTL
        resp.putShort(rdataLen.toShort())
        resp.put(rdata)

        return resp.array()
    }
}
