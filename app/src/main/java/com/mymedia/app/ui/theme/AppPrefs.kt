package com.mymedia.app.ui.theme

import android.content.Context

/** 主题模式：跟随系统 / 浅色 / 深色 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

/** 设置持久化 */
object AppPrefs {
    private const val PREFS = "app_settings"

    fun getThemeMode(ctx: Context): ThemeMode {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("theme", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return runCatching { ThemeMode.valueOf(s) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(ctx: Context, mode: ThemeMode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("theme", mode.name).apply()
    }

    /** 0 = 无限制；1~20 = 指定并发数 */
    fun getConcurrent(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("concurrent", 2)

    fun setConcurrent(ctx: Context, n: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().
            putInt("concurrent", if (n <= 0) 0 else n.coerceIn(1, 20)).apply()
    }
}
