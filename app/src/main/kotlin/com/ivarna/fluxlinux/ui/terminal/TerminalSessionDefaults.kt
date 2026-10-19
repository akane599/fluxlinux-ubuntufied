package com.ivarna.fluxlinux.ui.terminal

import com.ivarna.fluxlinux.core.terminal.TerminalShellCardUi

/** Which Ubuntu-first shells the terminal offers, resolved from the catalog cards. */
data class TerminalSessionChoices(
    /** Ubuntu PRoot user shell: what the empty state and "+" open. Null if the catalog lacks it. */
    val defaultUser: TerminalShellCardUi?,
    /** Ubuntu PRoot root shell, when Ubuntu PRoot is installed. */
    val ubuntuRoot: TerminalShellCardUi?,
    /** Ubuntu chroot shell, only when root is granted and the chroot is installed. */
    val ubuntuChroot: TerminalShellCardUi?,
    /** Embedded Termux host shell, kept for recovery. */
    val host: TerminalShellCardUi?,
) {
    /** Secondary shells for the "+" long-press menu, in display order. */
    val extras: List<TerminalShellCardUi> get() = listOfNotNull(ubuntuRoot, ubuntuChroot, host)
}

/**
 * Pure selection of the terminal's Ubuntu shells from [com.ivarna.fluxlinux.core.terminal.TerminalShellCatalog]
 * cards, so the session type, title and method always come from the catalog.
 */
object TerminalSessionDefaults {
    const val UBUNTU_PROOT_ID = "ubuntu"
    const val UBUNTU_CHROOT_ID = "ubuntu_chroot"

    private const val TYPE_USER = "shell"
    private const val TYPE_ROOT = "shell-root"

    fun resolve(cards: List<TerminalShellCardUi>): TerminalSessionChoices {
        fun find(method: String, distroId: String?, type: String) = cards.firstOrNull {
            it.def.method == method && it.def.distroId == distroId && it.def.type == type
        }
        return TerminalSessionChoices(
            defaultUser = find("proot", UBUNTU_PROOT_ID, TYPE_USER),
            ubuntuRoot = find("proot", UBUNTU_PROOT_ID, TYPE_ROOT)?.takeIf { it.enabled },
            // Card enabled == chroot installed AND root granted (TerminalShellCatalog.sections).
            ubuntuChroot = find("chroot", UBUNTU_CHROOT_ID, TYPE_USER)?.takeIf { it.enabled },
            host = cards.firstOrNull { it.def.method == "host" && it.enabled },
        )
    }

    /** Compact tab label: drops the catalog's " Shell" noise ("Ubuntu Shell Rooted" -> "Ubuntu root"). */
    fun shortTabTitle(title: String): String {
        val short = title
            .replace(" Shell Rooted", " root")
            .replace(" Rooted", " root")
            .replace(" Shell", "")
            .trim()
        return short.ifBlank { title.trim() }
    }
}
