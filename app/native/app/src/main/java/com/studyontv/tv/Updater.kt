package com.studyontv.tv

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * 版本检查：只使用蒲公英应用内更新接口（appKey + 当前版本）。
 * 不再访问 GitHub 或第三方镜像；蒲公英失败时由更新页引导用户手动下载。
 */
object Updater {
    const val OWNER_REPO = "chadyi/StudyOnTV"
    val DEFAULT_MIRRORS = emptyList<String>()
    private const val CACHE_MS = 6L * 60 * 60 * 1000

    data class Info(
        val latest: String,
        val current: String,
        val force: Boolean,
        val notes: String,
        val url: String,
        val sha256: String = "",
        val source: String = ""
    )

    fun api(ctx: Context): String = Prefs.updateApi(ctx)

    fun mirrors(ctx: Context): List<String> {
        val saved = Prefs.updateMirrors(ctx) ?: return DEFAULT_MIRRORS
        return saved.split('\n', ',', ' ').map { it.trim() }.filter { it.startsWith("https://") }
            .map { if (it.endsWith("/")) it else "$it/" }.distinct()
    }

    /** 可用的 APK 下载地址 */
    fun downloadCandidates(ctx: Context, url: String): List<Pair<String, String>> =
        listOf("蒲公英" to url)

    fun check(ctx: Context, forceRefresh: Boolean = false, onResult: (Info?) -> Unit) {
        val cachedAt = Prefs.updateCheckedAt(ctx)
        val cached = cachedInfo(ctx)
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
            onResult(cached)
            return
        }
        Thread { onResult(checkNow(ctx).first) }.apply { isDaemon = true }.start()
    }

    private val checkingFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    val checking: Boolean get() = checkingFlag.get()

    /** 管理面板“立即检查”：后台执行，面板轮询 status()（不能加锁，否则会等正在进行的检查） */
    fun startCheck(ctx: Context) {
        if (!checkingFlag.compareAndSet(false, true)) return
        Thread { try { checkNow(ctx) } finally { checkingFlag.set(false) } }.apply { isDaemon = true }.start()
    }

    /** 同步检查；返回结果和每个来源的尝试记录（给管理面板显示） */
    @Synchronized
    fun checkNow(ctx: Context): Pair<Info?, JSONArray> {
        val log = JSONArray()
        val current = currentVersion(ctx)
        val started = System.currentTimeMillis()
        var info: Info? = null
        try {
            info = Pgyer.info(ctx, current, currentVersionCode(ctx))
            log.put(JSONObject().put("source", "蒲公英").put("ok", info != null).put("ms", System.currentTimeMillis() - started)
                .put("message", if (info == null) "当前已是最新版本" else "v" + info.latest))
        } catch (e: Exception) {
            Diag.error("Update", "Pgyer check failed", e)
            log.put(JSONObject().put("source", "蒲公英").put("ok", false).put("ms", System.currentTimeMillis() - started)
                .put("message", e.message?.take(160) ?: e.javaClass.simpleName))
        }
        Prefs.setUpdateInfo(ctx, info?.let { toJson(it).toString() } ?: "")
        Prefs.setUpdateCheckedAt(ctx, System.currentTimeMillis())
        Prefs.setUpdateLog(ctx, JSONObject().put("at", System.currentTimeMillis()).put("items", log).toString())
        return info to log
    }

    fun toJson(info: Info): JSONObject = JSONObject()
        .put("latest", info.latest).put("current", info.current).put("force", info.force)
        .put("notes", info.notes).put("url", info.url).put("sha256", info.sha256).put("source", info.source)

    fun cachedInfo(ctx: Context): Info? {
        val raw = Prefs.updateInfo(ctx)
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            Info(o.optString("latest"), o.optString("current"), o.optBoolean("force"),
                o.optString("notes"), o.optString("url"), o.optString("sha256"), o.optString("source"))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 比较远程版本是否比本地版本更新（遵循语义化版本 SemVer）：
     * 主版本号段 `[major, minor, patch]` 与先行版后缀（如 `-beta`）分开解析，
     * 保证正式版 `0.4.4` 比同号内测包 `0.4.4-beta` 新，而 `0.4.4-beta` 不会被判成比正式版 `0.4.4` 新。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val remoteClean = latest.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val localClean = current.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        if (remoteClean.isEmpty()) return false
        val remoteCore = remoteClean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val localCore = localClean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteCore.size, localCore.size)) {
            val x = remoteCore.getOrElse(i) { 0 }
            val y = localCore.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        val remotePre = remoteClean.substringAfter('-', "")
        val localPre = localClean.substringAfter('-', "")
        if (remotePre.isEmpty() && localPre.isEmpty()) return false
        if (remotePre.isEmpty() && localPre.isNotEmpty()) return true
        if (remotePre.isNotEmpty() && localPre.isEmpty()) return false
        val rParts = remotePre.split('.', '-')
        val lParts = localPre.split('.', '-')
        for (i in 0 until minOf(rParts.size, lParts.size)) {
            val rp = rParts[i]
            val lp = lParts[i]
            if (rp == lp) continue
            val rn = rp.toIntOrNull()
            val ln = lp.toIntOrNull()
            return when {
                rn != null && ln != null -> rn > ln
                rn != null -> false
                ln != null -> true
                else -> rp > lp
            }
        }
        return rParts.size > lParts.size
    }

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0" } catch (_: Exception) { "0.0.0" }

    fun currentVersionCode(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Exception) { 0 }

    fun newerThanCurrent(ctx: Context, info: Info?): Info? =
        if (info != null && isNewer(info.latest, currentVersion(ctx))) info else null

    /** 管理面板用：当前状态 */
    fun status(ctx: Context): JSONObject {
        val info = cachedInfo(ctx)
        val log = try { JSONObject(Prefs.updateLog(ctx)) } catch (_: Exception) { JSONObject() }
        return JSONObject().put("current", currentVersion(ctx))
            .put("latest", info?.latest ?: "").put("hasUpdate", newerThanCurrent(ctx, info) != null)
            .put("notes", info?.notes ?: "").put("source", info?.source ?: "")
            .put("url", info?.url ?: "")
            .put("checkedAt", Prefs.updateCheckedAt(ctx)).put("checking", checking)
            .put("logAt", log.optLong("at"))
            .put("api", api(ctx)).put("mirrors", JSONArray(mirrors(ctx))).put("defaultMirrors", JSONArray(DEFAULT_MIRRORS))
            .put("lastDownloadSource", Prefs.updateLastSource(ctx))
            .put("canInstall", ApkInstaller.canInstall(ctx))
            .put("installError", Prefs.updateInstallError(ctx))
            .put("log", log.optJSONArray("items") ?: JSONArray())
    }

    fun saveSettings(ctx: Context, input: JSONObject): JSONObject {
        if (input.has("api")) {
            val api = input.optString("api").trim()
            require(api.isEmpty() || api.startsWith("https://")) { "自定义更新地址需要以 https:// 开头" }
            Prefs.setUpdateApi(ctx, api)
        }
        if (input.has("mirrors")) {
            val raw = input.get("mirrors")
            val list = if (raw is JSONArray) (0 until raw.length()).map { raw.optString(it) } else raw.toString().split('\n', ',')
            val clean = list.map { it.trim() }.filter { it.isNotEmpty() }
            clean.forEach { require(it.startsWith("https://")) { "镜像地址需要以 https:// 开头：$it" } }
            require(clean.size <= 10) { "最多填写 10 个镜像" }
            Prefs.setUpdateMirrors(ctx, if (input.optBoolean("resetMirrors")) null else clean.joinToString("\n"))
        }
        Prefs.setUpdateCheckedAt(ctx, 0)
        return status(ctx)
    }
}
