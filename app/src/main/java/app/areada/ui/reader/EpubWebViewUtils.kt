package app.areada.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.areada.R
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
internal fun restoreWebViewScroll(
    webView: WebView,
    fraction: Float,
) {
    if (fraction <= 0f) {
        return
    }

    val restore = {
        val fullHeight = (webView.contentHeight * webView.scale).toInt()
        val maxScroll = (fullHeight - webView.height).coerceAtLeast(0)
        if (maxScroll > 0) {
            scrollWebViewToProgress(webView, fraction)
        }
    }

    webView.post(restore)
    webView.postDelayed(restore, 150L)
}

@Suppress("DEPRECATION")
internal fun scrollWebViewToProgress(
    webView: WebView,
    progress: Float,
) {
    val fullHeight = (webView.contentHeight * webView.scale).toInt()
    val maxScroll = (fullHeight - webView.height).coerceAtLeast(0)
    val targetY = (maxScroll * progress.coerceIn(0f, 1f)).roundToInt()
    webView.scrollTo(webView.scrollX, targetY)
}

internal fun applyEpubScrollRequest(
    webView: WebView,
    progress: Float,
) {
    val scroll = {
        scrollWebViewToProgress(webView, progress)
    }
    scroll()
    webView.post(scroll)
    webView.postDelayed(scroll, 80L)
}

@Suppress("DEPRECATION")
internal fun epubWebViewScrollState(webView: WebView): Pair<Float, Boolean> {
    val fullHeight = webView.contentHeight * webView.scale
    val maxScroll = (fullHeight - webView.height).coerceAtLeast(0f)
    if (maxScroll <= 1f) {
        return 0f to false
    }
    return (webView.scrollY / maxScroll).coerceIn(0f, 1f) to true
}

@Suppress("DEPRECATION")
internal fun resetWebViewZoom(webView: WebView) {
    val currentScale = webView.scale.takeIf { it > 0f } ?: 1f
    val zoomFactor = (1f / currentScale).coerceIn(0.2f, 5f)
    if (abs(currentScale - 1f) > 0.02f) {
        webView.zoomBy(zoomFactor)
    }
}

@Suppress("DEPRECATION")
internal fun scrollByWebView(webView: WebView, deltaX: Int, deltaY: Int) {
    webView.scrollBy(deltaX, deltaY)
}

internal class NoteBridge(
    private val onNoteOpen: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun open(note: String?) {
        val cleanNote = note?.trim().orEmpty()
        if (cleanNote.isBlank()) {
            return
        }

        mainHandler.post {
            onNoteOpen(cleanNote)
        }
    }
}

internal fun injectNoteHandler(webView: WebView) {
    webView.evaluateJavascript(
        """
        (function() {
          if (window.__areadaNoteHandlerInstalled) return;
          window.__areadaNoteHandlerInstalled = true;

          function findAnchor(start) {
            var node = start;
            while (node && node.tagName !== 'A') {
              node = node.parentElement;
            }
            return node;
          }

          function noteFor(targetId, fallback) {
            if (!targetId) return fallback || '';
            var node = document.getElementById(targetId) ||
              document.querySelector('[name="' + targetId.replace(/"/g, '\\"') + '"]');
            if (!node) return fallback || '';
            return (node.innerText || node.textContent || fallback || '').trim();
          }

          document.addEventListener('click', function(event) {
            var anchor = findAnchor(event.target);
            if (!anchor) return;

            var href = anchor.getAttribute('href') || '';
            var hash = '';
            try {
              hash = new URL(href, window.location.href).hash || '';
            } catch (error) {
              var hashIndex = href.indexOf('#');
              if (hashIndex >= 0) hash = href.substring(hashIndex);
            }
            if (!hash || hash.length < 2) return;

            var targetId = decodeURIComponent(hash.substring(1));
            var fallback = (anchor.getAttribute('title') || anchor.innerText || anchor.textContent || '').trim();
            var note = noteFor(targetId, fallback);
            if (note && window.AreadaNote) {
              event.preventDefault();
              window.AreadaNote.open(note);
            }
          }, true);
        })();
        """.trimIndent(),
        null,
    )
}

internal fun openChapterNote(
    webView: WebView,
    fragment: String,
    onNoteOpen: (String) -> Unit,
) {
    val quotedFragment = JSONObject.quote(fragment)
    val script = """
        (function() {
          var id = $quotedFragment;
          var node = document.getElementById(id) || document.querySelector('[name="' + id.replace(/"/g, '\\"') + '"]');
          return node ? node.innerText : '';
        })();
    """.trimIndent()

    webView.evaluateJavascript(script) { result ->
        val note = decodeJavascriptString(result).trim()
        if (note.isNotBlank()) {
            onNoteOpen(note)
        }
    }
}

internal fun decodeJavascriptString(value: String?): String {
    if (value.isNullOrBlank() || value == "null") {
        return ""
    }
    return runCatching {
        JSONArray("[$value]").optString(0)
    }.getOrDefault("")
}

internal fun installEpubTtsTextIndex(
    webView: WebView,
    highlightColor: String,
    onReadableText: (String) -> Unit,
) {
    val quotedColor = JSONObject.quote(highlightColor)
    webView.evaluateJavascript(
        """
            (function() {
              var blockTags = {
                ADDRESS: true, ARTICLE: true, ASIDE: true, BLOCKQUOTE: true,
                DD: true, DIV: true, DL: true, DT: true, FIGCAPTION: true,
                FIGURE: true, FOOTER: true, H1: true, H2: true, H3: true,
                H4: true, H5: true, H6: true, HEADER: true, HR: true,
                LI: true, MAIN: true, NAV: true, OL: true, P: true,
                PRE: true, SECTION: true, TABLE: true, TD: true, TH: true,
                TR: true, UL: true
              };

              function isExcluded(element) {
                var current = element;
                while (current && current !== document.body) {
                  var tag = current.tagName;
                  if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'SVG') {
                    return true;
                  }
                  if (current.getAttribute && (
                      current.getAttribute('aria-hidden') === 'true' ||
                      current.hasAttribute('data-areada-ignore-tts')
                  )) {
                    return true;
                  }
                  current = current.parentElement;
                }
                if (element && window.getComputedStyle) {
                  var style = window.getComputedStyle(element);
                  return style.display === 'none' || style.visibility === 'hidden';
                }
                return false;
              }

              function blockAncestor(element) {
                var current = element;
                while (current && current !== document.body) {
                  if (blockTags[current.tagName]) return current;
                  current = current.parentElement;
                }
                return document.body;
              }

              window.__areadaBuildTtsIndex = function() {
                if (window.__areadaTtsIndex) return window.__areadaTtsIndex;

                var output = [];
                var points = [];
                var pendingSpace = false;
                var lastBlock = null;
                var walker = document.createTreeWalker(
                  document.body,
                  NodeFilter.SHOW_TEXT
                );
                var node;
                while ((node = walker.nextNode())) {
                  var parent = node.parentElement;
                  if (!parent || isExcluded(parent)) continue;

                  var block = blockAncestor(parent);
                  if (output.length && block !== lastBlock) pendingSpace = true;

                  var raw = node.nodeValue || '';
                  for (var i = 0; i < raw.length; i++) {
                    var character = raw.charAt(i);
                    if (/\s/.test(character)) {
                      pendingSpace = true;
                      continue;
                    }
                    if (pendingSpace && output.length) {
                      output.push(' ');
                      points.push({ node: node, offset: i });
                    }
                    output.push(character);
                    points.push({ node: node, offset: i });
                    pendingSpace = false;
                  }
                  lastBlock = block;
                }

                window.__areadaTtsIndex = {
                  text: output.join(''),
                  points: points
                };
                return window.__areadaTtsIndex;
              };

              window.__areadaClearTtsHighlight = function() {
                if (window.CSS && CSS.highlights) {
                  CSS.highlights.delete('areada-tts');
                }
                var selection = window.getSelection && window.getSelection();
                if (selection) selection.removeAllRanges();
              };

              window.__areadaHighlightTtsRange = function(start, end) {
                var index = window.__areadaBuildTtsIndex();
                window.__areadaClearTtsHighlight();
                if (!index.points.length || start < 0 || end <= start || end > index.points.length) {
                  return false;
                }

                var first = index.points[start];
                var last = index.points[end - 1];
                var range = document.createRange();
                range.setStart(first.node, first.offset);
                range.setEnd(last.node, last.offset + 1);

                if (window.CSS && CSS.highlights && window.Highlight) {
                  CSS.highlights.set('areada-tts', new Highlight(range));
                } else {
                  var selection = window.getSelection();
                  selection.removeAllRanges();
                  selection.addRange(range);
                }

                var rect = range.getBoundingClientRect();
                var upperGuard = Math.max(92, window.innerHeight * 0.18);
                var lowerGuard = window.innerHeight - Math.max(132, window.innerHeight * 0.22);
                if (rect.top < upperGuard || rect.bottom > lowerGuard) {
                  window.scrollTo({
                    top: Math.max(0, window.scrollY + rect.top - window.innerHeight * 0.34),
                    behavior: 'smooth'
                  });
                }
                return true;
              };

              var style = document.getElementById('areada-tts-highlight-style');
              if (!style) {
                style = document.createElement('style');
                style.id = 'areada-tts-highlight-style';
                document.head.appendChild(style);
              }
              var color = $quotedColor;
              style.textContent =
                '::highlight(areada-tts) { background-color: ' + color + '; color: inherit; }' +
                '::selection { background-color: ' + color + '; color: inherit; }';

              return window.__areadaBuildTtsIndex().text;
            })();
        """.trimIndent(),
    ) { result ->
        onReadableText(decodeJavascriptString(result))
    }
}

internal fun applyEpubTtsHighlight(
    webView: WebView,
    start: Int,
    end: Int,
) {
    val highlightKey = "$start:$end"
    if (webView.getTag(R.id.tag_epub_tts_highlight) == highlightKey) {
        return
    }
    webView.setTag(R.id.tag_epub_tts_highlight, highlightKey)

    val script = if (start >= 0 && end > start) {
        """
            (function() {
              return window.__areadaHighlightTtsRange
                ? window.__areadaHighlightTtsRange($start, $end)
                : false;
            })();
        """.trimIndent()
    } else {
        """
            (function() {
              if (window.__areadaClearTtsHighlight) {
                window.__areadaClearTtsHighlight();
              }
            })();
        """.trimIndent()
    }
    webView.evaluateJavascript(script, null)
}
