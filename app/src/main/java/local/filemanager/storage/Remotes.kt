package local.filemanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 保存した NAS(SMB 共有)の接続先。 */
data class RemoteConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    /** 開いたときの最初のフォルダ(共有の中のパス)。空なら共有の直下。 */
    val startPath: String = "",
    /** 空ならゲスト接続。 */
    val user: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val startDir: String get() = "/" + startPath.trim('/').trim()
}

/** パスワードは端末の Keystore の鍵で暗号化して保存する(アプリ外には出ない)。 */
private object Secrets {
    private const val ALIAS = "filer_remote_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** 復号できなければ空文字(鍵が消えた場合など。パスワードを入れ直してもらう)。 */
    fun decrypt(text: String): String {
        if (text.isEmpty()) return ""
        return try {
            val all = Base64.decode(text, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12))
        } catch (e: Exception) {
            ""
        }
    }
}

object RemoteStore {
    private const val PREFS = "fm_remotes"
    private const val KEY = "list"

    fun load(ctx: Context): List<RemoteConfig> {
        val text = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteConfig(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.optInt("port", 445),
                    share = o.getString("share"),
                    startPath = o.optString("start", ""),
                    user = o.optString("user", ""),
                    password = Secrets.decrypt(o.optString("pw", "")),
                    domain = o.optString("domain", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, list: List<RemoteConfig>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("id", r.id).put("name", r.name).put("host", r.host).put("port", r.port)
                    .put("share", r.share).put("start", r.startPath).put("user", r.user)
                    .put("pw", Secrets.encrypt(r.password)).put("domain", r.domain),
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
