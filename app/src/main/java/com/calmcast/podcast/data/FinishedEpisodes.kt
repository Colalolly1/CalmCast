package com.calmcast.podcast.data

import android.content.Context

/**
 * Episodes listened to the end. A saved position alone can't say that when the
 * feed gives no length, so a finished episode would resume at its very end.
 */
object FinishedEpisodes {
    private const val PREFS = "finished_episodes"
    private const val KEY = "ids"
    private const val KEEP = 500

    fun mark(context: Context, episodeId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        ids += episodeId
        // Old marks don't matter much; keep the set small.
        val kept = if (ids.size > KEEP) ids.toList().takeLast(KEEP).toSet() else ids
        prefs.edit().putStringSet(KEY, kept).apply()
    }

    fun has(context: Context, episodeId: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.contains(episodeId) == true

    fun clear(context: Context, episodeId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        if (ids.remove(episodeId)) prefs.edit().putStringSet(KEY, ids).apply()
    }
}
