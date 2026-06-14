package com.streamadblock.firetv

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads community filter lists (uBlock Origin, EasyList, EasyPrivacy,
 * AdGuard DNS) and caches them on disk so the Fire TV blocker can use the
 * full ad/tracker ecosystem instead of only the ~199 bundled domains.
 *
 * uBlock Origin itself is a *browser extension* and cannot run on Fire TV —
 * there is no extension-capable browser, and an extension only sees pages
 * inside a browser, not native streaming apps. What *is* portable is uBO's
 * intelligence: its filter lists. This pulls those lists and feeds the
 * host-blockable subset into the on-device DNS blocker.
 *
 * Cosmetic (`##`) and scriptlet (`##+js`) rules in these lists are ignored by
 * [BlocklistManager] because they require running inside the page — see the
 * SSAI notes in firetv/SSAI.md for why in-stream ads remain out of reach.
 *
 * No third-party dependencies — plain [HttpURLConnection]. Network work is
 * blocking; always call [updateNow] off the main thread.
 */
object FilterListUpdater {
    private const val TAG = "FilterListUpdater"

    /** Sub-directory of `filesDir` where downloaded lists are cached. */
    const val CACHE_DIR = "lists_cache"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_BYTES = 25 * 1024 * 1024            // 25 MB cap per list
    private const val MAX_REDIRECTS = 5
    private const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000 // once per day

    data class Source(val name: String, val url: String)

    /**
     * Default remote lists. Only host-blockable rules are extracted at the DNS
     * layer; everything else is ignored. uBO's own lists are first so they take
     * precedence in spirit, followed by the broader EasyList ecosystem and a
     * DNS-optimised list from AdGuard.
     */
    val SOURCES = listOf(
        Source("ubo-badware",     "https://ublockorigin.github.io/uAssets/filters/badware.txt"),
        Source("ubo-privacy",     "https://ublockorigin.github.io/uAssets/filters/privacy.txt"),
        Source("ubo-quick-fixes", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt"),
        Source("easylist",        "https://easylist.to/easylist/easylist.txt"),
        Source("easyprivacy",     "https://easylist.to/easylist/easyprivacy.txt"),
        Source("adguard-dns",     "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"),
    )

    data class Result(val updated: Int, val failed: Int, val domains: Int)

    /** True if the cache is older than [UPDATE_INTERVAL_MS] (or never fetched). */
    fun shouldUpdate(context: Context): Boolean =
        System.currentTimeMillis() - Settings.getLastListUpdate(context) > UPDATE_INTERVAL_MS

    /**
     * Download every source, cache successes to disk, then rebuild the
     * in-memory blocklist. Existing cached lists are kept if a fetch fails, so
     * a flaky network never leaves the blocker empty. Blocking — run on IO.
     */
    fun updateNow(context: Context): Result {
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        var ok = 0
        var failed = 0

        for (src in SOURCES) {
            try {
                val body = download(src.url)
                if (!body.isNullOrEmpty()) {
                    // Write to a temp file then rename so a partial write never
                    // replaces a good cached copy.
                    val tmp = File(dir, "${src.name}.txt.tmp")
                    tmp.writeText(body)
                    if (tmp.renameTo(File(dir, "${src.name}.txt"))) ok++ else { tmp.delete(); failed++ }
                } else {
                    failed++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed ${src.name}: ${e.message}")
                failed++
            }
        }

        if (ok > 0) {
            Settings.setLastListUpdate(context, System.currentTimeMillis())
            BlocklistManager.reload(context)
        }
        Log.i(TAG, "Update complete: $ok ok, $failed failed, ${BlocklistManager.blockedCount()} domains")
        return Result(ok, failed, BlocklistManager.blockedCount())
    }

    private fun download(urlStr: String): String? {
        var url = URL(urlStr)
        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false   // handle manually so http→https works
                setRequestProperty("User-Agent", "StreamAdBlock-FireTV")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    url = URL(url, loc)
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "$urlStr -> HTTP $code")
                    return null
                }
                conn.inputStream.bufferedReader().use { reader ->
                    val sb = StringBuilder()
                    val cbuf = CharArray(8192)
                    var total = 0
                    while (true) {
                        val n = reader.read(cbuf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            Log.w(TAG, "$urlStr exceeds size cap")
                            return null
                        }
                        sb.append(cbuf, 0, n)
                    }
                    return sb.toString()
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }
}
