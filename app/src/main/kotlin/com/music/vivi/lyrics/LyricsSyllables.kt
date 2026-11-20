/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.lyrics

/**
 * Parte en sílabas las palabras de las letras que traen tiempo por palabra pero no por sílaba,
 * para que la pantalla de letras las ilumine sílaba por sílaba (como "se-ver", "walk-ing").
 *
 * - Solo inglés y español (se detecta por canción). Otros idiomas no se tocan.
 * - Solo palabras "enteras": si el proveedor ya partió la palabra en sílabas, no se toca.
 * - El tiempo de la palabra se reparte entre sus sílabas según el tamaño de cada una.
 */
object LyricsSyllables {
    /** Interruptor general (se conectará a ajustes en otra entrega). */
    @Volatile
    var enabled: Boolean = true

    private enum class Lang { EN, ES }

    fun apply(entries: List<LyricsEntry>): List<LyricsEntry> {
        if (!enabled) return entries
        return try {
            if (entries.none { !it.words.isNullOrEmpty() }) return entries
            val songLang = detectLanguage(entries) ?: return entries
            entries.map { entry ->
                val words = entry.words
                if (words.isNullOrEmpty()) {
                    entry
                } else {
                    val split = splitWords(words, songLang)
                    if (split === words) entry else entry.copy(words = split)
                }
            }
        } catch (e: Exception) {
            entries
        }
    }

    // ---------------------------------------------------------------------------------------
    // Idioma de la canción
    // ---------------------------------------------------------------------------------------

    private val EN_WORDS = setOf(
        "the", "you", "and", "to", "it", "in", "my", "that", "is", "of", "your", "be", "we", "but",
        "all", "for", "on", "this", "with", "just", "i", "i'm", "it's", "don't", "can't", "you're",
        "i'll", "know", "like", "what", "when", "oh", "yeah", "they", "she", "he", "was", "are",
    )
    private val ES_WORDS = setOf(
        "que", "el", "los", "las", "del", "una", "por", "para", "con", "pero", "como", "muy", "más",
        "qué", "está", "eres", "soy", "yo", "tú", "mi", "tu", "te", "lo", "se", "es", "en", "un",
        "y", "de", "cuando", "porque", "todo", "nada", "tengo", "quiero",
    )
    // Palabras de otros idiomas parecidos (francés, portugués, italiano, alemán): si ganan, no se toca nada
    private val OTHER_WORDS = setOf(
        "je", "est", "pas", "vous", "nous", "une", "les", "des", "du", "qui", "ça", "être", "mais",
        "dans", "avec", "sur", "não", "você", "eu", "meu", "minha", "uma", "um", "é", "il", "che",
        "non", "sono", "per", "di", "ti", "mio", "mia", "ich", "und", "nicht", "das", "ist", "ein",
        "mir", "dir", "wir", "sie", "der", "die",
    )

    private fun detectLanguage(entries: List<LyricsEntry>): Lang? {
        var en = 0
        var es = 0
        var other = 0
        for (entry in entries) {
            for (token in entry.text.lowercase().split(NON_WORD)) {
                if (token.isEmpty()) continue
                if (token in EN_WORDS) en++
                if (token in ES_WORDS) es++
                if (token in OTHER_WORDS) other++
            }
        }
        return when {
            en >= 3 && en >= es && en > other -> Lang.EN
            es >= 3 && es > en && es > other -> Lang.ES
            else -> null
        }
    }

    private val NON_WORD = Regex("[^\\p{L}'’]+")

    // ---------------------------------------------------------------------------------------
    // Partir las palabras de una línea
    // ---------------------------------------------------------------------------------------

    private fun splitWords(words: List<WordTimestamp>, songLang: Lang): List<WordTimestamp> {
        var changed = false
        val out = ArrayList<WordTimestamp>(words.size + 4)
        for ((i, word) in words.withIndex()) {
            val isLast = i == words.lastIndex
            val whole = (i == 0 || words[i - 1].hasTrailingSpace) && (isLast || word.hasTrailingSpace)
            val pieces = if (whole && !word.isLineTimed) splitOne(word, songLang) else null
            if (pieces == null) {
                out.add(word)
            } else {
                changed = true
                out.addAll(pieces)
            }
        }
        return if (changed) out else words
    }

    /** Devuelve las sílabas de la palabra con su tiempo, o null si no hay que partirla. */
    private fun splitOne(word: WordTimestamp, songLang: Lang): List<WordTimestamp>? {
        val text = word.text
        val duration = word.endTime - word.startTime
        if (text.length < 4 || duration <= 0.0) return null

        // Quitar signos de puntuación del principio y del final ("(love," -> "love")
        var start = 0
        while (start < text.length && !text[start].isLetter()) start++
        var end = text.length
        while (end > start && !text[end - 1].isLetter()) end--
        // "goin'" lleva el apóstrofe dentro de la palabra
        if (end - start >= 3 && end < text.length && (text[end] == '\'' || text[end] == '’') &&
            text.substring(end - 2, end).equals("in", ignoreCase = true)
        ) {
            end++
        }
        if (end - start < 4) return null
        val core = text.substring(start, end)
        if (!core.all { isAllowedChar(it) }) return null

        val lang = if (core.any { it in "áéíóúüñÁÉÍÓÚÜÑ" }) Lang.ES else songLang
        val cuts = syllableCuts(core, lang == Lang.ES)
        if (cuts.isEmpty()) return null

        // Trozos: el primero lleva la puntuación de delante, el último la de detrás
        val bounds = ArrayList<Int>()
        bounds.add(0)
        for (c in cuts) bounds.add(start + c)
        bounds.add(text.length)

        val weights = ArrayList<Int>()
        for (k in 0 until bounds.size - 1) {
            val from = maxOf(bounds[k], start)
            val to = minOf(bounds[k + 1], end)
            weights.add(maxOf(1, to - from))
        }
        val totalWeight = weights.sum().toDouble()

        // Palabra alargada ("rumbleeee"): cada sílaba de antes dura como mucho MAX_SECONDS_PER_LETTER
        // por letra y la última se lleva el resto, porque el cantante sostiene el final.
        // Es un tope suave: en palabras normales no cambia nada (reparto por tamaño).
        val result = ArrayList<WordTimestamp>(weights.size)
        var t = word.startTime
        for (k in 0 until bounds.size - 1) {
            val isLastPiece = k == bounds.size - 2
            val share = minOf(duration * weights[k] / totalWeight, weights[k] * MAX_SECONDS_PER_LETTER)
            val pieceEnd = if (isLastPiece) word.endTime else t + share
            result.add(
                word.copy(
                    text = text.substring(bounds[k], bounds[k + 1]),
                    startTime = t,
                    endTime = pieceEnd,
                    hasTrailingSpace = if (isLastPiece) word.hasTrailingSpace else false,
                ),
            )
            t = pieceEnd
        }
        return result
    }

    private const val MAX_SECONDS_PER_LETTER = 0.12

    private fun isAllowedChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in "áéíóúüñÁÉÍÓÚÜÑ" || c == '\'' || c == '’'

    // ---------------------------------------------------------------------------------------
    // Reglas de corte
    // ---------------------------------------------------------------------------------------

    private const val EN_VOWELS = "aeiouy"
    private const val ES_STRONG = "aeoáéíóú"
    private const val ES_WEAK = "iuü"

    private val EN_DIGRAPHS = listOf("tch", "sh", "ch", "th", "ph", "wh", "ck", "ng", "gh", "qu")
    private val ES_DIGRAPHS = listOf("ch", "ll", "rr", "qu", "gu")
    private val CODA_UNITS = setOf("ck", "ng", "tch")
    private val ONSET_PAIRS = setOf(
        "cl", "cr", "pl", "pr", "bl", "br", "tr", "dr", "gl", "gr", "fl", "fr", "kl", "kr", "tw", "sw",
        "thr", "shr",
    )

    /** Posiciones (dentro de la palabra) donde se corta. Vacío si es de una sola sílaba. */
    private fun syllableCuts(core: String, es: Boolean): List<Int> {
        val n = core.length
        val w = String(CharArray(n) { core[it].lowercaseChar() }).replace('’', '\'')

        // Excepciones escritas a mano (palabras compuestas y casos raros)
        EXCEPTIONS[w]?.let { spec ->
            val cutsEx = ArrayList<Int>()
            var pos = 0
            for (ch in spec) {
                if (ch == '-') cutsEx.add(pos) else pos++
            }
            if (pos == n) return cutsEx.filter { it in 1 until n }
        }

        // Palabras compuestas: any-one, when-ev-er, fire-fly, some-what
        if (!es) {
            for (h in COMPOUND_HEADS) {
                if (w.startsWith(h) && w.substring(h.length) in COMPOUND_TAILS) {
                    val head = if (h.length >= 4) syllableCuts(core.substring(0, h.length), false) else emptyList()
                    val tail = syllableCuts(core.substring(h.length), false).map { it + h.length }
                    return (head + h.length + tail).filter { it in 1 until n }.distinct().sorted()
                }
            }
        }

        // "-ly" tras "e" muda: love-ly, lone-ly, like-ly, en-tire-ly
        if (!es && w.endsWith("ly") && n >= 6) {
            val st = n - 2
            if (w[st - 1] == 'e' && w[st - 2] !in EN_VOWELS && w.substring(0, st - 1).any { it in EN_VOWELS }) {
                val inner = syllableCuts(core.substring(0, st), false)
                return (inner + st).filter { it in 1 until n }.distinct().sorted()
            }
        }

        // Terminación "ing" / "in'" como sílaba aparte (walk-ing, run-ning, no-thing, be-co-ming)
        if (!es) {
            val m = ING_REGEX.matchEntire(w)
            if (m != null) {
                val stem = m.groupValues[1]
                var cut = stem.length
                if (stem.length >= 2 && stem[stem.length - 1] == stem[stem.length - 2] &&
                    stem[stem.length - 1].isLetter() && stem[stem.length - 1] !in "aeiouylsz"
                ) {
                    cut = stem.length - 1
                } else if (stem.length >= 2 && stem.endsWith("w") && stem[stem.length - 2] in "aeiou") {
                    // draw-ing, show-ing, know-ing: la "w" se queda con la sílaba de antes
                    cut = stem.length
                } else {
                    // Una sola consonante (o th/sh/ch...) tras la última vocal: se pasa a "ing"
                    var t = stem.length
                    while (t > 0 && stem[t - 1] !in EN_VOWELS) t--
                    val tail = stem.substring(t)
                    if (t > 0 && tail.isNotEmpty() && tail !in CODA_UNITS &&
                        (tail.length == 1 || tail in EN_DIGRAPHS)
                    ) {
                        cut = t
                    }
                }
                val inner = syllableCuts(core.substring(0, cut), false)
                return (inner + cut).filter { it in 1 until n }.distinct().sorted()
            }
        }

        // Terminaciones que se leen aparte (time-less, dark-ness, care-ful, heart-ache, pay-ment)
        if (!es) {
            for (suf in SUFFIXES) {
                if (w.length - suf.length >= 2 && w.endsWith(suf)) {
                    val stemLen = w.length - suf.length
                    if (w.substring(0, stemLen).any { it in EN_VOWELS }) {
                        val inner = syllableCuts(core.substring(0, stemLen), false)
                        return (inner + stemLen).filter { it in 1 until n }.distinct().sorted()
                    }
                }
            }
        }

        // Prefijos que se leen aparte: with-out, with-in, de-stroy, de-scribe
        if (!es) {
            val pre = when {
                WITH_PREFIX_REGEX.containsMatchIn(w) -> 4
                DE_PREFIX_REGEX.containsMatchIn(w) -> 2
                CRE_PREFIX_REGEX.containsMatchIn(w) -> 3
                REACT_REGEX.containsMatchIn(w) -> 2
                else -> 0
            }
            if (pre > 0 && n > pre + 1) {
                val rest = syllableCuts(core.substring(pre), false).map { it + pre }
                return (listOf(pre) + rest).filter { it in 1 until n }.distinct().sorted()
            }
        }

        // Qué letras son vocal
        val isVowel = BooleanArray(n)
        for (i in 0 until n) {
            val c = w[i]
            if (es) {
                isVowel[i] = c in ES_STRONG || c in ES_WEAK
                if (c == 'u' && i > 0 && w[i - 1] == 'q') isVowel[i] = false
                if (c == 'u' && i > 0 && w[i - 1] == 'g' && i + 1 < n && (w[i + 1] == 'e' || w[i + 1] == 'i')) {
                    isVowel[i] = false
                }
                if (c == 'y') {
                    isVowel[i] = i == n - 1 && i > 0 && w[i - 1] in "aeiouáéíóú"
                }
            } else {
                isVowel[i] = c in EN_VOWELS
                // "ow"/"aw"/"ew" tras consonante suena como una sola vocal: pow-er, flow-er
                if (c == 'w' && i >= 2 && w[i - 1] in "aeo" && w[i - 2] !in EN_VOWELS) isVowel[i] = true
                if (c == 'y' && i == 0 && n > 1 && w[1] in "aeiou") isVowel[i] = false
                if (c == 'u' && i > 0 && w[i - 1] == 'q') isVowel[i] = false
            }
        }

        // Grupos de vocales (uno por sílaba): [inicio, fin)
        val groups = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (isVowel[i]) {
                var j = i + 1
                while (j < n && isVowel[j]) {
                    // En español, dos vocales fuertes seguidas van en sílabas distintas
                    if (es && w[j - 1] in ES_STRONG && w[j] in ES_STRONG) break
                    // Tras esa "w" empieza otra sílaba: pow-er
                    if (!es && w[j - 1] == 'w' && w[j] != 'w') break
                    j++
                }
                if (es || j - i < 2) {
                    groups.add(intArrayOf(i, j))
                } else {
                    // Vocales juntas que suenan separadas: cre-ate, i-de-a, po-et, cru-el, ra-di-o
                    var st = i
                    for (k in i until j - 1) {
                        if (isHiatus(w, k, n)) {
                            groups.add(intArrayOf(st, k + 1))
                            st = k + 1
                        }
                    }
                    groups.add(intArrayOf(st, j))
                }
                i = j
            } else {
                i++
            }
        }

        if (!es && groups.size > 1) {
            val last = groups[groups.size - 1]
            // "e" muda al final: love, make, alone (pero no "-le": table, little)
            if (last[1] == n && last[1] - last[0] == 1 && w[n - 1] == 'e') {
                val prevIsL = last[0] >= 2 && w[last[0] - 1] == 'l' && !isVowel[last[0] - 2]
                if (!prevIsL) groups.removeAt(groups.size - 1)
            }
        }
        if (!es && groups.size > 1 && w.endsWith("es")) {
            // "-es" mudo: makes, loves (pero kis-ses, wan-ces)
            val last = groups[groups.size - 1]
            if (last[1] == n - 1 && last[0] == n - 2 && n >= 3 && !isVowel[n - 3] &&
                w[n - 3] !in "sxzcg" && !w.endsWith("hes")
            ) {
                groups.removeAt(groups.size - 1)
            }
        }
        if (!es && groups.size > 1 && w.endsWith("ed")) {
            // "-ed" mudo: walked (pero wan-ted)
            val last = groups[groups.size - 1]
            if (last[1] == n - 1 && last[0] == n - 2 && n >= 3 && !isVowel[n - 3] && w[n - 3] !in "td") {
                groups.removeAt(groups.size - 1)
            }
        }
        if (groups.size < 2) return emptyList()

        val digraphs = (if (es) ES_DIGRAPHS else EN_DIGRAPHS).sortedByDescending { it.length }
        val cuts = ArrayList<Int>()
        for (gi in 0 until groups.size - 1) {
            val a = groups[gi][1]
            val b = groups[gi + 1][0]
            val cluster = w.substring(a, b)

            // Consonantes del medio, juntando las que suenan como una sola (sh, ch, th...)
            val units = ArrayList<String>()
            var k = 0
            while (k < cluster.length) {
                var took = false
                for (d in digraphs) {
                    if (cluster.startsWith(d, k)) {
                        units.add(d)
                        k += d.length
                        took = true
                        break
                    }
                }
                if (!took) {
                    units.add(cluster.substring(k, k + 1))
                    k++
                }
            }

            if (units.isEmpty()) {
                cuts.add(b)
                continue
            }
            // "pock-et", "sing-er": ck / ng pegadas a la sílaba de antes
            if (!es && units.size == 1 && units[0] in CODA_UNITS) {
                cuts.add(b)
                continue
            }
            val isLastGroup = gi + 1 == groups.size - 1
            val finalLe = !es && isLastGroup && w.endsWith("le") && groups[groups.size - 1][0] == n - 1
            if (finalLe) {
                // "ta-ble", "lit-tle", "sim-ple": la consonante de antes de "le" va con "le"
                val beforeL = if (units.size >= 2) units[units.size - 2] else ""
                cuts.add(
                    when {
                        units.size == 1 -> b - units[0].length
                        beforeL in CODA_UNITS -> b - 1
                        else -> b - 1 - beforeL.length
                    },
                )
                continue
            }
            // "high-er", "bright-er": tras "igh" (y "ight") se corta
            if (!es && cluster.startsWith("gh") && w[a - 1] == 'i') {
                cuts.add(if (cluster.startsWith("ght")) a + 3 else a + 2)
                continue
            }
            if (units.size >= 2 && isOnset(units[units.size - 2], units[units.size - 1])) {
                cuts.add(b - units[units.size - 1].length - units[units.size - 2].length)
            } else {
                cuts.add(b - units[units.size - 1].length)
            }
        }
        return cuts.filter { it in 1 until n }.distinct().sorted()
    }

    /** ¿Las dos vocales de la posición k suenan en sílabas distintas? (solo inglés) */
    private fun isHiatus(w: String, k: Int, n: Int): Boolean {
        val pair = w.substring(k, k + 2)
        val prev = if (k > 0) w[k - 1] else ' '
        val next = if (k + 2 < n) w[k + 2] else ' '
        return when (pair) {
            "ia" -> k > 0 && prev !in "ctgsxh"
            "io" -> k > 0 && prev !in "ctsxghn" && !(prev == 'l' && k >= 3)
            "eo" -> k > 0 && prev !in "gc" && !(k == 1 && w[0] in "pj")
            "ao" -> true
            "ea" -> k + 2 == n && w.substring(0, k).any { it in EN_VOWELS }
            "oe" -> next == 't' || next == 'm'
            "ue", "ua" -> next == 'l'
            "ie" -> next == 't' && k > 0
            else -> false
        }
    }

    private fun isOnset(first: String, second: String): Boolean = (first + second) in ONSET_PAIRS

    /** Cortes a mano: solo lo que no tiene regla. Palabra en minúscula -> sílabas separadas por guiones. */
    private val EXCEPTIONS = mapOf(
        "ever" to "ev-er", "every" to "ev-ery", "archangelo" to "arch-an-ge-lo",
    )

    private val COMPOUND_HEADS = listOf(
        "week", "life", "for", "no", "wher", "any", "every", "some", "when", "where", "what", "who", "how", "fire",
    )
    private val COMPOUND_TAILS = setOf(
        "one", "body", "thing", "where", "more", "time", "times", "day", "ever", "how", "way",
        "fly", "what", "work", "works", "long", "line", "lines", "end", "ends", "days", "night", "nights", "ly",
    )

    private val SUFFIXES = listOf("less", "ness", "ment", "ful", "ache")

    private val ING_REGEX = Regex("^(.*[aeiouy].*?)(ing|in')$")
    private val WITH_PREFIX_REGEX = Regex("^with(out|in|draw|hold|stand)")
    private val DE_PREFIX_REGEX = Regex("^de(str|scr|spr)")
    private val CRE_PREFIX_REGEX = Regex("^(cre(ate|ation|ative|ating|ated|ator)|scien)")
    private val REACT_REGEX = Regex("^reac(t|tion|tions|ted)$")
}
