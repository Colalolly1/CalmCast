package com.calmcast.podcast.ui.common

import android.graphics.Color
import android.text.Html
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    maxLines: Int? = null,
    forceBlackText: Boolean = false,
    onTruncated: (Boolean) -> Unit = {}
) {
    // Parsing HTML is expensive; do it once per html value, not on every recomposition
    // (the update block re-runs each time the enclosing screen recomposes).
    val spanned = remember(html, forceBlackText) {
        val parsed = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        if (forceBlackText && parsed is Spannable) {
            // Remove color spans and URL spans to force black text
            val spans = parsed.getSpans(0, parsed.length, Any::class.java)
            for (span in spans) {
                if (span is ForegroundColorSpan || span is URLSpan) {
                    parsed.removeSpan(span)
                }
            }
        }
        parsed
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextColor(Color.BLACK)
                if (maxLines != null) {
                    setMaxLines(maxLines)
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
                }
            }
        },
        update = { textView ->
            if (textView.text != spanned) {
                textView.text = spanned
            }
            if (maxLines != null) {
                textView.post {
                    val layout = textView.layout
                    if (layout != null) {
                        val lineCount = layout.lineCount
                        val lastLine = if (lineCount > 0) minOf(maxLines - 1, lineCount - 1) else 0
                        val isEllipsized =
                            (lineCount > maxLines) ||
                            (lineCount >= maxLines && layout.getEllipsisCount(lastLine) > 0)
                        onTruncated(isEllipsized)
                    } else {
                        onTruncated(false)
                    }
                }
            }
        }
    )
}
