package dev.liuyouwu.familiar.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的 AndroidKeyStore AES-GCM 加密存储（不引第三方库）。
 *
 * - 密钥由系统 Keystore 生成并保管（alias "twllm_api_key"，AES 256 位），
 *   不落文件、不可导出，卸载/清数据时随系统销毁；
 * - IV 由 Keystore 每次加密随机生成，前 12 字节与密文同存后整体 Base64（NO_WRAP）；
 * - [decrypt] 失败（密钥被清除/密文损坏/系统故障）返回 null 并打日志，绝不抛出；
 * - [encrypt] 失败（Keystore 不可用等）返回 null，由调用方决定降级策略——
 *   绝不把明文当密文返回，避免调用方误存明文后又清掉旧的明文键导致 Key 丢失。
 */
object CryptoStore {

    private const val TAG = "CryptoStore"
    private const val KEY_ALIAS = "twllm_api_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    // Keystore 操作非线程安全，且避免并发重复生成同名密钥
    private val lock = Any()

    /** 取已有密钥；不存在则生成 256 位 AES-GCM 密钥 */
    private fun getOrCreateKey(): SecretKey {
        synchronized(lock) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null, null) }
            keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }

    /** 加密：成功返回 Base64(IV + 密文)；空串原样返回 ""（对应用户保存了空 Key）；Keystore 不可用返回 null */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + encrypted.size)
            iv.copyInto(out)
            encrypted.copyInto(out, iv.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed，调用方需自行降级（保留旧明文键）", e)
            null
        }
    }

    /**
     * 解密：成功返回明文；密文损坏/密钥不可用等失败返回 null 并打日志。
     * 空串不是失败，原样返回空串（对应用户保存了空 Key）。
     */
    fun decrypt(cipher: String): String? {
        if (cipher.isEmpty()) return ""
        return try {
            val data = Base64.decode(cipher, Base64.NO_WRAP)
            if (data.size <= GCM_IV_BYTES) {
                Log.e(TAG, "decrypt failed: 密文过短（${data.size} 字节）")
                return null
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES)
            )
            String(c.doFinal(data, GCM_IV_BYTES, data.size - GCM_IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed", e)
            null
        }
    }
}
