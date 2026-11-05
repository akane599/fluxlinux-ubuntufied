package com.ivarna.fluxlinux.core.install

/**
 * What one install run does, as data: [OnboardingInstallRunner] branches only on these
 * flags, never on [InstallKind] or the method itself. Pure, unit-tested per kind × method.
 *
 * [kind] is the effective kind ([InstallKind.effective]); [method] is `proot` or `chroot`.
 */
data class InstallSteps(
    val method: String,
    val kind: InstallKind,
    /** Chroot: the R0 phase checks root before anything else runs. */
    val checkRoot: Boolean,
    /** Refuse unless the rootfs is already installed (checked after the host phase). */
    val needsInstalledRootfs: Boolean,
    /** Download the pinned rootfs archive (DL phase). */
    val downloadRootfs: Boolean,
    /**
     * The run may (re)extract the rootfs: chroot runs the ROOTFS phase (setup script as
     * root); PRoot lets flux_install.sh extract a missing or interrupted rootfs. Never true
     * with [needsInstalledRootfs], and flux_install.sh never re-extracts a completely
     * extracted rootfs.
     */
    val extractRootfs: Boolean,
    /** Id of the phase the family script runs in ([BaseDesktopInstallPlan.familyPhaseId]). */
    val familyPhaseId: String,
    /** `FLUX_INSTALL_DESKTOP` for the family script (XFCE packages, startxfce4, GPU mode). */
    val installDesktop: Boolean,
    /** Set the desktop-incomplete marker right before the family step (cleared on success). */
    val markDesktopIncomplete: Boolean,
    /** Fail the run unless `startxfce4` is in the guest after the family step. */
    val requireStartxfce4: Boolean,
    /** Stage the XFCE theme / icon / cursor / wallpaper assets before customization. */
    val stageXfceAssets: Boolean,
    /** PRoot: stage Oh My Zsh on the host (a guest clone under proot can hang). */
    val stageHostOhMyZsh: Boolean,
    /** `FLUX_CUSTOMIZE_DESKTOP` for the customization script. */
    val customizeDesktop: Boolean,
    /** Run the guest hardware-acceleration installer. */
    val runHwAccel: Boolean,
    /** On success, record `xfce4_desktop` (and `customization` / `hw_accel` when they passed). */
    val markDesktopComponents: Boolean,
) {
    val chroot: Boolean get() = method == "chroot"

    /** The run only adds the desktop to an installed system (phase texts say "XFCE"). */
    val desktopOnly: Boolean get() = needsInstalledRootfs && installDesktop

    companion object {
        fun of(method: String, kind: InstallKind): InstallSteps {
            val chroot = method == "chroot"
            val desktop = kind.installsDesktop
            val addOn = kind == InstallKind.DESKTOP_ADD_ON
            return InstallSteps(
                method = method,
                kind = kind,
                checkRoot = chroot,
                needsInstalledRootfs = addOn,
                downloadRootfs = !addOn,
                extractRootfs = !addOn,
                familyPhaseId = BaseDesktopInstallPlan.familyPhaseId(method, kind),
                installDesktop = desktop,
                markDesktopIncomplete = desktop,
                requireStartxfce4 = desktop,
                stageXfceAssets = desktop,
                stageHostOhMyZsh = !chroot,
                customizeDesktop = desktop,
                runHwAccel = desktop,
                markDesktopComponents = desktop,
            )
        }
    }
}
