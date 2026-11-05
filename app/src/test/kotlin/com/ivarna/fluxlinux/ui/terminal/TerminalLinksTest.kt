package com.ivarna.fluxlinux.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLinksTest {

    @Test
    fun urlIn_plainHttpsWord() {
        assertEquals("https://ubuntu.com/download", TerminalLinks.urlIn("https://ubuntu.com/download"))
    }

    @Test
    fun urlIn_trimsSentencePunctuation() {
        assertEquals("https://ubuntu.com", TerminalLinks.urlIn("https://ubuntu.com."))
        assertEquals("http://example.org/a", TerminalLinks.urlIn("http://example.org/a,"))
        assertEquals("https://x.io/q?a=1", TerminalLinks.urlIn("https://x.io/q?a=1?!"))
    }

    @Test
    fun urlIn_trimsUnbalancedClosersButKeepsBalancedOnes() {
        assertEquals("https://ubuntu.com", TerminalLinks.urlIn("(https://ubuntu.com)."))
        assertEquals("https://ubuntu.com", TerminalLinks.urlIn("[docs](https://ubuntu.com)"))
        assertEquals(
            "https://en.wikipedia.org/wiki/Unix_(disambiguation)",
            TerminalLinks.urlIn("https://en.wikipedia.org/wiki/Unix_(disambiguation)")
        )
    }

    @Test
    fun urlIn_stopsAtQuotesAndAngleBrackets() {
        assertEquals("https://a.example/x", TerminalLinks.urlIn("\"https://a.example/x\""))
        assertEquals("https://a.example/x", TerminalLinks.urlIn("<https://a.example/x>"))
        assertEquals("https://a.example/x", TerminalLinks.urlIn("url='https://a.example/x'"))
    }

    @Test
    fun urlIn_findsLinkGluedToOtherText() {
        assertEquals("https://github.com/x", TerminalLinks.urlIn("remote:https://github.com/x"))
    }

    @Test
    fun urlIn_bareWww() {
        assertEquals("www.ubuntu.com", TerminalLinks.urlIn("www.ubuntu.com"))
        assertEquals("https://www.ubuntu.com", TerminalLinks.toUri("www.ubuntu.com"))
        assertEquals("http://a.b", TerminalLinks.toUri("http://a.b"))
    }

    @Test
    fun urlIn_rejectsNonWebAndHostless() {
        assertNull(TerminalLinks.urlIn("file:///etc/passwd"))
        assertNull(TerminalLinks.urlIn("ftp://mirror.example"))
        assertNull(TerminalLinks.urlIn("https://"))
        assertNull(TerminalLinks.urlIn("https://."))
        assertNull(TerminalLinks.urlIn("www."))
        assertNull(TerminalLinks.urlIn("ls -la /home/akane"))
        assertNull(TerminalLinks.urlIn(""))
    }

    @Test
    fun urlIn_isCaseInsensitiveOnScheme() {
        assertEquals("HTTPS://Ubuntu.com", TerminalLinks.urlIn("HTTPS://Ubuntu.com"))
    }

    @Test
    fun toUri_lowercasesTheSchemeOnly() {
        // Android matches intent schemes case-sensitively: HTTPS:// would find no browser.
        assertEquals("https://Ubuntu.com/Download", TerminalLinks.toUri("HTTPS://Ubuntu.com/Download"))
        assertEquals("http://A.b/C", TerminalLinks.toUri("Http://A.b/C"))
        assertEquals("https://WWW.kernel.org", TerminalLinks.toUri("WWW.kernel.org"))
    }

    @Test
    fun findAll_distinctInOrder() {
        val text = """
            Cloning from https://github.com/ohmyzsh/ohmyzsh.git
            see https://ubuntu.com, and https://github.com/ohmyzsh/ohmyzsh.git again
            docs: www.kernel.org.
        """.trimIndent()
        assertEquals(
            listOf("https://github.com/ohmyzsh/ohmyzsh.git", "https://ubuntu.com", "www.kernel.org"),
            TerminalLinks.findAll(text)
        )
    }
}
