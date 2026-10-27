package com.lanhang.miuix.campusnet

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lanhang.miuix.session.Session
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 校园网账号的本地存储。
 *
 * **密码不落明文**：用 Android Keystore 里的 AES-GCM 密钥加密，SharedPreferences 里只放
 * `base64(iv):base64(ciphertext)`。密钥由系统托管、`setUserAuthenticationRequired(false)`
 * （不要求指纹，否则每次认证都要解锁太烦），卸载 App 时随 Keystore 一起销毁。
 *
 * 对比：官方蓝航是把统一身份认证密码**明文**写进 `SPUtils.put("pwd", ...)`
 * （`LoginActivity:395`，为 `onReceivedHttpAuthRequest` 的 Basic Auth 兜底），这条路不能照搬。
 *
 * 解密失败（例如系统换了锁屏导致密钥失效）一律返回 null 并清掉密文，让用户重输，
 * 不要把异常抛到界面层。
 */
object CampusNetStore {

    private const val FILE = "lanhang_campus_net"
    private const val KEY_ALIAS = "lanhang.campus.net"
    private const val KEY_STORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** 读不到 `ac_id` 时的默认值（多数深澜部署是 1） */
    private const val DEFAULT_AC_ID = "1"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 账号默认填统一身份认证的学号/工号；用户可以改（例如教职工用工号） */
    var username: String
        get() = sp.getString("username", "").orEmpty().ifBlank { Session.username }
        set(value) = sp.edit().putString("username", value.trim()).apply()

    /** 认证点 id；首次认证时从门户探测，探测不到就用这个 */
    var acId: String
        get() = sp.getString("ac_id", "").orEmpty().ifBlank { DEFAULT_AC_ID }
        set(value) = sp.edit().putString("ac_id", value.trim()).apply()

    /**
     * 上次认证成功时命中的身份域（如 `@stu.nchu.edu.cn`）。
     *
     * 深澜走 LDAP，账号是「学号 + 身份域」；域不对就是「账号不存在」。
     * 记住它，下次认证**先试这个域**，命中就一步到位；
     * 没命中再按门户下拉的顺序逐个试（见 [SrunApi.detectDomains]）。
     */
    var domain: String
        get() = sp.getString("domain", "").orEmpty()
        set(value) = sp.edit().putString("domain", value.trim()).apply()

    /**
     * 认证时按「上次的域优先，其余按门户顺序」排列候选域。
     *
     * @param detected 门户下拉里读到的域（读不到时传空列表）
     */
    fun domains(detected: List<String>): List<String> {
        val remembered = domain
        val result = LinkedHashSet<String>()
        if (remembered.isNotBlank()) result += remembered
        result += detected
        return result.toList()
    }

    /**
     * 用户是否勾了「记住密码」。
     *
     * **必须与「有没有密码」分开存**（2026-10-26 用户反馈「勾了退出再进就自动关掉」）：
     * 勾选是用户的**意愿**，密码能不能落盘是结果。旧实现拿 `sp.contains("pwd")` 反推勾选状态，
     * 而密码只在认证成功后才写，认证一失败（网络不通、密码错）就变成没勾过、密码也消失。
     *
     * 默认值取 `sp.contains("pwd")`：老版本只存了密码没存这个标记，已经存过密码的视为勾选。
     */
    var remember: Boolean
        get() = sp.getBoolean("remember", sp.contains("pwd"))
        set(value) = sp.edit().putBoolean("remember", value).apply()

    /** 加密保存密码；空串等于清除。返回是否成功（Keystore 异常时 false） */
    fun savePassword(password: String): Boolean {
        if (password.isEmpty()) {
            clearPassword()
            return true
        }
        val ok = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            sp.edit().putString("pwd", value).commit()
        }.getOrDefault(false)
        if (!ok) clearPassword()
        return ok
    }

    /** 取回明文密码；没有密码 / 解密失败返回 null */
    fun password(): String? {
        val raw = sp.getString("pwd", null) ?: return null
        val parts = raw.split(':')
        if (parts.size != 2) {
            clearPassword()
            return null
        }
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull() ?: run {
            // 密钥失效（恢复出厂/换锁屏/系统升级）后密文再也解不开，清掉免得一直失败
            clearPassword()
            null
        }
    }

    fun clearPassword() {
        sp.edit().remove("pwd").apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
