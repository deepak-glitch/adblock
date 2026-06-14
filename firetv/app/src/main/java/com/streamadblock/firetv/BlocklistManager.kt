package com.streamadblock.firetv

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Loads ad/tracker domain lists from bundled assets AND downloaded filter
 * lists (uBO / EasyList / EasyPrivacy / AdGuard-DNS, see [FilterListUpdater]).
 *
 * Supported input formats:
 *   - hosts file:        `0.0.0.0 ads.example.com`
 *   - plain domain:      `ads.example.com`
 *   - Adblock network:   `||ads.example.com^`  (with `$options`)
 *   - Adblock exception: `@@||good.example.com^`  → allowlisted
 *
 * Because this runs at the DNS layer it can only act on *host names*. Rules
 * that target a URL path, inject CSS/JS (`##`, `##+js`), or merely modify a
 * request (`$csp`, `$redirect`, `$removeparam`, …) cannot be enforced here
 * and are deliberately ignored — see [parseRule].
 *
 * Lookup is O(labels) via HashSet plus a parent-domain walk for subdomains.
 * Block/allow sets are swapped atomically so [reload] is safe to call while
 * the VPN packet loop is reading via [isBlocked].
 */
object BlocklistManager {
    private const val TAG = "BlocklistManager"

    @Volatile private var blockSet: Set<String> = emptySet()
    @Volatile private var allowSet: Set<String> = emptySet()
    @Volatile private var loaded = false

    /**
     * Build the block/allow sets from bundled assets + the downloaded cache.
     * Pass [force] = true to rebuild after a filter-list update.
     */
    @Synchronized
    fun loadAll(context: Context, force: Boolean = false) {
        if (loaded && !force) return
        val started = System.currentTimeMillis()

        val block = HashSet<String>(1 shl 16)
        val allow = HashSet<String>(256)
        val assets = context.assets

        // Allowlist FIRST — these domains must never be blocked.
        try {
            assets.open("lists/core/allowlist.txt").use { ingestAllow(it, allow) }
        } catch (e: Exception) {
            Log.w(TAG, "No allowlist found: ${e.message}")
        }

        // Bundled lists shipped inside the APK (assets/lists/**).
        val dirs = try { assets.list("lists") ?: emptyArray() } catch (e: Exception) { emptyArray() }
        for (sub in dirs) {
            val subPath = "lists/$sub"
            val files = try { assets.list(subPath) ?: emptyArray() } catch (e: Exception) { emptyArray() }
            for (f in files) {
                if (!f.endsWith(".txt") || f == "allowlist.txt") continue
                try {
                    assets.open("$subPath/$f").use { ingest(it, block, allow) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load $subPath/$f: ${e.message}")
                }
            }
        }

        // Downloaded filter lists (uBO/EasyList/…) cached on disk.
        val cacheDir = File(context.filesDir, FilterListUpdater.CACHE_DIR)
        if (cacheDir.isDirectory) {
            cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.forEach { f ->
                try {
                    f.inputStream().use { ingest(it, block, allow) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load cached ${f.name}: ${e.message}")
                }
            }
        }

        // Atomic swap — readers see the old or the new set, never a partial one.
        blockSet = block
        allowSet = allow
        loaded = true

        val dur = System.currentTimeMillis() - started
        Log.i(TAG, "Loaded ${block.size} blocked, ${allow.size} allowed domains in ${dur}ms")
    }

    /** Force a full rebuild (e.g. after [FilterListUpdater] downloads new lists). */
    fun reload(context: Context) = loadAll(context, force = true)

    // ── Parsing ───────────────────────────────────────────────────────────

    private data class Rule(val domain: String, val allow: Boolean)

    private val ipPattern = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$|^::1$""")
    private val domainPattern =
        Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$""")

    /**
     * Filter options that mean "modify / redirect the request" rather than
     * "block the host". Treating these as a full-domain block at the DNS layer
     * would wrongly sinkhole real domains, so any rule carrying one is skipped.
     */
    private val nonBlockingOptions = listOf(
        "csp", "redirect", "removeparam", "replace", "cookie", "permissions",
        "empty", "mp4", "badfilter", "elemhide", "generichide", "specifichide",
        "inline-script", "inline-font"
    )

    private fun ingest(stream: InputStream, block: HashSet<String>, allow: HashSet<String>) {
        BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.lineSequence().forEach { raw ->
                val rule = parseRule(raw) ?: return@forEach
                if (rule.allow) allow.add(rule.domain) else block.add(rule.domain)
            }
        }
    }

    /** Allowlist.txt entries are plain domains but must always count as allow. */
    private fun ingestAllow(stream: InputStream, allow: HashSet<String>) {
        BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.lineSequence().forEach { raw ->
                parseRule(raw)?.let { allow.add(it.domain) }
            }
        }
    }

    private fun parseRule(raw: String): Rule? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("!"))
            return null
        // Cosmetic / scriptlet filters cannot apply at the DNS layer.
        if (line.contains("##") || line.contains("#@#") ||
            line.contains("#?#") || line.contains("#\$#") || line.contains("#%#"))
            return null

        val allow = line.startsWith("@@")
        if (allow) line = line.substring(2)

        // Adblock network rule:  ||host^[$options]
        if (line.startsWith("||")) {
            var body = line.substring(2)
            val dollar = body.indexOf('$')
            val options = if (dollar >= 0) body.substring(dollar + 1) else ""
            if (dollar >= 0) body = body.substring(0, dollar)
            // A path means it targets a URL, not a host — DNS can't enforce it.
            if (body.contains('/')) return null
            if (options.isNotEmpty() && nonBlockingOptions.any { options.contains(it) }) return null
            val d = body.substringBefore('^').removePrefix("*.").lowercase()
            return if (domainPattern.matches(d)) Rule(d, allow) else null
        }

        // hosts-file format:  <ip> domain   (e.g. 0.0.0.0 ads.example.com)
        val parts = line.split(Regex("\\s+"))
        if (parts.size >= 2 && ipPattern.matches(parts[0])) {
            val d = parts[1].lowercase()
            if (d == "localhost" || d == "localhost.localdomain") return null
            return if (domainPattern.matches(d)) Rule(d, allow) else null
        }

        // Bare domain on its own line.
        if (parts.size == 1) {
            val d = parts[0].lowercase()
            return if (domainPattern.matches(d)) Rule(d, allow) else null
        }
        return null
    }

    // ── Matching ──────────────────────────────────────────────────────────

    /**
     * Check if a domain should be blocked. Walks parent labels so that
     * `a.b.ads.doubleclick.net` matches a rule for `ads.doubleclick.net`.
     * The allowlist always wins.
     */
    fun isBlocked(domain: String): Boolean {
        if (domain.isEmpty()) return false
        val block = blockSet
        val allow = allowSet
        val d = domain.lowercase().trimEnd('.')

        if (allow.contains(d)) return false

        val labels = d.split('.')
        for (i in 0 until labels.size - 1) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (allow.contains(candidate)) return false
            if (block.contains(candidate)) return true
        }
        return false
    }

    fun blockedCount() = blockSet.size
    fun allowedCount() = allowSet.size
    fun isLoaded() = loaded
}
