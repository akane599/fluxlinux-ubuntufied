package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuestComponentPayloadTest {

    private val fakeAssets = mapOf(
        "ubuntu/common/setup/setup_ubuntu_family.sh" to "FAMILY_BODY",
        "common/setup/setup_hw_accel_guest.sh" to "HW_BODY",
        "common/setup/setup_customization_xfce.sh" to "CUSTOM_BODY",
        GuestComponentPayload.GUEST_COMMON_SCRIPT to "GUEST_COMMON",
        GuestComponentPayload.GPU_COMMON_SCRIPT to "GPU_COMMON",
    )

    private fun read(path: String): String = fakeAssets[path] ?: error("no asset $path")

    @Test
    fun familyComponent_prependsGuestCommonBeforeScript() {
        val payload = GuestComponentPayload.build(
            componentId = "xfce4_desktop",
            scriptName = "ubuntu/common/setup/setup_ubuntu_family.sh",
            extraEnv = emptyMap(),
            readScript = ::read,
        )
        assertEquals("GUEST_COMMON\n\nFAMILY_BODY", payload)
    }

    @Test
    fun hwAccel_prependsGpuCommonAfterEnv() {
        val payload = GuestComponentPayload.build(
            componentId = "hw_accel",
            scriptName = "common/setup/setup_hw_accel_guest.sh",
            extraEnv = linkedMapOf("FLUX_GPU" to "virgl"),
            readScript = ::read,
        )
        assertEquals("export FLUX_GPU=\"virgl\"\n\nGPU_COMMON\n\nHW_BODY", payload)
    }

    @Test
    fun otherComponents_getNoHelperLibrary() {
        assertNull(GuestComponentPayload.commonScriptFor("customization"))
        val payload = GuestComponentPayload.build(
            componentId = "customization",
            scriptName = "common/setup/setup_customization_xfce.sh",
            extraEnv = emptyMap(),
            readScript = ::read,
        )
        assertEquals("CUSTOM_BODY", payload)
    }

    @Test
    fun missingHelperLibrary_stillReturnsScript() {
        val payload = GuestComponentPayload.build(
            componentId = "xfce4_desktop",
            scriptName = "ubuntu/common/setup/setup_ubuntu_family.sh",
            extraEnv = emptyMap(),
            readScript = { path ->
                if (path == GuestComponentPayload.GUEST_COMMON_SCRIPT) error("gone") else read(path)
            },
        )
        assertEquals("FAMILY_BODY", payload)
    }

    @Test
    fun guestCommonAsset_definesHelpersTheFamilyGuardChecks() {
        val common = repoFile("src/main/assets/scripts/${GuestComponentPayload.GUEST_COMMON_SCRIPT}")
            .readText()
        assertTrue(common.contains("_flux_setup_pulse() {"))
        assertTrue(common.contains("_flux_ensure_en_us_locale() {"))
        assertTrue(repoFile("src/main/assets/scripts/${GuestComponentPayload.GPU_COMMON_SCRIPT}").isFile)
    }

    @Test
    fun ubuntuFamily_failsClearlyWhenHelpersMissing() {
        val text = repoFile("src/main/assets/scripts/ubuntu/common/setup/setup_ubuntu_family.sh")
            .readText()
        val guard = text.indexOf("flux_guest_common.sh missing")
        assertTrue("family script must stop when helpers are missing", guard > 0)
        assertTrue(text.substring(guard).lineSequence().take(3).any { it.trim() == "exit 2" })
        // The guard must run before the first helper call.
        assertTrue(guard < text.indexOf("_flux_ensure_tmp\n"))
        assertFalse(text.substring(0, guard).contains("\n_flux_"))
    }

    @Test
    fun ubuntuFamily_switchesToGnuCoreutilsBeforeOtherInstalls() {
        val text = repoFile("src/main/assets/scripts/ubuntu/common/setup/setup_ubuntu_family.sh")
            .readText()
        val switch = text.indexOf("apt-get install -y --allow-remove-essential coreutils-from-gnu")
        assertTrue("family setup must install coreutils-from-gnu", switch > 0)
        assertTrue("needs the apt lists", text.indexOf("if ! apt-get update") in 0 until switch)
        assertEquals("switch precedes every other install", switch, text.indexOf("apt-get install"))
    }

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile }
            ?: error("missing $rel (cwd=$cwd)")
    }
}
