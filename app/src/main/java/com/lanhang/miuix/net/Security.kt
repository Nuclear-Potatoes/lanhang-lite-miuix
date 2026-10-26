package com.lanhang.miuix.net

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object Security {
    // 与官方 App configuration/all 下发的 password_public_key_rsa 一致
    private const val PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIKS1QzPcybjZp5Z0bVPddrFL93oFu8SQ74r9Epd0BwYNbnKqJfmBR9HLYaF2DkCNvOl23/fR9wm76yHFBso2D0o6jHQE4L5w9eL1pO+aQ1atlmn55h4KP3CkJSAZ5ZBG0OluuwQwrD+mcXlGjrR0d3TxCrBvPZ4g/A4gnL+uHLQIDAQAB"

    fun encryptPassword(plain: String): String {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        val cipher = Cipher.getInstance("RSA/None/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key as PublicKey)
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.DEFAULT)
    }
}
