package com.ivarna.fluxlinux.ui.terminal

import com.ivarna.fluxlinux.core.terminal.TerminalShellCardUi
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode

/** Which Ubuntu-first shells the terminal offers, resolved from the catalog cards. */
data class TerminalSessionChoices(
    /** Which Ubuntu install is active; [defaultUser]/[ubuntuRoot] belong to it. */
    val mode: UbuntuRuntimeMode,
    /**
     * User shell of the active Ubuntu install: what the empty state and "New session" open.
     * Present but disabled when that install is missing (or, for chroot, root isn't granted).
     * Null if the catalog lacks it.
     */
    val defaultUser: TerminalShellCardUi?,
    /** Root shell of the active Ubuntu install, only when it can be opened. */
    val ubuntuRoot: TerminalShellCardUi?,
    /** User shell of the other Ubuntu install (PRoot <-> chroot), only when it can be opened. */
    val otherUser: TerminalShellCardUi?,
    /** Root shell of the other Ubuntu install, only when it can be opened. */
    val otherRoot: TerminalShellCardUi?,
    /** Embedded Termux host shell, kept for recovery. */
    val host: TerminalShellCardUi?,
) {
    /** The Ubuntu chroot user shell when it can be opened, whichever mode is active. */
    val ubuntuChroot: TerminalShellCardUi?
        get() = if (mode == UbuntuRuntimeMode.CHROOT) defaultUser?.takeIf { it.enabled } else otherUser

    /** Secondary shells for the "New session" long-press menu, in display order. */
    val extras: List<TerminalShellCardUi> get() = listOfNotNull(ubuntuRoot, otherUser, otherRoot, host)
}

/**
 * Pure selection of the terminal's Ubuntu shells from [com.ivarna.fluxlinux.core.terminal.TerminalShellCatalog]
 * cards, so the session type, title and method always come from the catalog.
 */
object TerminalSessionDefaults {
    const val UBUNTU_PROOT_ID = UbuntuRuntime.PROOT_ID
    const val UBUNTU_CHROOT_ID = UbuntuRuntime.CHROOT_ID

    private const val TYPE_USER = "shell"
    private const val TYPE_ROOT = "shell-root"

    /**
     * [mode] picks the default install (`UbuntuRuntime.mode`). Chroot cards are enabled only
     * when the chroot is installed AND root is granted (TerminalShellCatalog.sections), so the
     * caller must build them from an off-main availability snapshot.
     */
    fun resolve(
        cards: List<TerminalShellCardUi>,
        mode: UbuntuRuntimeMode = UbuntuRuntimeMode.PROOT,
    ): TerminalSessionChoices {
        fun find(method: String, distroId: String, type: String) = cards.firstOrNull {
            it.def.method == method && it.def.distroId == distroId && it.def.type == type
        }
        val prootUser = find("proot", UBUNTU_PROOT_ID, TYPE_USER)
        val prootRoot = find("proot", UBUNTU_PROOT_ID, TYPE_ROOT)
        val chrootUser = find("chroot", UBUNTU_CHROOT_ID, TYPE_USER)
        val chrootRoot = find("chroot", UBUNTU_CHROOT_ID, TYPE_ROOT)
        val chroot = mode == UbuntuRuntimeMode.CHROOT
        return TerminalSessionChoices(
            mode = mode,
            defaultUser = if (chroot) chrootUser else prootUser,
            ubuntuRoot = (if (chroot) chrootRoot else prootRoot)?.takeIf { it.enabled },
            otherUser = (if (chroot) prootUser else chrootUser)?.takeIf { it.enabled },
            otherRoot = (if (chroot) prootRoot else chrootRoot)?.takeIf { it.enabled },
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
