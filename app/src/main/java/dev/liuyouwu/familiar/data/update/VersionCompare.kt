package dev.liuyouwu.familiar.data.update

/** 逐段数值版本比较；任何非法输入按安全侧处理（视为无更新），绝不因畸形 tag 触发升级 */
object VersionCompare {

    fun parse(v: String): List<Long>? {
        val s = v.trim().removePrefix("v").removePrefix("V")
        if (s.isEmpty() || s.length > 32) return null
        val out = ArrayList<Long>(6)
        for (seg in s.split('.')) {
            if (seg.isEmpty() || seg.length > 10 || seg.any { it !in '0'..'9' }) return null
            out.add(seg.toLong())
        }
        return out
    }

    /** >0 local 新；<0 remote 新；0 相等。任一非法返回 0 */
    fun compare(local: String, remote: String): Int {
        val a = parse(local) ?: return 0
        val b = parse(remote) ?: return 0
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    fun isUpdateAvailable(localVersionName: String, remoteTag: String?): Boolean {
        if (remoteTag.isNullOrBlank()) return false
        return compare(localVersionName, remoteTag.trim()) < 0
    }
}
