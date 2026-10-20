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

    @Test
    fun gitClones_arePinnedToCommitShas() {
        val calls = Regex("""_flux_git_clone "https://[^"]+"[^\n\\]*(?:\\\n[^\n]*)?""")
            .findAll(script).map { it.value }.toList()
        assertTrue("expected OMZ, 3 plugins and pokemon", calls.size >= 5)
        calls.forEach { call ->
            assertTrue("unpinned clone: $call", Regex(""""\$[A-Z_]+_SHA"""").containsMatchIn(call))
        }
        listOf(
            "OMZ_SHA", "ZSH_AUTOSUGGEST_SHA", "ZSH_SYNTAX_HL_SHA", "FZF_TAB_SHA",
            "POKEMON_SHA", "AGNOSTERZAK_SHA",
        ).forEach { name ->
            assertTrue("$name must be a full commit SHA", Regex("""\n$name="[0-9a-f]{40}"""").containsMatchIn(script))
        }
        val clone = body("_flux_git_clone")
        assertTrue(clone.contains("fetch -q --depth 1 \"\$_url\" \"\$_sha\""))
        assertTrue(clone.contains("rev-parse HEAD"))
        assertFalse(clone.contains("git clone"))
    }

    @Test
    fun downloadsAreVersionedAndHashed() {
        assertFalse("no floating release", script.contains("releases/latest"))
        assertFalse("no floating branch", script.contains("agnosterzak-ohmyzsh-theme/master/"))
        assertTrue(Regex("""\n\s*NERD_FONT_SHA256="[0-9a-f]{64}"""").containsMatchIn(script))
        assertTrue(script.contains("_flux_sha256_ok \"\$TEMP_ZIP\" \"\$NERD_FONT_SHA256\""))
        assertTrue(Regex("""\nAGNOSTERZAK_SHA256="[0-9a-f]{64}"""").containsMatchIn(script))
        assertTrue(script.contains("_flux_sha256_ok \"\$_az_part\" \"\$AGNOSTERZAK_SHA256\""))
    }

    @Test
    fun pokemon_isCopiedNotInstalledByUpstreamScript() {
        assertFalse("never run fetched install.sh as root", script.contains("./install.sh"))
        assertTrue(script.contains("ln -s \"\$_pk_dir/pokemon-colorscripts.py\" /usr/local/bin/pokemon-colorscripts"))
    }
}
