package com.ivarna.fluxlinux.core.install

import com.ivarna.fluxlinux.core.terminal.ZshPluginSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L11: every customization script fetches Oh My Zsh, the zsh plugins, the
 * agnosterzak theme, pokemon-colorscripts and the Nerd Font at the same
 * reviewed pins, and those pins match [ZshPluginSet] (host-side bootstrap).
 */
class CustomizationScriptPinsContractTest {

    private val xfce = "common/setup/setup_customization_xfce.sh"
    private val others = listOf(
        "debian/common/setup/setup_customization_debian.sh",
        "alpine/common/setup/setup_customization_alpine.sh",
        "debian/common/setup/setup_customization_kde_debian.sh",
    )

    private fun read(rel: String): String {
        val path = "src/main/assets/scripts/$rel"
        val cwd = File("").absoluteFile
        return listOf(File(cwd, path), File(cwd, "app/$path"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $path (cwd=$cwd)")
    }

    /** `NAME="value"` assignments for pin-like names, in file order. */
    private fun pins(script: String): Map<String, String> =
        Regex("""(?m)^\s*([A-Z_]+_(?:SHA|SHA256|VERSION))="([^"]+)"""")
            .findAll(script)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val kotlinPins: Map<String, String> = mapOf(
        "OMZ_SHA" to ZshPluginSet.OMZ.commit,
        "ZSH_AUTOSUGGEST_SHA" to ZshPluginSet.EXTERNAL_PLUGINS.getValue("zsh-autosuggestions").commit,
        "ZSH_SYNTAX_HL_SHA" to ZshPluginSet.EXTERNAL_PLUGINS.getValue("zsh-syntax-highlighting").commit,
        "FZF_TAB_SHA" to ZshPluginSet.EXTERNAL_PLUGINS.getValue("fzf-tab").commit,
        "AGNOSTERZAK_SHA" to ZshPluginSet.AGNOSTERZAK_COMMIT,
        "AGNOSTERZAK_SHA256" to ZshPluginSet.AGNOSTERZAK_SHA256,
    )

    @Test
    fun xfcePins_matchZshPluginSet() {
        val xfcePins = pins(read(xfce))
        kotlinPins.forEach { (name, value) ->
            assertEquals("$name in $xfce vs ZshPluginSet", value, xfcePins[name])
        }
        val script = read(xfce)
        assertTrue(script.contains(ZshPluginSet.OMZ.url))
        ZshPluginSet.EXTERNAL_PLUGINS.values.forEach { assertTrue(script.contains(it.url)) }
    }

    @Test
    fun otherScripts_useTheXfcePins() {
        val reference = pins(read(xfce))
        for (rel in others) {
            val own = pins(read(rel))
            listOf("OMZ_SHA", "ZSH_AUTOSUGGEST_SHA", "ZSH_SYNTAX_HL_SHA", "POKEMON_SHA",
                "AGNOSTERZAK_SHA", "AGNOSTERZAK_SHA256", "NERD_FONT_VERSION", "NERD_FONT_SHA256",
            ).forEach { name ->
                assertTrue("$rel lacks $name", name in own)
            }
            own.forEach { (name, value) ->
                assertEquals("$rel $name drifted from $xfce", reference[name], value)
            }
        }
    }

    @Test
    fun otherScripts_fetchPinnedCommitsOnly() {
        for (rel in others) {
            val script = read(rel)
            assertFalse(
                "$rel: moving-branch git clone",
                Regex("""\bgit clone\s+(?:--|https|'|")""").containsMatchIn(script),
            )
            val calls = Regex("""_flux_git_clone "https://[^"]+"[^\n\\]*(?:\\\n[^\n]*)?""")
                .findAll(script).map { it.value }.toList()
            assertEquals("$rel: OMZ, 2 plugins and pokemon", 4, calls.size)
            calls.forEach { call ->
                assertTrue("$rel unpinned: $call", Regex(""""\$[A-Z_]+_SHA"""").containsMatchIn(call))
            }
            assertTrue(script.contains("https://github.com/ohmyzsh/ohmyzsh.git\" \"\$USER_HOME/.oh-my-zsh\" \"\$OMZ_SHA\""))
            val clone = script.substringAfter("_flux_git_clone() {").substringBefore("\n}\n")
            assertTrue(Regex("""fetch -q --depth 1 "\${'$'}_?url" "\${'$'}_?sha"""").containsMatchIn(clone))
            assertTrue(clone.contains("rev-parse HEAD"))
        }
    }

    @Test
    fun otherScripts_hashDownloadsAndNeverRunFetchedInstallers() {
        for (rel in others) {
            val script = read(rel)
            assertFalse("$rel: floating release", script.contains("releases/latest"))
            assertFalse("$rel: floating theme", script.contains("agnosterzak-ohmyzsh-theme/master/"))
            assertFalse("$rel: OMZ installer", script.contains("ohmyzsh/master/tools/install.sh"))
            assertFalse("$rel: fetched install.sh", Regex("""(?m)^[^#\n]*\binstall\.sh""").containsMatchIn(script))
            assertFalse("$rel: curl | sh", Regex("""sh -c "\$\(curl""").containsMatchIn(script))
            assertTrue(script.contains("_flux_sha256_ok \"\$TEMP_ZIP\" \"\$NERD_FONT_SHA256\""))
            assertTrue(script.contains("_flux_sha256_ok \"\$_az_part\" \"\$AGNOSTERZAK_SHA256\""))
            assertTrue(script.contains("/agnosterzak-ohmyzsh-theme/\$AGNOSTERZAK_SHA/agnosterzak.zsh-theme"))
            assertTrue(
                script.contains("ln -s \"\$_pk_dir/pokemon-colorscripts.py\" /usr/local/bin/pokemon-colorscripts"),
            )
            // The sha256 helper is defined before the font step uses it.
            assertTrue(script.indexOf("_flux_sha256_ok() {") < script.indexOf("_flux_sha256_ok \"\$TEMP_ZIP\""))
        }
        assertFalse(read(others[2]).contains("zsh-autocomplete.git"))
    }
}
