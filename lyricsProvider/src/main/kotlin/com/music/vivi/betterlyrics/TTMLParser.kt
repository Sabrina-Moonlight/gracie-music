/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Lectura de TTML adaptada del código de Metrolist (BetterLyricsProvider.kt):
 * límites de tamaño, lector XML propio, desfase global, agentes, voces de fondo,
 * espacios entre palabras y formato de tiempos. La salida sigue siendo la de la app.
 */
package com.music.vivi.betterlyrics

internal const val MAX_TTML_DEPTH = 64
internal const val MAX_TTML_NODES = 10_000
private const val MAX_TTML_CHARS = 512 * 1_024
private const val MAX_TTML_LINES = 5_000
private const val TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter"

object TTMLParser {

    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val words: List<ParsedWord>,
        val agent: String? = null,
        val isBackground: Boolean = false,
        val backgroundLines: List<ParsedLine> = emptyList()
    )

    data class ParsedWord(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasTrailingSpace: Boolean = true
    )

    private data class SpanInfo(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasTrailingSpace: Boolean
    )

    fun parseTTML(ttml: String): List<ParsedLine> {
        if (!isTtmlWithinLimits(ttml)) return emptyList()
        return try {
            val root = parseXml(ttml)
            var globalOffset = 0.0
            findChild(root, "head")?.let { head ->
                findChild(head, "metadata")?.let { meta ->
                    findChild(meta, "audio")?.let {
                        globalOffset = it.getAttribute("lyricOffset").toDoubleOrNull() ?: 0.0
                    }
                }
            }
            val raw = mutableListOf<ParsedLine>()
            findChild(root, "body")?.let { walk(it, raw, globalOffset) }
            attachBackground(raw.sortedBy { it.startTime })
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Una línea que es toda de fondo se pega como voz de fondo de la línea anterior. */
    private fun attachBackground(lines: List<ParsedLine>): List<ParsedLine> {
        val out = mutableListOf<ParsedLine>()
        for (line in lines) {
            if (line.isBackground && out.isNotEmpty()) {
                val prev = out.removeAt(out.lastIndex)
                out.add(prev.copy(backgroundLines = prev.backgroundLines + line))
            } else {
                out.add(line.copy(isBackground = false))
            }
        }
        return out
    }

    private fun walk(root: XmlElement, lines: MutableList<ParsedLine>, offset: Double) {
        val pending = ArrayDeque<Pair<XmlElement, String?>>()
        pending.addLast(root to null)
        while (pending.isNotEmpty() && lines.size < MAX_TTML_LINES) {
            val (current, inheritedAgent) = pending.removeLast()
            var agent = inheritedAgent
            when (current.localName) {
                "div" -> getAttr(current, "agent").let { if (it.isNotEmpty()) agent = it }
                "p" -> {
                    parseP(current, lines, offset, agent)
                    continue
                }
            }
            val children = current.children
            for (i in children.lastIndex downTo 0) pending.addLast(children[i] to agent)
        }
    }

    private fun parseP(p: XmlElement, lines: MutableList<ParsedLine>, offset: Double, divAgent: String?) {
        val begin = timingAttr(p, "begin").ifEmpty { findFirstSpanBegin(p) } ?: return
        val startTime = parseTime(begin) + offset
        val agent = getAttr(p, "agent").ifEmpty { divAgent }?.takeIf { it.isNotEmpty() }
        val isPBackground = getAttr(p, "role") == "x-bg"

        val spanInfos = mutableListOf<SpanInfo>()
        val backgroundLines = mutableListOf<ParsedLine>()

        for (child in p.children) {
            if (child.localName != "span") continue
            when (getAttr(child, "role")) {
                "x-bg" -> {
                    if (isPBackground) {
                        parseWordSpan(child, offset, spanInfos)
                    } else {
                        parseBackgroundSpan(child, startTime, offset)?.let { backgroundLines.add(it) }
                    }
                }
                "x-translation", "x-roman" -> {}
                else -> parseWordSpan(child, offset, spanInfos)
            }
        }

        val words = mergeSpansIntoWords(spanInfos)
        val lineText = if (words.isEmpty()) getDirectText(p).trim() else buildLineText(words)

        if (lineText.isNotEmpty()) {
            lines.add(ParsedLine(lineText, startTime, words, agent, isPBackground, backgroundLines))
        } else if (backgroundLines.isNotEmpty()) {
            // Sin texto principal: las voces de fondo pasan a ser líneas normales.
            lines.addAll(backgroundLines.map { it.copy(isBackground = false) })
        }
    }

    private fun parseWordSpan(span: XmlElement, offset: Double, out: MutableList<SpanInfo>) {
        val begin = timingAttr(span, "begin")
        val end = timingAttr(span, "end")
        if (begin.isEmpty() || end.isEmpty()) return
        val text = span.textContent
        val next = span.nextSibling
        val nextText = if (next is XmlText) next.value else ""
        val extraWhitespace =
            if (nextText.startsWith("\n") || nextText.startsWith("\r")) {
                if (nextText.trim().isEmpty()) "" else " "
            } else {
                nextText.takeWhile { it == ' ' || it == '\t' }
            }
        val fullText = text + extraWhitespace
        val hasTrailingSpace = fullText.isNotEmpty() && fullText.last().isWhitespace()
        val wordText = if (hasTrailingSpace && fullText.endsWith(' ')) fullText.dropLast(1) else fullText
        if (wordText.isBlank()) return
        out.add(SpanInfo(wordText, parseTime(begin) + offset, parseTime(end) + offset, hasTrailingSpace))
    }

    private fun parseBackgroundSpan(span: XmlElement, parentStart: Double, offset: Double): ParsedLine? {
        val begin = timingAttr(span, "begin")
        val start = if (begin.isNotEmpty()) parseTime(begin) + offset else parentStart

        val spanInfos = mutableListOf<SpanInfo>()
        var hasSpans = false
        for (child in span.children) {
            if (child.localName == "span") {
                hasSpans = true
                val role = getAttr(child, "role")
                if (role != "x-translation" && role != "x-roman") parseWordSpan(child, offset, spanInfos)
            }
        }
        if (!hasSpans) {
            val text = span.textContent.trim()
            return if (text.isEmpty()) null else ParsedLine(text, start, emptyList(), null, true)
        }
        val words = mergeSpansIntoWords(spanInfos)
        val text = if (words.isEmpty()) getDirectText(span).trim() else buildLineText(words)
        return if (text.isEmpty()) null else ParsedLine(text, start, words, null, true)
    }

    /** Cada sílaba queda como palabra propia (como Metrolist); el espacio final dice dónde acaba la palabra. */
    private fun mergeSpansIntoWords(spanInfos: List<SpanInfo>): List<ParsedWord> =
        spanInfos.mapNotNull {
            val text = it.text.trim()
            if (text.isEmpty()) null else ParsedWord(text, it.startTime, it.endTime, it.hasTrailingSpace)
        }

    private fun buildLineText(words: List<ParsedWord>): String =
        buildString {
            words.forEachIndexed { i, w ->
                append(w.text)
                if (w.hasTrailingSpace && !w.text.endsWith('-') && i < words.lastIndex) append(' ')
            }
        }.trim()

    private fun getDirectText(el: XmlElement): String {
        val sb = StringBuilder()
        for (child in el.childNodes) {
            if (child is XmlText) {
                sb.append(child.value)
            } else if (child is XmlElement && child.localName == "span") {
                val role = getAttr(child, "role")
                if (role != "x-bg" && role != "x-translation" && role != "x-roman") sb.append(child.textContent)
            }
        }
        return sb.toString()
    }

    private fun findChild(parent: XmlElement, name: String): XmlElement? =
        parent.children.firstOrNull { it.localName == name }

    private fun getAttr(el: XmlElement, localName: String): String {
        val itunes = el.getAttribute("itunes:$localName")
        if (itunes.isNotEmpty()) return itunes
        val itunesNs = el.getAttributeNS("http://music.apple.com/lyric-ttml-internal", localName)
        if (itunesNs.isNotEmpty()) return itunesNs
        val lrc = el.getAttribute("lrc:$localName")
        if (lrc.isNotEmpty()) return lrc
        val lrcNs = el.getAttributeNS("http://lrc.red/lyric-ttml-internal", localName)
        if (lrcNs.isNotEmpty()) return lrcNs
        val ttm = el.getAttribute("ttm:$localName")
        if (ttm.isNotEmpty()) return ttm
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        return el.getAttributeNS("http://www.w3.org/ns/ttml#metadata", localName)
    }

    private fun timingAttr(el: XmlElement, localName: String): String {
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        return el.getAttributeNS(TTML_PARAMETER_NS, localName)
    }

    private fun findFirstSpanBegin(p: XmlElement): String? {
        var best: String? = null
        var bestSeconds = Double.POSITIVE_INFINITY
        for (child in p.children) {
            if (child.localName == "span") {
                val b = timingAttr(child, "begin")
                if (b.isNotEmpty()) {
                    val s = parseTime(b)
                    if (s < bestSeconds) {
                        bestSeconds = s
                        best = b
                    }
                }
            }
        }
        return best
    }

    private fun parseTime(time: String): Double {
        val t = time.trim()
        val c1 = t.indexOf(':')
        if (c1 != -1) {
            val c2 = t.lastIndexOf(':')
            return if (c1 == c2) {
                (t.substring(0, c1).toIntOrNull() ?: 0) * 60.0 + (t.substring(c1 + 1).toDoubleOrNull() ?: 0.0)
            } else {
                (t.substring(0, c1).toIntOrNull() ?: 0) * 3600.0 + (t.substring(c1 + 1, c2).toIntOrNull() ?: 0) * 60.0 +
                    (t.substring(c2 + 1).toDoubleOrNull() ?: 0.0)
            }
        }
        if (t.endsWith("ms")) return (t.substring(0, t.length - 2).toDoubleOrNull() ?: 0.0) / 1000.0
        val s = if (t.endsWith("s") || t.endsWith("m") || t.endsWith("h")) t.substring(0, t.length - 1) else t
        val v = s.toDoubleOrNull() ?: 0.0
        return when {
            t.endsWith("m") -> v * 60.0
            t.endsWith("h") -> v * 3600.0
            else -> v
        }
    }

    /** Protege `%`, `:` y `|` del texto de una palabra: son los separadores del formato de salida. */
    private fun escapeWord(text: String): String =
        text.replace("%", "%25").replace(":", "%3A").replace("|", "%7C")

    fun toLRC(lines: List<ParsedLine>): String {
        return buildString {
            lines.forEach { line ->
                val timeMs = (line.startTime * 1000).toLong()
                val minutes = timeMs / 60000
                val seconds = (timeMs % 60000) / 1000
                val centiseconds = (timeMs % 1000) / 10
                
                // Add agent info if present
                val agentPrefix = if (!line.agent.isNullOrEmpty()) "{agent:${line.agent}}" else ""
                
                appendLine(String.format("[%02d:%02d.%02d]%s%s", minutes, seconds, centiseconds, agentPrefix, line.text))
                
                if (line.words.isNotEmpty()) {
                    val wordsData = line.words.joinToString("|") { word ->
                        "${escapeWord(word.text)}:${word.startTime}:${word.endTime}:${if (word.hasTrailingSpace) 1 else 0}"
                    }
                    appendLine("<$wordsData>")
                }
                
                // Add background vocals as separate lines
                line.backgroundLines.forEach { bgLine ->
                    val bgTimeMs = (bgLine.startTime * 1000).toLong()
                    val bgMinutes = bgTimeMs / 60000
                    val bgSeconds = (bgTimeMs % 60000) / 1000
                    val bgCentiseconds = (bgTimeMs % 1000) / 10
                    
                    appendLine(String.format("[%02d:%02d.%02d]{bg}%s", bgMinutes, bgSeconds, bgCentiseconds, bgLine.text))
                    
                    if (bgLine.words.isNotEmpty()) {
                        val bgWordsData = bgLine.words.joinToString("|") { word ->
                            "${escapeWord(word.text)}:${word.startTime}:${word.endTime}:${if (word.hasTrailingSpace) 1 else 0}"
                        }
                        appendLine("<$bgWordsData>")
                    }
                }
            }
        }
    }
}

internal fun isTtmlWithinLimits(text: String): Boolean {
    if (text.length > MAX_TTML_CHARS) return false
    var index = 0
    var depth = 0
    var nodes = 0
    while (index < text.length) {
        if (text[index] != '<') {
            index++
            continue
        }
        val marker = text.getOrNull(index + 1)
        var cursor = index + 1
        var quote: Char? = null
        while (cursor < text.length) {
            val char = text[cursor]
            if (quote == null && (char == '\'' || char == '"')) {
                quote = char
            } else if (char == quote) {
                quote = null
            }
            if (char == '>' && quote == null) break
            cursor++
        }
        if (cursor >= text.length) return false
        if (marker == '/') {
            if (--depth < 0) return false
        } else if (marker != '!' && marker != '?') {
            nodes++
            if (nodes > MAX_TTML_NODES) return false
            if (text
                    .substring(index, cursor)
                    .trimEnd()
                    .endsWith('/')
                    .not() && ++depth > MAX_TTML_DEPTH
            ) {
                return false
            }
        }
        index = cursor + 1
    }
    return depth == 0
}
