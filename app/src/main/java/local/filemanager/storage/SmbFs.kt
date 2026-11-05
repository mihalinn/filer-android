package local.filemanager

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as SmbFile

/** NAS などの SMB 共有(Windows 共有)。1つの共有を1つの Fs として扱う。 */
class SmbFs(val cfg: RemoteConfig) : Fs {
    override val id: String get() = "smb:${cfg.id}"
    override val isLocal = false

    private val lock = Any()
    private var client: SMBClient? = null
    private var share: DiskShare? = null

    private fun rel(path: String) = path.trim('/').replace('/', '\\')

    private fun connect(): DiskShare = synchronized(lock) {
        if (stale) {
            stale = false
            if (active.get() == 0) closeQuietly()
        }
        share?.takeIf { it.isConnected }?.let { return it }
        closeQuietly()
        ensureBouncyCastle()
        val c = SMBClient(
            SmbConfig.builder()
                .withTimeout(20, TimeUnit.SECONDS)
                // 回線が遅い・不安定なとき(外出先の VPN など)に備え、止まったら早めに気づいて続きから読み直す
                .withSoTimeout(25, TimeUnit.SECONDS)
                .withReadBufferSize(CHUNK)
                .withWriteBufferSize(CHUNK)
                .build(),
        )
        try {
            val session = c.connect(cfg.host, cfg.port).authenticate(
                if (cfg.user.isBlank()) AuthenticationContext.guest()
                else AuthenticationContext(cfg.user, cfg.password.toCharArray(), cfg.domain),
            )
            val disk = session.connectShare(cfg.share) as? DiskShare
                ?: throw OpException(Txt(R.string.smb_fs_001, cfg.share))
            client = c
            share = disk
            disk
        } catch (e: Exception) {
            runCatching { c.close() }
            throw translate(e)
        }
    }

    /** 接続を作り直した回数。失敗した読み書きが「自分が使っていた接続」だけを閉じるための目印。 */
    private var epoch = 0

    /** 開いたままの読み書き(open / create / openRandom)の数。0 のときだけ、使っていない接続を捨てられる。 */
    private val active = java.util.concurrent.atomic.AtomicInteger()

    private fun currentEpoch(): Int = synchronized(lock) { epoch }

    /** 接続を手放す。切れた相手への後始末は待たされることがあるので、裏のスレッドで行う(その間も次の接続を作れる)。 */
    private fun closeQuietly() {
        val s = share
        val c = client
        share = null
        client = null
        if (s == null && c == null) return
        epoch++
        Thread({
            runCatching { s?.close() }
            runCatching { c?.close() }
        }, "filer-smb-close").apply { isDaemon = true }.start()
    }

    fun close() = synchronized(lock) { closeQuietly() }

    /** 失敗したときに、その失敗が起きた接続(epoch)が今も使われているなら閉じる。別の読み書きが作り直した接続は壊さない。 */
    private fun closeIfEpoch(seen: Int) = synchronized(lock) { if (epoch == seen) closeQuietly() }

    @Volatile
    private var stale = false

    /**
     * アプリが裏から戻ったとき用。次に接続を使うとき、開いている読み書きが無ければ接続を捨ててつなぎ直す
     * (切れた接続を使って待たされないように)。待たないので、画面のスレッドから呼べる。
     */
    fun markStale() {
        stale = true
    }

    private fun translate(e: Exception): Exception = when (e) {
        is OpException -> e
        is SMBApiException -> OpException(
            when (e.status.name) {
                "STATUS_LOGON_FAILURE", "STATUS_WRONG_PASSWORD", "STATUS_NO_SUCH_USER" -> Txt(R.string.smb_fs_002)
                "STATUS_BAD_NETWORK_NAME" -> Txt(R.string.smb_fs_003, cfg.share)
                "STATUS_ACCESS_DENIED" -> Txt(R.string.smb_fs_004)
                "STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND", "STATUS_NO_SUCH_FILE" -> Txt(R.string.smb_fs_005)
                "STATUS_OBJECT_NAME_COLLISION" -> Txt(R.string.smb_fs_006)
                "STATUS_DISK_FULL" -> Txt(R.string.smb_fs_007)
                "STATUS_SHARING_VIOLATION" -> Txt(R.string.smb_fs_008)
                else -> Txt(R.string.smb_fs_009, e.status.name)
            },
        )
        else -> OpException(Txt(R.string.smb_fs_010, cfg.host, e.message ?: e.javaClass.simpleName))
    }

    /** 通信が切れていたら1回だけつなぎ直して再実行する。認証や権限のエラーは再試行しない。 */
    private fun <T> op(block: (DiskShare) -> T): T {
        var last: Exception? = null
        repeat(2) {
            val seen: Int
            val sh: DiskShare
            synchronized(lock) { sh = connect(); seen = epoch }
            try {
                return block(sh)
            } catch (e: OpException) {
                throw e
            } catch (e: SMBApiException) {
                throw translate(e)
            } catch (e: Exception) {
                last = e
                closeIfEpoch(seen)
            }
        }
        throw translate(last!!)
    }

    override fun list(path: String): List<FileItem>? = op { sh ->
        sh.list(rel(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                FileItem(child(path, it.fileName), it.fileName, dir, if (dir) 0L else it.endOfFile, it.lastWriteTime.toEpochMillis())
            }
    }

    override fun stat(path: String): FileItem? = op { sh ->
        val r = rel(path)
        if (r.isEmpty()) return@op FileItem(path, cfg.name, true, 0L, 0L)
        val dir = sh.folderExists(r)
        if (!dir && !sh.fileExists(r)) return@op null
        val info = sh.getFileInformation(r)
        FileItem(path, r.substringAfterLast('\\'), dir, info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.toEpochMillis())
    }

    override fun exists(path: String): Boolean = op { sh ->
        val r = rel(path)
        r.isEmpty() || sh.fileExists(r) || sh.folderExists(r)
    }

    override fun mkdir(path: String) = op { sh -> sh.mkdir(rel(path)) }

    override fun delete(path: String) = op { sh ->
        val r = rel(path)
        if (sh.folderExists(r)) sh.rmdir(r, true) else sh.rm(r)
    }

    override fun rename(path: String, newName: String): String {
        FileOps.validateName(newName)?.let { throw OpException(it) }
        val parent = parent(path) ?: throw OpException(Txt(R.string.smb_fs_011))
        val dst = child(parent, newName)
        moveInside(path, dst)
        return dst
    }

    override fun moveInside(src: String, dst: String): Boolean = op { sh ->
        val s = rel(src)
        val d = rel(dst)
        if (sh.fileExists(d) || sh.folderExists(d)) throw OpException(Txt(R.string.smb_fs_012))
        val options = if (sh.folderExists(s)) EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
        else EnumSet.noneOf(SMB2CreateOptions::class.java)
        sh.open(s, EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, options)
            .use { it.rename(d) }
        true
    }

    /**
     * 読み出し。1 回に読むのは CHUNK バイトまでで、先読みはしない。通信が切れたら、つなぎ直して切れた位置から続ける。
     * (smbj 標準の入力ストリームは 1MB ずつ先読みするため、細い回線では止まったように見えることがあった)
     */
    override fun open(path: String): InputStream {
        var file: SmbFile? = openForRead(path)
        var seen = currentEpoch()
        active.incrementAndGet()
        val fileLock = Any()
        // 先読みのスレッドからも呼ばれる
        fun fileNow(): SmbFile = synchronized(fileLock) { file ?: openForRead(path).also { file = it; seen = currentEpoch() } }
        val ahead = ReadAhead(CHUNK, readAheadDepth) { pos, buf, off, len -> fileNow().read(buf, pos, off, len) }
        val closedOnce = java.util.concurrent.atomic.AtomicBoolean()
        return ResumingInput(
            chunk = CHUNK, retries = RETRIES, wait = { ResumingOutput.sleepFor(it) }, isTransient = { transientError(it) }, fail = { translate(it) },
            readAt = { pos, buf, off, len -> ahead.readAt(pos, buf, off, len) },
            reconnect = {
                ahead.reset()
                val used = synchronized(fileLock) { runCatching { file?.close() }; file = null; seen }
                closeIfEpoch(used)
            },
            onClose = {
                ahead.close()
                synchronized(fileLock) { runCatching { file?.close() } }
                if (closedOnce.compareAndSet(false, true)) active.decrementAndGet()
            },
        )
    }

    private fun openForRead(path: String): SmbFile = op { sh ->
        sh.openFile(
            rel(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
    }

    /**
     * 書き込み。返事を待たずに何個か続けて送る。通信が切れたら、つなぎ直して確認できていない分から続ける。
     * 閉じるときに、全部書き終わるのを待つ。
     */
    override fun create(path: String): OutputStream {
        var file: SmbFile? = op { sh ->
            sh.openFile(
                rel(path), EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
            )
        }
        var seen = currentEpoch()
        active.incrementAndGet()
        val fileLock = Any()
        // 書き込みのスレッドから同時に呼ばれる
        fun fileNow(): SmbFile = synchronized(fileLock) {
            file ?: op { sh ->
                // つなぎ直したあとは、できかけのファイルを消さずにそのまま開く
                sh.openFile(
                    rel(path), EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
                )
            }.also { file = it; seen = currentEpoch() }
        }
        val closedOnce = java.util.concurrent.atomic.AtomicBoolean()
        return WriteBehind(
            chunk = CHUNK, depth = writeBehindDepth, retries = RETRIES,
            wait = { ResumingOutput.sleepFor(it) }, isTransient = { transientError(it) }, fail = { translate(it) },
            writeAt = { pos, buf, off, len -> fileNow().write(buf, pos, off, len) },
            reconnect = {
                val used = synchronized(fileLock) { runCatching { file?.close() }; file = null; seen }
                closeIfEpoch(used)
            },
            onClose = {
                synchronized(fileLock) { runCatching { file?.close() } }
                if (closedOnce.compareAndSet(false, true)) active.decrementAndGet()
            },
        )
    }

    /** 通信の途切れ(やり直せるもの)かどうか。権限や名前の誤りなど、やり直しても同じ失敗になるものは false。 */
    private fun transientError(e: Exception): Boolean = when (e) {
        is OpException -> false
        is SMBApiException -> e.status.name in TRANSIENT_STATUS
        else -> true
    }
    override fun space(path: String): Pair<Long, Long>? = op { sh ->
        val info = sh.shareInformation
        info.totalSpace to info.freeSpace
    }

    override fun openRandom(path: String): RandomSource? = op { sh ->
        val f = sh.openFile(
            rel(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        val length = f.fileInformation.standardInformation.endOfFile
        active.incrementAndGet()
        val closedOnce = java.util.concurrent.atomic.AtomicBoolean()
        object : RandomSource {
            override val size = length
            override fun readAt(pos: Long, buf: ByteArray, off: Int, len: Int): Int = synchronized(f) {
                val n = f.read(buf, pos, off, len)
                if (n <= 0) -1 else n
            }

            override fun close() {
                runCatching { f.close() }
                if (closedOnce.compareAndSet(false, true)) active.decrementAndGet()
            }
        }
    }

    /** 接続できて共有の直下を読めるかを確かめる。成功すれば項目数を返す。 */
    fun test(): Int = op { sh -> sh.list("").count { it.fileName != "." && it.fileName != ".." } }

    /** 前から順に読むとき、返事を待たずに先に要求しておく数(1 なら先読みなし)。 */
    internal var readAheadDepth = READ_AHEAD

    /** 書き込むとき、返事を待たずに続けて送る数(1 なら 1 つずつ)。 */
    internal var writeBehindDepth = WRITE_BEHIND

    companion object {
        const val READ_AHEAD = 4
        const val WRITE_BEHIND = 6

        /** 1 回の読み書きの大きさ。小さめにして、細い回線でも進み具合が見えて、切れても失う量が少ないようにする。 */
        const val CHUNK = 256 * 1024
        const val RETRIES = 6
        private val TRANSIENT_STATUS = setOf(
            "STATUS_USER_SESSION_DELETED", "STATUS_NETWORK_NAME_DELETED", "STATUS_CONNECTION_DISCONNECTED", "STATUS_CONNECTION_RESET",
            "STATUS_NETWORK_SESSION_EXPIRED", "STATUS_FILE_CLOSED", "STATUS_INVALID_HANDLE", "STATUS_CANCELLED",
        )

        /** Android 内蔵の簡易版 BouncyCastle には NTLM に要る MD4 が無いので、完全版に差し替える。 */
        private fun ensureBouncyCastle() {
            val name = BouncyCastleProvider.PROVIDER_NAME
            if (Security.getProvider(name) !is BouncyCastleProvider) {
                Security.removeProvider(name)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
    }
}
