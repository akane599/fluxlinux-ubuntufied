package com.ivarna.fluxlinux.core.install

/**
 * Pure assembly of a Distro Settings component payload: env exports, then the
 * shared helper library the component script depends on, then the script.
 *
 * Mirrors what onboarding prepends in [BaseDesktopInstallPlan.familySetupPayload]
 * (`flux_guest_common.sh` before a family script) and
 * [BaseDesktopInstallPlan.hwAccelPayload] (`flux_gpu_common.sh` before the
 * hw-accel installer), so a component re-run gets the same helpers.
 */
object GuestComponentPayload {

    const val GUEST_COMMON_SCRIPT = "common/setup/flux_guest_common.sh"
    const val GPU_COMMON_SCRIPT = "common/setup/flux_gpu_common.sh"

    /** Asset path of the helper library [componentId] needs, or null when none. */
    fun commonScriptFor(componentId: String): String? = when (componentId) {
        "hw_accel" -> GPU_COMMON_SCRIPT
        // setup_*_family.sh calls _flux_* helpers defined in flux_guest_common.sh.
        "xfce4_desktop" -> GUEST_COMMON_SCRIPT
        else -> null
    }

    /**
     * Builds the payload. [readScript] loads an asset script by path; a missing
     * helper library is tolerated (the guest script reports it), a missing
     * component script is not.
     */
    fun build(
        componentId: String,
        scriptName: String,
        extraEnv: Map<String, String>,
        readScript: (String) -> String,
    ): String {
        val base = readScript(scriptName)
        val common = commonScriptFor(componentId)
            ?.let { path -> runCatching { readScript(path) }.getOrDefault("") }
            .orEmpty()
        return buildString {
            if (extraEnv.isNotEmpty()) {
                append(extraEnv.entries.joinToString("\n") { "export ${it.key}=\"${it.value}\"" })
                append("\n\n")
            }
            if (common.isNotBlank()) append(common).append("\n\n")
            append(base)
        }
    }
}
