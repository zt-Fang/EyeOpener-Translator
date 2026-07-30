package io.github.ztfang.eye.data.local.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 的 AES/GCM 加密工具。
 *
 * 加密流程:
 *   1. 在 Android Keystore 中生成并持久化 AES-256 密钥 (首次启动时)
 *   2. 使用 AES/GCM/NoPadding 加密明文
 *   3. 输出格式: Base64(IV + ciphertext + tag) — GCM tag 自动附在密文末尾
 *
 * 为什么用 GCM:
 *   - 提供认证加密 (AEAD),同时保证机密性和完整性
 *   - Android 官方推荐模式
 *   - 每个密文带独立 IV,相同明文每次加密结果不同
 *
 * 注意:
 *   - 密钥存在 Keystore 中,不可导出,root 也难以提取
 *   - GCM 模式下 IV 必须 12 字节 (96 bit),tag 128 bit
 *   - 加密后密文长度 = 明文长度 + 12 (IV) + 16 (tag)
 */
class CryptoManager {

    companion object {
        private const val KEY_ALIAS = "eye_opener_api_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12   // 96 bits, GCM 推荐
        private const val GCM_TAG_LENGTH = 128 // 128 bits
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        ensureKeyExists()
    }

    /**
     * 加密字符串。
     * @param plaintext 明文
     * @return Base64 编码的密文 (包含 IV + ciphertext + tag)
     */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // IV + 密文拼接后 Base64 编码,方便存到 DataStore (DataStore 只支持基础类型)
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密字符串。
     * @param ciphertext Base64 编码的密文 (IV + ciphertext + tag)
     * @return 明文;如果密文为空或无效返回空字符串
     */
    fun decrypt(ciphertext: String): String {
        if (ciphertext.isBlank()) return ""
        return try {
            val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return ""
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val data = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH, iv)
            )
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            // 解密失败可能是密钥变更 (如用户清除数据后重新安装)
            // 返回空串,上层当作未配置处理
            ""
        }
    }

    /**
     * 确保 Keystore 中有密钥。没有则生成。
     * 密钥属性: AES-256, GCM 模式, 仅在本应用内可用, 不可导出。
     */
    private fun ensureKeyExists() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        val entry = keyStore.getEntry(KEY_ALIAS, null)
            as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("Keystore 密钥不存在: $KEY_ALIAS")
        return entry.secretKey
    }
}
