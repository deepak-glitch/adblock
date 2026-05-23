package com.streamadblock.firetv

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads ad/tracker domain lists from the app's assets folder.
 * Supports hosts-file, plain-domain, and adblock (||domain^) formats.
 *
 * Lookup is O(1) via HashSet plus parent-domain walk for subdomains.
 *
 * Lists are bundled in `app/src/main/assets/lists/` and mirror the
 * structure in the root project's `lists/` directory.
 */
object BlocklistManager {
    private const val TAG = "BlocklistManager"

    private val blockSet = HashSet<String>(8192)
    private val allowSet = HashSet<String>(64)
    @Volatile private var loaded = false

    @Synchronized
    fun loadAll(context: Context) {
        if (loaded) return
        val started = System.currentTimeMillis()
        blockSet.clear()
        allowSet.clear()

        val assets = context.assets

        // Allowlist FIRST so we can skip its entries in the block set.
        try {
            assets.open("lists/core/allowlist.txt").use { ingest(it, allowSet) }
            Log.i(TAG, "Loaded ${allowSet.size} allowlist domains")
        } catch (e: Exception) {
            Log.w(TAG, "No allowlist found: ${e.message}")
        }

        // Walk every subdirectory under lists/
        val dirs = try { assets.list("lists") ?: emptyArray() } catch (e: Exception) { emptyArray() }
        for (sub in dirs) {
            val subPath = "lists/$sub"
            val files = try { assets.list(subPath) ?: emptyArray() } catch (e: Exception) { emptyArray() }
            for (f in files) {
                if (!f.endsWith(".txt") || f == "allowlist.txt") continue
                val path = "$subPath/$f"
                try {
                    assets.open(path).use { ingest(it, blockSet, allowSet) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load $path: ${e.message}")
                }
            }
        }

        loaded = true
        val dur = System.currentTimeMillis() - started
        Log.i(TAG, "Loaded ${blockSet.size} blocked, ${allowSet.size} allowed domains in ${dur}ms")
    }

    private fun ingest(stream: java.io.InputStream, target: HashSet<String>, exclude: HashSet<String>? = null) {
        BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.lineSequence().forEach { raw ->
                parseLine(raw)?.let { d ->
                    if (exclude == null || !exclude.contains(d)) target.add(d)
                }
            }
        }
    }

    private val ipPattern = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$|^::1$""")
    private val domainPattern = Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$""")

    private fun parseLine(raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return null
        if (line.startsWith("!") || line.startsWith("@@")) return null

        // Adblock: ||domain.com^
        if (line.startsWith("||")) {
            val d = line.substring(2).substringBefore('^').substringBefore('/').lowercase()
            return if (domainPattern.matches(d)) d else null
        }

        val parts = line.split(Regex("\\s+"))
        if (parts.size >= 2 && ipPattern.matches(parts[0])) {
            val d = parts[1].lowercase()
            if (d == "localhost" || d == "localhost.localdomain") return null
            return if (domainPattern.matches(d)) d else null
        }

        if (parts.size == 1) {
            val d = parts[0].lowercase()
            return if (domainPattern.matches(d)) d else null
        }
        return null
    }

    /**
     * Check if a domain should be blocked.
     * Walks parent labels: a.b.ads.doubleclick.net → ads.doubleclick.net (HIT).
     * Allowlist always wins.
     */
    fun isBlocked(domain: String): Boolean {
        if (domain.isEmpty()) return false
        val d = domain.lowercase().trimEnd('.')

        if (allowSet.contains(d)) return false

        val labels = d.split('.')
        for (i in 0 until labels.size - 1) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (allowSet.contains(candidate)) return false
            if (blockSet.contains(candidate)) return true
        }
        return false
    }

    fun blockedCount() = blockSet.size
    fun allowedCount() = allowSet.size
    fun isLoaded() = loaded
}
