package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Download-integrity contract for setup_customization_xfce.sh. */
class CustomizationXfceScriptContractTest {

    private val script: String by lazy {
        val rel = "src/main/assets/scripts/common/setup/setup_customization_xfce.sh"
        val cwd = File("").absoluteFile
        listOf(File(cwd, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $rel (cwd=$cwd)")
    }

    private fun body(fn: String): String =
        script.substringAfter("$fn() {\n").substringBefore("\n}\n")

    @Test
    fun downloads_goThroughPartialAndZipTest() {
        val dl = body("download_if_needed")
        assertTrue(dl.contains("_part=\"\$_out.partial\""))
        assertTrue(dl.contains("mv -f \"\$_part\" \"\$_out\""))
        // The .partial name hides the .zip suffix, so the check takes the final name.
        assertTrue(dl.contains("_flux_archive_ok \"\$_part\" \"\$_out\""))
        assertFalse("never fetch straight into the cached name", dl.contains("_flux_fetch \"\$_url\" \"\$_out\""))
        assertTrue(body("_flux_archive_ok").contains("unzip -tq"))
    }

    @Test
    fun unzipResults_areChecked() {
        for (zip in listOf("theme", "icons", "cursor")) {
            assertTrue(
                "$zip.zip extraction must be checked",
                script.contains("if unzip -q -o /tmp/$zip.zip -d /tmp/flux_${zip}_zip; then"),
            )
        }
        assertTrue(script.contains("if ! unzip -o -j \"\$TEMP_WP_ZIP\" -d \"\$WALLPAPER_DIR\""))
        assertFalse(script.contains("unzip -o -j \"\$TEMP_WP_ZIP\" -d \"\$WALLPAPER_DIR\" 2>/dev/null || true"))
    }
}
