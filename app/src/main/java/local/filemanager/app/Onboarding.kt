package local.filemanager

/** 初回設定画面(はじめの案内)の判断。Android に依存しない。 */
object Onboarding {
    /** 段階: 0 ようこそ(言語)、1 見た目、2 ファイルへのアクセス(通知)、3 NAS と復元 */
    const val STEPS = 4
    const val STEP_ACCESS = 2

    /**
     * 案内を出すか。初めて使う人にだけ出す。
     * すでにファイルへのアクセスを許可している人(= 使っている人)には出さない。
     * ただし、入れ直した直後は、許可が端末に残っていることがあるので、入れたばかりなら出す。
     */
    fun shouldShow(onboarded: Boolean, hasAccess: Boolean, freshInstall: Boolean = false): Boolean =
        !onboarded && (!hasAccess || freshInstall)

    /** 入れたばかりか(初めて入れた時刻と、最後に更新した時刻が同じ = 上書き更新を一度も受けていない)。 */
    fun isFreshInstall(firstInstallTime: Long, lastUpdateTime: Long): Boolean = firstInstallTime == lastUpdateTime

    /** 次の段階へ進めるか。ファイルへのアクセスの段階は、許可されるまで進めない。 */
    fun canNext(step: Int, hasAccess: Boolean): Boolean = step != STEP_ACCESS || hasAccess

    fun clamp(step: Int): Int = step.coerceIn(0, STEPS - 1)
}