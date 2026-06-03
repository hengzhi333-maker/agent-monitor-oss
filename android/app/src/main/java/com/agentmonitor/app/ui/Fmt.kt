package com.agentmonitor.app.ui

import java.util.concurrent.TimeUnit

object Fmt {
    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    // 相对时间:刚刚 / 3分钟前 / 2小时前 / 1天前
    fun ago(ms: Long): String {
        if (ms <= 0) return "—"
        val diff = System.currentTimeMillis() - ms
        if (diff < 0) return "刚刚"
        val sec = TimeUnit.MILLISECONDS.toSeconds(diff)
        val min = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hr = TimeUnit.MILLISECONDS.toHours(diff)
        val day = TimeUnit.MILLISECONDS.toDays(diff)
        return when {
            sec < 10 -> "刚刚"
            sec < 60 -> "${sec}秒前"
            min < 60 -> "${min}分钟前"
            hr < 24 -> "${hr}小时前"
            else -> "${day}天前"
        }
    }

    fun shortPath(p: String): String {
        if (p.isBlank()) return ""
        val parts = p.replace('\\', '/').trimEnd('/').split('/')
        return if (parts.size <= 2) p else ".../" + parts.takeLast(2).joinToString("/")
    }
}
