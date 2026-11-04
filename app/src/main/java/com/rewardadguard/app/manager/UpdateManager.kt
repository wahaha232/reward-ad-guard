package com.rewardadguard.app.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/** A newer release found on GitHub. */
data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val sha256Url: String,
    val notes: String
)

/**
 * Cloud update from this project's GitHub releases (2026-11-04).
 *
 * Flow: read `releases/latest` -> download the APK -> check it against the
 * `.sha256` file attached to the same release -> hand it to the system
 * installer, which always shows its own confirmation. Only download URLs of
 * this repository's releases are accepted, the APK size is capped, and nothing
 * else is ever sent or received: the request carries no user data.
 *
 * The release APK is signed with the shared debug key (see app/build.gradle.kts),
 * so it updates the installed copy in place and the accessibility grant,
 * settings and log survive.
 *
 * Every network call blocks; call [checkLatest] and [download] off the main thread.
 */
object UpdateManager {

    private const val REPO = "wahaha232/reward-ad-guard"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val APK_MAX_BYTES = 100L * 1024 * 1024
    private const val TIMEOUT_MS = 15_000

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** The latest release when it is newer than the installed version, else null. */
    fun checkLatest(context: Context): UpdateInfo? {
        val conn = openConnection(LATEST_URL, "application/vnd.github+json")
        // GitHub answers 404 while the repository has no release yet: nothing to
        // update to, not an error worth showing.
        if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        val json = JSONObject(readText(conn.inputStream, 512 * 1024))
        val tag = json.getString("tag_name")
        if (!isNewer(currentVersion(context), tag)) return null
        val assets = json.getJSONArray("assets")
        var apk: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            val url = asset.getString("browser_download_url")
            if (!url.startsWith(DOWNLOAD_PREFIX)) continue
            if (name.endsWith(".apk")) apk = url
            if (name.endsWith(".apk.sha256")) sha = url
        }
        if (apk == null || sha == null) throw IOException("release has no APK or .sha256 file")
        return UpdateInfo(tag.removePrefix("v"), apk, sha, json.optString("body"))
    }

    /** Downloads and verifies the APK. [onProgress] receives 0..100. */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val expected = readText(connect(info.sha256Url, "*/*").inputStream, 4096)
            .trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw IOException("malformed .sha256 file")
        val dir = File(context.cacheDir, UPDATE_DIR).apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = connect(info.apkUrl, "application/octet-stream")
        val total = conn.contentLengthLong
        if (total > APK_MAX_BYTES) throw IOException("APK too large")
        conn.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    done += n
                    if (done > APK_MAX_BYTES) throw IOException("APK too large")
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            target.delete()
            throw IOException("SHA-256 mismatch, download deleted")
        }
        return target
    }

    /** Android 8+ needs the user to allow this app to install unknown apps. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Compares versions such as "1.2.3" and "v1.2.4". */
    fun isNewer(current: String, latest: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('+').substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(current)
        val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    private fun readText(input: InputStream, max: Int): String = input.use {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw IOException("response too large")
        }
        out.toString("UTF-8")
    }

    private fun connect(url: String, accept: String): HttpURLConnection {
        val conn = openConnection(url, accept)
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        return conn
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection {
        require(url.startsWith("https://"))
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "RewardAdGuard")
        conn.setRequestProperty("Accept", accept)
        return conn
    }

    /** Cache sub-directory shared through the FileProvider (res/xml/file_paths.xml). */
    const val UPDATE_DIR = "updates"
}
