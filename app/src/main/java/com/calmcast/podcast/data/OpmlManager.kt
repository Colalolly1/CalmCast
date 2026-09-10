package com.calmcast.podcast.data

import android.content.Context
import android.net.Uri
import java.io.OutputStream

object OpmlManager {

    fun exportToOpml(podcasts: List<Podcast>, outputStream: OutputStream) {
        outputStream.bufferedWriter().use { writer ->
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            writer.write("<opml version=\"2.0\">\n")
            writer.write("  <head>\n")
            writer.write("    <title>CalmCast Subscriptions</title>\n")
            writer.write("  </head>\n")
            writer.write("  <body>\n")
            for (podcast in podcasts) {
                val title = escapeXml(podcast.title)
                val feedUrl = escapeXml(podcast.feedUrl ?: "")
                val xmlUrl = if (podcast.feedUrl != null) " xmlUrl=\"$feedUrl\"" else ""
                writer.write("    <outline text=\"$title\" type=\"rss\"$xmlUrl/>\n")
            }
            writer.write("  </body>\n")
            writer.write("</opml>\n")
        }
    }

    fun parseOpml(context: Context, uri: Uri): List<OpmlEntry> {
        val entries = mutableListOf<OpmlEntry>()
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val content = reader.readText()
            val outlinePattern = """<outline[^>]*/>""".toRegex()
            for (match in outlinePattern.findAll(content)) {
                val outline = match.value
                val xmlUrl = extractAttribute(outline, "xmlUrl")
                val text = extractAttribute(outline, "text") ?: extractAttribute(outline, "title")
                if (xmlUrl != null) {
                    entries.add(OpmlEntry(title = text ?: "", feedUrl = xmlUrl))
                }
            }
        }
        return entries
    }

    private fun extractAttribute(tag: String, attrName: String): String? {
        val pattern = """$attrName\s*=\s*"([^"]*)"""".toRegex()
        return pattern.find(tag)?.groupValues?.get(1)?.let { unescapeXml(it) }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun unescapeXml(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    data class OpmlEntry(val title: String, val feedUrl: String)
}
