package com.ivarna.fluxlinux.ui.screens

import com.ivarna.fluxlinux.core.data.DistroComponent
import com.ivarna.fluxlinux.core.data.DistroRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComponentUninstallTest {

    private fun component(id: String, script: String = "x.sh", mandatory: Boolean = false) =
        DistroComponent(
            id = id,
            name = id,
            description = "",
            scriptName = script,
            sizeEstimate = "",
            isMandatory = mandatory,
        )

    @Test
    fun installOnlyComponents_haveNoUninstall() {
        assertFalse(componentSupportsUninstall(component("xfce4_desktop")))
        assertFalse(
            componentSupportsUninstall(
                component("customization", "common/setup/setup_customization_xfce.sh"),
            ),
        )
        assertFalse(componentSupportsUninstall(component("kde_customization")))
        assertFalse(componentSupportsUninstall(component("hw_accel", mandatory = true)))
    }

    @Test
    fun kdePlasma_keepsUninstall() {
        assertTrue(
            componentSupportsUninstall(
                component("kde_plasma", "debian/common/setup/setup_kde_debian.sh"),
            ),
        )
    }

    /** Every component that shows Uninstall must ship a script that handles it. */
    @Test
    fun uninstallButton_onlyForScriptsWithUninstallBranch() {
        val offered = DistroRepository.supportedDistros
            .flatMap { it.components }
            .filter { componentSupportsUninstall(it) }
        offered.forEach { c ->
            val script = repoFile("src/main/assets/scripts/${c.scriptName}")
            assertTrue(
                "${c.id} (${c.scriptName}) offers Uninstall but has no uninstall branch",
                script.readText().contains("uninstall"),
            )
        }
        val customization = DistroRepository.supportedDistros
            .flatMap { it.components }
            .filter { it.scriptName.contains("setup_customization") }
        assertTrue(customization.isNotEmpty())
        assertEquals(
            emptyList<String>(),
            customization.filter { componentSupportsUninstall(it) }.map { it.scriptName },
        )
    }

    @Test
    fun customizationScript_doesNotClobberManagedOrLinkedZshrc() {
        val text = repoFile("src/main/assets/scripts/common/setup/setup_customization_xfce.sh")
            .readText()
        assertFalse("must not truncate ~/.zshrc in place", text.contains("cat > \"\$ZSHRC\""))
        assertTrue(text.contains("grep -qF '# flux-zsh-plugins' \"\$ZSHRC\""))
        assertTrue(text.contains("[ -L \"\$ZSHRC\" ]"))
        assertTrue(text.contains("\$ZSHRC.flux-bak"))
        assertTrue(text.contains("mv -f \"\$ZSHRC_NEW\" \"\$ZSHRC\""))
    }

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile }
            ?: error("missing $rel (cwd=$cwd)")
    }
}
