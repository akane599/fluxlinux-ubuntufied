package com.ivarna.fluxlinux.core.install

import com.ivarna.fluxlinux.core.utils.UbuntuRuntime

/**
 * What an install run sets up ([InstallController.start]).
 *
 * - [TERMINAL]: rootfs, base packages, user, zsh / Oh My Zsh. No XFCE, no desktop theme,
 *   no hardware acceleration.
 * - [TERMINAL_AND_DESKTOP]: [TERMINAL] plus XFCE, the Flux desktop customization and
 *   hardware acceleration (the pre-optional-desktop install).
 * - [DESKTOP_ADD_ON]: XFCE, desktop customization and hardware acceleration on top of an
 *   already installed rootfs; no download or extraction.
 */
enum class InstallKind {
    TERMINAL,
    TERMINAL_AND_DESKTOP,
    DESKTOP_ADD_ON,
    ;

    /** True when this run installs (and customizes) the XFCE desktop. */
    val installsDesktop: Boolean get() = this != TERMINAL

    companion object {
        /**
         * Only the Ubuntu family script honours `FLUX_INSTALL_DESKTOP=0`; every other distro's
         * family setup always installs XFCE, so a [TERMINAL] request runs as
         * [TERMINAL_AND_DESKTOP] there (the desktop it gets is then verified and recorded).
         */
        fun effective(distroId: String, requested: InstallKind): InstallKind =
            if (requested == TERMINAL && !supportsTerminalOnly(distroId)) TERMINAL_AND_DESKTOP else requested

        fun supportsTerminalOnly(distroId: String): Boolean = UbuntuRuntime.isUbuntuId(distroId)

        /**
         * Kind for re-running setup over an existing install (the install wizard): keep the
         * desktop when it is installed, otherwise refresh the terminal setup only.
         */
        fun forRerun(desktopInstalled: Boolean): InstallKind =
            if (desktopInstalled) TERMINAL_AND_DESKTOP else TERMINAL
    }
}
