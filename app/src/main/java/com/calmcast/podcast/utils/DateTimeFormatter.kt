package com.calmcast.podcast.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateTimeFormatter {

    fun formatDuration(seconds: Long): String {
        if (seconds < 0) return "00:00"

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }

    fun formatDurationFromString(durationStr: String?): String {
        if (durationStr.isNullOrBlank()) return "Unknown"

        return try {
            // Try parsing as seconds first
            val seconds = durationStr.toLongOrNull()
            if (seconds != null) {
                formatDuration(seconds)
            } else {
                formatISO8601Duration(durationStr)
            }
        } catch (e: Exception) {
            durationStr
        }
    }

    fun parseDuration(durationStr: String?): Long? {
        if (durationStr.isNullOrBlank()) return null

        durationStr.toLongOrNull()?.let { return it }
        parseISO8601Duration(durationStr)?.let { return it }

        return parseTimeFormat(durationStr)
    }

    private fun parseTimeFormat(timeStr: String): Long? {
        return try {
            val parts = timeStr.split(":")
            when (parts.size) {
                2 -> {
                    val minutes = parts[0].toLongOrNull() ?: return null
                    val seconds = parts[1].toLongOrNull() ?: return null
                    minutes * 60 + seconds
                }
                3 -> {
                    val hours = parts[0].toLongOrNull() ?: return null
                    val minutes = parts[1].toLongOrNull() ?: return null
                    val seconds = parts[2].toLongOrNull() ?: return null
                    hours * 3600 + minutes * 60 + seconds
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseISO8601Duration(iso8601Duration: String): Long? {
        try {
            val pattern = """PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""".toRegex()
            val matchResult = pattern.find(iso8601Duration) ?: return null

            val hours = matchResult.groupValues[1].toLongOrNull() ?: 0
            val minutes = matchResult.groupValues[2].toLongOrNull() ?: 0
            val seconds = matchResult.groupValues[3].toDoubleOrNull()?.toLong() ?: 0

            return hours * 3600 + minutes * 60 + seconds
        } catch (_: Exception) {
            return null
        }
    }

    private fun formatISO8601Duration(iso8601Duration: String): String {
        val totalSeconds = parseISO8601Duration(iso8601Duration) ?: return iso8601Duration
        return formatDuration(totalSeconds)
    }

    fun formatPublishDate(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return "Unknown date"

        return try {
            val date = parseDate(dateStr)
            val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
            sdf.format(date)
        } catch (_: Exception) {
            dateStr
        }
    }

    fun parseDateToMillis(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            parseDate(dateStr).time
        } catch (_: Exception) {
            dateStr.toLongOrNull()?.let { it * 1000 }
        }
    }

    fun formatDateWithTime(date: Date): String {
        val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        return sdf.format(date)
    }

    fun formatDateWithTimeString(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return "Unknown date"

        return try {
            val date = parseDate(dateStr)
            formatDateWithTime(date)
        } catch (e: Exception) {
            dateStr
        }
    }

    fun formatRelativeTime(timestampMs: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = now - timestampMs
        val diffMinutes = diffMs / 60_000
        val diffHours = diffMinutes / 60
        val diffDays = diffHours / 24

        return when {
            diffMinutes < 1 -> "Just now"
            diffMinutes < 60 -> "${diffMinutes}m ago"
            diffHours < 24 -> "${diffHours}h ago"
            diffDays < 7 -> "${diffDays}d ago"
            else -> {
                val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
                sdf.format(Date(timestampMs))
            }
        }
    }

    fun formatPlaybackTime(currentPosition: Long, totalDuration: Long): String {
        val currentSeconds = currentPosition / 1000
        val totalSeconds = totalDuration / 1000

        return "${formatDuration(currentSeconds)} / ${formatDuration(totalSeconds)}"
    }

    fun formatRemainingTime(currentPosition: Long, totalDuration: Long): String {
        val remainingMs = totalDuration - currentPosition
        val remainingSeconds = remainingMs / 1000

        return "-${formatDuration(remainingSeconds)}"
    }

    private fun parseDate(dateStr: String): Date {
        try {
            val timestamp = dateStr.toLong()
            if (timestamp in 946684800..4102444800L) {
                return Date(timestamp * 1000) // Convert seconds to milliseconds
            }
        } catch (_: NumberFormatException) {
            // Not a Unix timestamp, continue to try other formats
        }

        var lastException: Exception? = null

        for (sdf in dateFormatsCache.get()!!) {
            try {
                return synchronized(sdf) { sdf.parse(dateStr) } ?: Date()
            } catch (e: Exception) {
                lastException = e
                continue
            }
        }

        throw lastException ?: Exception("Unable to parse date: $dateStr")
    }

    // RFC 2822 first (the overwhelming majority of RSS feeds), then ISO variants.
    // Locale.US is required: month/day names in RSS are English by spec, so a
    // non-English device locale would fail every parse and zero out all dates.
    // Cached per thread — SimpleDateFormat isn't thread-safe, and re-allocating
    // ten of them per episode was a measurable cost during feed refresh.
    private val dateFormatsCache = object : ThreadLocal<List<SimpleDateFormat>>() {
        override fun initialValue(): List<SimpleDateFormat> = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",         // RFC 2822 (common in RSS)
            "dd MMM yyyy HH:mm:ss Z",              // Alternative RFC format
            "yyyy-MM-dd'T'HH:mm:ss'Z'",            // ISO 8601 (UTC)
            "yyyy-MM-dd'T'HH:mm:ssX",              // ISO 8601 with timezone
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",        // ISO 8601 with milliseconds
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",          // ISO 8601 with milliseconds and timezone
            "yyyy-MM-dd HH:mm:ss",                 // Simple format
            "yyyy-MM-dd",                          // Date only
            "MMM dd, yyyy",                        // Short format
            "MMMM dd, yyyy"                        // Long format
        ).map { SimpleDateFormat(it, Locale.US).apply { isLenient = false } }
    }
}