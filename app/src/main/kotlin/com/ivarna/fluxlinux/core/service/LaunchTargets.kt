package com.ivarna.fluxlinux.core.service

/**
 * Pure (no `android.*`) contract for intents that open MainActivity:
 * - which page a notification tap (or termux-x11's "back to app") lands on, and
 * - which distro a `fluxlinux://callback?name=distro_(un)install_<id>` callback may name.
 */
object LaunchTargets {
    /** Intent extra naming the page to open. */
    const val EXTRA_TARGET_PAGE = "EXTRA_TARGET_PAGE"

    /** Alias termux-x11 also sets. */
    const val EXTRA_TARGET_PAGE_ALIAS = "target_page"

    const val PAGE_HOME = "home"
    const val PAGE_TERMINAL = "terminal"

    /**
     * Base-install notification: no navigation (onboarding restores itself), but the tap is
     * not a plain launch, so it never triggers the desktop auto-start.
     */
    const val PAGE_INSTALL = "install"

    enum class Page { NONE, HOME, TERMINAL }

    fun pageFor(targetPage: String?, alias: String?): Page = when {
        targetPage == PAGE_TERMINAL -> Page.TERMINAL
        targetPage == PAGE_HOME || alias == PAGE_HOME -> Page.HOME
        else -> Page.NONE
    }

    /**
     * Distro id carried by a `<prefix><id>` callback name, or null when the name has another
     * prefix or the id is not a known distro (the callback is unauthenticated).
     */
    fun callbackDistroId(scriptName: String, prefix: String, knownIds: Collection<String>): String? {
        if (!scriptName.startsWith(prefix)) return null
        return scriptName.removePrefix(prefix).takeIf { it in knownIds }
    }
}
