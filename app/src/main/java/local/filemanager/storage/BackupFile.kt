package local.filemanager

import org.bouncycastle.crypto.generators.SCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * バックアップファイル(.filerbak)の入れ物。Android に依存しない。
 * 中身の意味(何を入れるか)は BackupCodec が決める。ここは「包む・開く」だけ。
 *
 * パスワードを決めたとき(暗号化あり):
 *   [ 8B 目印 | 1B 版 | 1B 鍵の作り方(1=scrypt) | 1B logN | 1B r | 1B p | 16B salt | 12B nonce | 暗号文+認証タグ ]
 *   鍵は、パスワードから scrypt(総当たりに強い作り方)で 32 バイト作り、AES-256-GCM で暗号化する。
 *   先頭の 41 バイトも認証の対象なので、書き換えると開けない。パスワードを忘れると、誰にも開けない。
 * パスワードを決めないとき(簡易な暗号化):
 *   [ 8B 目印 | 1B 版 | 1B 鍵の作り方(2=アプリに入っている鍵) | 12B nonce | 暗号文+認証タグ ]
 *   AES-256-GCM だが、鍵がアプリの中に入っている。ふだん目に入る(メモ帳で開く)のを防ぐだけで、
 *   ソースを知っている人なら解読できる(ソースは公開されている)。強く守るには、パスワードを決める。壊れたことは見つけられる。
 */
object BackupFile {
    const val VERSION = 1
    const val MIN_PASSWORD = 8

    /** 読み込むファイルの大きさの上限(これより大きいものは、バックアップではないとみなす) */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    /** scrypt の重さ。N = 2^logN。メモリは約 128 × N × r バイト */
    class Cost(val logN: Int, val r: Int, val p: Int)

    /** 書き出すときの重さ(メモリ約 32MB、スマホで 1 秒前後)。ファイルに書くので、あとで変えても古いファイルを開ける */
    val DEFAULT_COST = Cost(15, 8, 3)

    /** ファイルの種類。パスワードが要るかどうかを、開く前に知るために使う。SIMPLE はパスワードなし(簡易な暗号化)。 */
    enum class Kind { NOT_BACKUP, TOO_NEW, TOO_BIG, SIMPLE, ENCRYPTED }

    enum class Failure { NOT_BACKUP, TOO_NEW, PASSWORD_OR_CORRUPT, TOO_BIG }

    sealed class Opened {
        class Ok(val plain: ByteArray) : Opened()
        class Fail(val why: Failure) : Opened()
    }

    private val MAGIC = byteArrayOf(0x46, 0x4C, 0x52, 0x42, 0x41, 0x4B, 0x1A, 0x0A) // "FLRBAK" + 区切り
    private const val KDF_SCRYPT = 1
    private const val KDF_BUILTIN = 2
    private const val SALT = 16
    private const val NONCE = 12
    private const val HEADER = 8 + 1 + 1 + 1 + 1 + 1 + SALT + NONCE
    private const val SIMPLE_HEADER = 8 + 1 + 1 + NONCE
    private const val TAG_BYTES = 16

    /** 簡易な暗号化の鍵。アプリに入っているので、秘密ではない(上の説明のとおり) */
    private val BUILTIN_KEY: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest("Filer backup / simple encryption / v1".toByteArray(Charsets.UTF_8)) }

    private fun key(password: String, salt: ByteArray, c: Cost): ByteArray {
        val pw = Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        return SCrypt.generate(pw, salt, 1 shl c.logN, c.r, c.p, 32)
    }

    /** ファイルの種類を調べる(パスワードは要らない)。 */
    fun peek(file: ByteArray): Kind {
        if (file.size > MAX_FILE_BYTES) return Kind.TOO_BIG
        if (file.size < 10 || !file.copyOfRange(0, 8).contentEquals(MAGIC)) return Kind.NOT_BACKUP
        val ver = file[8].toInt() and 0xFF
        if (ver > VERSION) return Kind.TOO_NEW
        if (ver != VERSION) return Kind.NOT_BACKUP
        return when (file[9].toInt()) {
            KDF_BUILTIN -> Kind.SIMPLE
            KDF_SCRYPT -> Kind.ENCRYPTED
            else -> Kind.NOT_BACKUP
        }
    }

    /** 書き出すファイルの中身にする。password が空なら簡易な暗号化、空でなければパスワードで暗号化する。 */
    fun pack(plain: ByteArray, password: String, cost: Cost = DEFAULT_COST): ByteArray =
        if (password.isEmpty()) sealSimple(plain) else seal(plain, password, cost)

    private fun sealSimple(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also { SecureRandom().nextBytes(it) }
        val header = ByteArray(SIMPLE_HEADER)
        System.arraycopy(MAGIC, 0, header, 0, 8)
        header[8] = VERSION.toByte()
        header[9] = KDF_BUILTIN.toByte()
        System.arraycopy(nonce, 0, header, 10, NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(BUILTIN_KEY, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    /** plain をパスワードで暗号化して、ファイルの中身にする */
    fun seal(plain: ByteArray, password: String, cost: Cost = DEFAULT_COST): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT).also { rnd.nextBytes(it) }
        val nonce = ByteArray(NONCE).also { rnd.nextBytes(it) }
        val header = ByteArray(HEADER)
        System.arraycopy(MAGIC, 0, header, 0, 8)
        header[8] = VERSION.toByte()
        header[9] = KDF_SCRYPT.toByte()
        header[10] = cost.logN.toByte()
        header[11] = cost.r.toByte()
        header[12] = cost.p.toByte()
        System.arraycopy(salt, 0, header, 13, SALT)
        System.arraycopy(nonce, 0, header, 13 + SALT, NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(password, salt, cost), "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    /**
     * ファイルの中身を開く。簡易な暗号化のファイルは、パスワードを見ない。
     * パスワードが違う・壊れている・書き換えられているときは、区別せずに同じ失敗にする。
     */
    fun open(file: ByteArray, password: String): Opened {
        when (peek(file)) {
            Kind.TOO_BIG -> return Opened.Fail(Failure.TOO_BIG)
            Kind.TOO_NEW -> return Opened.Fail(Failure.TOO_NEW)
            Kind.NOT_BACKUP -> return Opened.Fail(Failure.NOT_BACKUP)
            Kind.SIMPLE -> return openSimple(file)
            Kind.ENCRYPTED -> {}
        }
        if (file.size < HEADER + TAG_BYTES) return Opened.Fail(Failure.NOT_BACKUP)
        val logN = file[10].toInt() and 0xFF
        val r = file[11].toInt() and 0xFF
        val p = file[12].toInt() and 0xFF
        // 細工したファイルで、メモリや時間を使い切らせないよう、範囲を決める
        if (logN !in 10..20 || r !in 1..16 || p !in 1..16 || 128L * (1L shl logN) * r > 256L * 1024 * 1024) return Opened.Fail(Failure.NOT_BACKUP)
        return try {
            val salt = file.copyOfRange(13, 13 + SALT)
            val nonce = file.copyOfRange(13 + SALT, HEADER)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(password, salt, Cost(logN, r, p)), "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(file, 0, HEADER)
            Opened.Ok(cipher.doFinal(file, HEADER, file.size - HEADER))
        } catch (e: Exception) {
            Opened.Fail(Failure.PASSWORD_OR_CORRUPT)
        }
    }

    private fun openSimple(file: ByteArray): Opened {
        if (file.size < SIMPLE_HEADER + TAG_BYTES) return Opened.Fail(Failure.NOT_BACKUP)
        return try {
            val nonce = file.copyOfRange(10, SIMPLE_HEADER)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(BUILTIN_KEY, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(file, 0, SIMPLE_HEADER)
            Opened.Ok(cipher.doFinal(file, SIMPLE_HEADER, file.size - SIMPLE_HEADER))
        } catch (e: Exception) {
            Opened.Fail(Failure.PASSWORD_OR_CORRUPT)
        }
    }
}
