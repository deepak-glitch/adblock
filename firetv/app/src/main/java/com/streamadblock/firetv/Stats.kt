package com.streamadblock.firetv

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe in-memory counters + periodic SharedPreferences persistence.
 */
object Stats {
    private const val PREFS = "stats_prefs"
    private const val K_TOTAL = "total_blocked"
    private const val K_TOTAL_QUERIES = "total_queries"
    private const val K_TODAY = "today_blocked"
    private const val K_TODAY_DATE = "today_date"

    val totalBlocked = AtomicLong(0)
    val totalQueries = AtomicLong(0)
    val todayBlocked = AtomicLong(0)
    @Volatile private var todayDate: String = currentDate()

    private fun currentDate(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    fun load(context: Context) {
        val p = prefs(context)
        totalBlocked.set(p.getLong(K_TOTAL, 0))
        totalQueries.set(p.getLong(K_TOTAL_QUERIES, 0))
        val savedDate = p.getString(K_TODAY_DATE, currentDate()) ?: currentDate()
        if (savedDate == currentDate()) {
            todayBlocked.set(p.getLong(K_TODAY, 0))
            todayDate = savedDate
        } else {
            todayBlocked.set(0)
            todayDate = currentDate()
        }
    }

    fun save(context: Context) {
        prefs(context).edit {
            putLong(K_TOTAL, totalBlocked.get())
            putLong(K_TOTAL_QUERIES, totalQueries.get())
            putLong(K_TODAY, todayBlocked.get())
            putString(K_TODAY_DATE, todayDate)
        }
    }

    fun recordBlocked() {
        rollDay()
        totalQueries.incrementAndGet()
        totalBlocked.incrementAndGet()
        todayBlocked.incrementAndGet()
    }

    fun recordAllowed() {
        rollDay()
        totalQueries.incrementAndGet()
    }

    private fun rollDay() {
        val today = currentDate()
        if (today != todayDate) {
            todayDate = today
            todayBlocked.set(0)
        }
    }

    fun reset(context: Context) {
        totalBlocked.set(0)
        totalQueries.set(0)
        todayBlocked.set(0)
        todayDate = currentDate()
        save(context)
    }

    fun blockRate(): Int {
        val q = totalQueries.get()
        return if (q == 0L) 0 else ((totalBlocked.get() * 100) / q).toInt()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
