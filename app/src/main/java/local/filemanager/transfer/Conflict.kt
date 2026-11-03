package local.filemanager

/** 貼り付け先に同じ名前があったときの扱い。 */
enum class ConflictAction {
    /** 置き換える(フォルダ同士は中身をまとめる) */
    OVERWRITE,

    /** 何もしない */
    SKIP,

    /** 「名前 (1)」にして両方残す */
    KEEP_BOTH,
}
