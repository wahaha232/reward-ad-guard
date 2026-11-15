package com.rewardadguard.app.collector

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An app the user added to the collector (2026-11-15). It has no hand-written
 * profile: [toProfile] gives it the generic rules (tap the user's reward
 * words, avoid theirs plus the usual dangerous ones, no tab bar to look for).
 * Whether a given app can be collected this way depends on its screens.
 */
data class CustomApp(
    val id: Long,
    val name: String,
    val packageName: String,
    val rewardTerms: List<String>,
    val avoidTerms: List<String> = emptyList()
) {
    val key: String get() = "custom_$id"

    fun toProfile(): AppProfile = AppProfile(
        key = key,
        name = name,
        packageName = packageName,
        rewardTerms = rewardTerms,
        avoidTerms = (avoidTerms + GENERIC_AVOID).distinct(),
        // No tab bar known: every screen of the package counts as the app's own.
        chromeTerms = emptyList(),
        adMinWatchMillis = 20_000L,
        adTimeoutMillis = 120_000L,
        maxRewards = 30,
        maxRepeatPerTarget = 5,
        launchWaitMillis = 8_000L
    )

    companion object {
        /** Words never tapped in an unknown app: payments, installs, leaving. */
        val GENERIC_AVOID = listOf(
            "已領取", "已完成", "明天", "明日", "購買", "付款", "儲值", "訂閱", "安裝", "下載",
            "取消", "關閉", "返回", "登出", "刪除", "分享", "邀請", "兌換", "設定"
        )

        /** Suggested reward words for a new app. */
        val DEFAULT_REWARD = listOf("領取", "看廣告", "觀看廣告", "簽到", "免費領取")
    }
}

/**
 * The collector's editable app list: built-in profiles the user hid and apps
 * the user added. Kept in SharedPreferences and mirrored in memory so
 * [CollectorProfiles] can read it from any thread without disk access.
 */
object CustomApps {

    private const val PREFS = "collector_apps"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_CUSTOM = "custom"

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _custom = MutableStateFlow<List<CustomApp>>(emptyList())
    val custom: StateFlow<List<CustomApp>> = _custom.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called once from Application.onCreate. */
    fun load(context: Context) {
        val p = prefs(context)
        _hidden.value = p.getString(KEY_HIDDEN, "").orEmpty().split(",").filter { it.isNotBlank() }.toSet()
        _custom.value = decode(p.getString(KEY_CUSTOM, null))
    }

    /** JVM tests only: set the list without SharedPreferences. */
    internal fun setForTest(hidden: Set<String>, custom: List<CustomApp>) {
        _hidden.value = hidden
        _custom.value = custom
    }

    fun setHidden(context: Context, key: String, hide: Boolean) {
        val next = if (hide) _hidden.value + key else _hidden.value - key
        prefs(context).edit().putString(KEY_HIDDEN, next.joinToString(",")).apply()
        _hidden.value = next
    }

    fun upsert(context: Context, app: CustomApp) {
        saveCustom(context, _custom.value.filterNot { it.id == app.id } + app)
    }

    fun remove(context: Context, id: Long) {
        saveCustom(context, _custom.value.filterNot { it.id == id })
    }

    private fun saveCustom(context: Context, list: List<CustomApp>) {
        prefs(context).edit().putString(KEY_CUSTOM, encode(list)).apply()
        _custom.value = list
    }

    // ---- storage: one app per line, "id|name|package|reward;reward|avoid;avoid" ----

    /** Strips the separators from user input so a line always splits back. */
    fun clean(text: String): String = text.replace("|", " ").replace(";", " ").replace("\n", " ").trim()

    fun encode(list: List<CustomApp>): String = list.joinToString("\n") { app ->
        listOf(
            app.id.toString(),
            clean(app.name),
            clean(app.packageName),
            app.rewardTerms.map(::clean).filter { it.isNotEmpty() }.joinToString(";"),
            app.avoidTerms.map(::clean).filter { it.isNotEmpty() }.joinToString(";")
        ).joinToString("|")
    }

    fun decode(raw: String?): List<CustomApp> = raw.orEmpty().lines().mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size != 5) return@mapNotNull null
        val id = parts[0].toLongOrNull() ?: return@mapNotNull null
        val pkg = parts[2].trim().ifEmpty { return@mapNotNull null }
        val reward = parts[3].split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (reward.isEmpty()) return@mapNotNull null
        CustomApp(id, parts[1].trim().ifEmpty { pkg }, pkg, reward, parts[4].split(";").map { it.trim() }.filter { it.isNotEmpty() })
    }

    /** "領取, 看廣告，簽到" -> [領取, 看廣告, 簽到]. */
    fun splitTerms(text: String): List<String> =
        text.split(',', '，', '、', '\n').map { clean(it) }.filter { it.isNotEmpty() }.distinct()
}
