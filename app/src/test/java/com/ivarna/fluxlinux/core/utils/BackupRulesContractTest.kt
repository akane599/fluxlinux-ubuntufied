package com.ivarna.fluxlinux.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L12: device-local rootfs state must not be restored from backup or device transfer.
 * File names are read from the sources, so renaming a prefs file fails here instead of
 * silently re-enabling its backup.
 */
class BackupRulesContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile } ?: error("missing $rel (cwd=$cwd)")
    }

    private fun src(rel: String) = repoFile("src/main/kotlin/com/ivarna/fluxlinux/$rel").readText()

    private fun prefsConst(rel: String): String =
        Regex("""const val PREFS = "([^"]+)"""").find(src(rel))?.groupValues?.get(1) ?: error("no PREFS in $rel")

    private fun excludedPrefs(): Set<String> {
        val state = Regex("""getSharedPreferences\("([^"]+)"""").findAll(src("core/utils/StateManager.kt"))
            .map { it.groupValues[1] }.toSet()
        assertEquals("StateManager uses one prefs file", 1, state.size)
        val gpu = setOf(prefsConst("core/gpu/GpuDriverInstaller.kt"), prefsConst("core/gpu/PanvkInstaller.kt"))
        return state + prefsConst("core/terminal/GuestRepairCache.kt") + gpu
    }

    private fun assertExcludes(section: String, rel: String) {
        for (name in excludedPrefs()) {
            assertTrue(
                "$rel <$section> excludes $name.xml",
                section.contains("""<exclude domain="sharedpref" path="$name.xml"/>"""),
            )
        }
    }

    @Test
    fun fullBackupRules_excludeDeviceLocalState() {
        val rel = "src/main/res/xml/backup_rules.xml"
        assertExcludes(repoFile(rel).readText(), rel)
    }

    @Test
    fun dataExtractionRules_excludeDeviceLocalState_inBothSections() {
        val rel = "src/main/res/xml/data_extraction_rules.xml"
        val xml = repoFile(rel).readText()
        for (tag in listOf("cloud-backup", "device-transfer")) {
            val section = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
                ?: error("missing <$tag>")
            assertExcludes(section, "$rel <$tag>")
        }
    }
}
