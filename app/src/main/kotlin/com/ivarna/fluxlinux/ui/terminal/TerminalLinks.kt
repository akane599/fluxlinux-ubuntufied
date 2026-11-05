package com.ivarna.fluxlinux.ui.terminal

/**
 * Finds web links in terminal text (pure, JVM-tested).
 *
 * Only `http://`, `https://` and bare `www.` links: those are what a browser opens. `file://`
 * would throw FileUriExposedException on startActivity, and `ftp://` rarely has a handler.
 * Trailing sentence punctuation and closing brackets/quotes that have no opener inside the
 * link are trimmed, so `(see https://ubuntu.com).` yields `https://ubuntu.com`.
 */
object TerminalLinks {

    private val CANDIDATE = Regex("""(?i)(?:https?://|www\.)[^\s<>"'`]+""")
    private val TRAILING_PUNCTUATION = ".,;:!?"
    private val CLOSERS = mapOf(')' to '(', ']' to '[', '}' to '{')

    /** First link in [text] (e.g. the word under a tap), or null. */
    fun urlIn(text: String): String? = findAll(text).firstOrNull()

    /** Every distinct link in [text], in order of first appearance. */
    fun findAll(text: String): List<String> =
        CANDIDATE.findAll(text)
            .mapNotNull { clean(it.value) }
            .distinct()
            .toList()

    /**
     * The URI to hand to the browser: bare `www.` links get `https://`, and the scheme is
     * lowercased because Android matches intent schemes case-sensitively (`HTTPS://` finds no app).
     */
    fun toUri(url: String): String = when {
        url.startsWith("www.", ignoreCase = true) -> "https://$url"
        url.startsWith("https://", ignoreCase = true) -> "https://" + url.substring(8)
        url.startsWith("http://", ignoreCase = true) -> "http://" + url.substring(7)
        else -> url
    }

    internal fun clean(candidate: String): String? {
        var s = candidate
        while (s.isNotEmpty()) {
            val last = s.last()
            val opener = CLOSERS[last]
            s = when {
                last in TRAILING_PUNCTUATION -> s.dropLast(1)
                opener != null && s.count { it == last } > s.count { it == opener } -> s.dropLast(1)
                else -> break
            }
        }
        return s.takeIf(::hasHost)
    }

    /** A host must follow the scheme (or `www.`): rejects `https://` and `www.` alone. */
    private fun hasHost(s: String): Boolean {
        val rest = when {
            s.startsWith("https://", ignoreCase = true) -> s.substring(8)
            s.startsWith("http://", ignoreCase = true) -> s.substring(7)
            s.startsWith("www.", ignoreCase = true) -> s.substring(4)
            else -> return false
        }
        return rest.isNotEmpty() && (rest[0].isLetterOrDigit() || rest[0] == '[')
    }
}
