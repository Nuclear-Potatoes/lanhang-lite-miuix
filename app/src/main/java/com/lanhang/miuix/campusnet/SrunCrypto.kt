package com.lanhang.miuix.campusnet

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 深澜（Srun）5.x 认证用到的加密原语。
 *
 * 全部是手写 + JDK 自带 `MessageDigest`/`Mac`，**不引第三方库**（AGENTS.md 第 5 节）：
 * 协议要用的就这四个，加起来不到 150 行，引库反而多一层不可控。
 *
 * 两个必须照抄的细节（写错一个就 100% 登录失败，而且服务端只回一句密码错误）：
 *
 * 1. **自定义 base64 字母表**（不是标准表）：
 *    `LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA`
 * 2. **XXTEA 的输入按「UTF-16 码元低字节」打包成 32 位字**，末尾追加明文长度（码元数），
 *    输出再按小端拆回字节。直接套标准 XXTEA（按 UTF-8 字节打包）服务端解不出来。
 */
object SrunCrypto {

    /** 深澜自定义字母表，长度必须 64 */
    private const val ALPHABET = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"

    /** XXTEA 的 delta；0x9E3779B9 在 Int 里是负数，加法溢出正好等价于 JS 的 `>>> 0` */
    private const val DELTA = 0x9E3779B9.toInt()

    fun md5Hex(text: String): String =
        hex(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)))

    fun sha1Hex(text: String): String =
        hex(MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)))

    /** 5.x 的 `hmd5`：HMAC-MD5(key = challenge, msg = 密码) */
    fun hmacMd5Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacMD5"))
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    /**
     * `info` 参数：`{SRBX1}` + 自定义 base64(XXTEA(json, challenge))。
     * [json] 必须是紧凑序列化、键序固定（见 [SrunApi.buildInfoJson]）。
     */
    fun srunInfo(json: String, challenge: String): String =
        "{SRBX1}" + base64(xxtea(json, challenge))

    // ==================== 内部实现 ====================

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /**
     * XXTEA 加密（**深澜变体，不是标准 XXTEA**）。
     *
     * 输出长度固定为 `v.size * 4` 字节（含末尾追加的长度字），**不做裁剪**——
     * 门户 JS 的 `l(v, false)` 就是把每个 32 位字按小端摊成 4 字节后全量拼接，
     * 服务端靠末尾的长度字自行还原明文。
     *
     * ⚠️ 踩过的坑：标准 XXTEA 的混合公式是
     * `((z>>>5 ^ y<<2) + (y>>>3 ^ z<<4)) ^ ((sum^y) + (key^z))`，
     * 而门户 JS 被混淆成了 **`A + (B ^ C) + D`** 三个加号串起来的形式，
     * 两者数学上并不等价。照标准实现能跑通本地自测，但服务端一律回 `auth_info_error`
     * （2026-10-25 校内实测），所以必须照抄下面这个形状。
     */
    private fun xxtea(data: String, key: String): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val v = wordsOf(data, includeLength = true)
        // key 不足 4 个字时补 0：JS 里 k[(i&3)^e] 越界会拿到 undefined（位运算当 0），
        // 实际 challenge 是 64 字符 hex，这里只是防御
        val k = wordsOf(key, includeLength = false).let { if (it.size < 4) it.copyOf(4) else it }
        // JS: var n = v.length - 1 —— n 是「最后一个下标」，不是元素个数
        val n = v.size - 1

        var z = v[n]
        var d = 0
        var rounds = 6 + 52 / (n + 1)
        while (rounds-- > 0) {
            d += DELTA
            val e = (d ushr 2) and 3
            for (p in 0 until n) {
                val y = v[p + 1]
                // 三步加法，顺序与 JS 一致（Int 溢出回绕正好等价 JS 的 & 0xFFFFFFFF）
                var m = (z ushr 5) xor (y shl 2)
                m += (y ushr 3) xor (z shl 4) xor (d xor y)
                m += k[(p and 3) xor e] xor z
                z = v[p] + m
                v[p] = z
            }
            val y = v[0]
            var m = (z ushr 5) xor (y shl 2)
            m += (y ushr 3) xor (z shl 4) xor (d xor y)
            m += k[(n and 3) xor e] xor z
            z = v[n] + m
            v[n] = z
        }

        val out = ByteArray(v.size * 4)
        for (i in out.indices) {
            out[i] = ((v[i shr 2] ushr ((i and 3) shl 3)) and 0xff).toByte()
        }
        return out
    }

    /**
     * 对应 JS 的 `s(str, includeLength)`：每 4 个 **UTF-16 码元**压进一个 int（小端），
     * 可选追加码元数作为长度字。
     *
     * 注意取的是完整码元（不是低字节）：JS 是 `charCodeAt(i) | charCodeAt(i+1) << 8 …`，
     * 非 ASCII 字符会占两个字节。我们的 info 是纯 ASCII，但别在这里写 `and 0xff` 留坑。
     */
    private fun wordsOf(text: String, includeLength: Boolean): IntArray {
        val length = text.length
        val words = if ((length and 3) == 0) length shr 2 else (length shr 2) + 1
        val result = IntArray(if (includeLength) words + 1 else words)
        if (includeLength) result[words] = length
        for (i in 0 until length) {
            result[i shr 2] = result[i shr 2] or (text[i].code shl ((i and 3) shl 3))
        }
        return result
    }

    /** 标准 base64 流程，但字母表换成深澜那张表 */
    private fun base64(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            val rest = bytes.size - i
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(if (rest > 1) ALPHABET[((b1 and 0x0f) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (rest > 2) ALPHABET[b2 and 0x3f] else '=')
            i += 3
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
