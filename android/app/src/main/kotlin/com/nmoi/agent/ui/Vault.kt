package com.nmoi.agent.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥仓（D156 / `docs/secrets.md` 的 L1；D157 起按供应商分开）。
 *
 * 只解决一件事：**落盘的是密文**。设备被拿走、备份被翻出来，看到的是
 * `[12 字节 IV][密文]`，主密钥在 AndroidKeyStore 里不可导出。
 *
 * 明确不解决的（secrets.md 里写死了）：同 UID 的代码可以让 App 自己去解密。
 * 所以这里不做"藏密钥"的表演——容器隔离是 L3 的事，不是这个文件的功劳。
 *
 * 为什么不用 SQLite：库要能导出、能备份、能被 `load_units` 之类的路径碰到。
 * 凭据单独一个文件，删掉它等于吊销，也不会跟着库一起被拷走。
 *
 * **一个供应商一个文件**（D157）：`vault/provider-<id>.bin`。删掉某个供应商时
 * 只删它自己那一个文件，不碰别人的钥匙。`id = default` 沿用 D156 那版的文件名
 * `vault/provider.bin`——老配置升级上来不需要重新填密钥。
 */
internal object Vault {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "nmoi.vault.v1"
    private const val LEGACY_ID = "default"
    private const val LEGACY_FILE = "provider.bin"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun dir(context: Context): File =
        File(context.filesDir, "vault").apply { mkdirs() }

    /** 文件名只认字母数字与 `-_`：id 是我们自己生成的，但**别拿库里的字符串当路径**。 */
    private fun fileName(id: String): String {
        if (id == LEGACY_ID) return LEGACY_FILE
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return "provider-$safe.bin"
    }

    private fun file(context: Context, id: String): File = File(dir(context), fileName(id))

    /** 有没有存过。不解密，只看文件在不在——打开设置页不该触发一次密钥运算。 */
    fun has(context: Context, id: String): Boolean = file(context, id).exists()

    /**
     * 加密写到 `vault/provider-<id>.bin`。
     *
     * 写临时文件再改名：写一半被杀不会留下半截密文。改名失败就直接写，
     * 也可能失败——那就把错误交回去，别装作存上了。
     */
    fun save(context: Context, id: String, plain: String): Result<Unit> = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val target = file(context, id)
        val tmp = File(dir(context), "${target.name}.tmp")
        tmp.writeBytes(cipher.iv + body)
        if (!tmp.renameTo(target)) {
            target.writeBytes(cipher.iv + body)
            tmp.delete()
        }
    }

    /**
     * 解出来。返回 `null` 的三种情况要分开看（日志里会写是哪一种）：
     * 没存过 / 文件坏了 / 主密钥没了（换机、清数据、Keystore 被重置）。
     * 后两种都意味着"要重填一次密钥"，界面该这么说，而不是装作没配过。
     */
    fun load(context: Context, id: String): String? {
        val file = file(context, id)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= IV_BYTES) {
                NLog.w("Vault", "密文长度不对（${bytes.size} 字节），当成没配过：$id")
                return null
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey(),
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
            )
            val plain = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
            String(plain, Charsets.UTF_8)
        } catch (failure: Throwable) {
            // 不打明文、不打密文。只说哪一类失败。
            NLog.w("Vault", "解不开（${failure.javaClass.simpleName}），需要重填密钥：$id")
            null
        }
    }

    /** 吊销：删掉这一个供应商的密钥文件。主密钥留着（下次存同一把，省一次生成）。 */
    fun clear(context: Context, id: String) {
        val file = file(context, id)
        if (file.exists() && !file.delete()) {
            NLog.w("Vault", "删不掉 ${file.name}（还在盘上，但界面已当作没配过）")
        }
    }

    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 常驻 API key 不绑生物识别：否则每一次请求都要按一次指纹（secrets.md L1）。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}

/** `sk-abcdef…9f3a` → `sk-****3f9a`。太短就全星：短串的前缀本身就是信息。 */
internal fun maskSecret(plain: String): String {
    val text = plain.trim()
    return when {
        text.isEmpty() -> ""
        text.length <= 8 -> "********"
        else -> text.take(3) + "****" + text.takeLast(4)
    }
}
