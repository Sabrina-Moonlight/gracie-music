/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.lyrics

/**
 * Quita del título las etiquetas de versión que los buscadores de letras no usan,
 * por ejemplo "(Taylor's Version)" o "(From The Vault)".
 */
object TitleVersionTag {
    private val TAG = Regex(
        """\s*[\(\[][^\)\]]*(?:taylor\W*s\s+version|from\s+the\s+vault)[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val DASH_TAG = Regex(
        """\s+[-–—]\s+[^-–—]*(?:taylor\W*s\s+version|from\s+the\s+vault)[^-–—]*$""",
        RegexOption.IGNORE_CASE,
    )

    fun strip(title: String): String =
        title.replace(TAG, "").replace(DASH_TAG, "").replace(Regex("""\s+"""), " ").trim()
}
