package com.ivarna.fluxlinux.core.terminal

import java.io.File
import java.nio.file.Files

/**
 * Single source of truth for the Ubuntu oh-my-zsh plugin set: the plugin
 * order, where non-bundled plugins come from, which guest packages they need,
 * and the managed `.zshrc` block that loads them.
 *
 * Used by [GuestZshrcRepair] (defensive profile + upgrade of existing installs)
 * and `ProotZshBootstrap` (host-side clones + lazy guest packages). The guest
 * script `scripts/common/setup/setup_customization_xfce.sh` carries a copy of
 * [managedBlock] for new installs; `ZshPluginSetTest` fails when they drift.
 *
 * The block builds `plugins` at shell start-up and only lists a plugin whose
 * directory exists and whose tool is installed, so a partial (offline) install
 * never prints oh-my-zsh "plugin not found" / "zoxide not found" at each prompt.
 */
object ZshPluginSet {

    /** Load order matters: fzf-tab before autosuggestions/syntax-highlighting, history-substring-search last. */
    val PLUGINS: List<String> = listOf(
        "git",
        "extract",
        "colored-man-pages",
        "copypath",
        "copyfile",
        "command-not-found",
        "fzf",
        "zoxide",
        "fzf-tab",
        "zsh-autosuggestions",
        "zsh-syntax-highlighting",
        "history-substring-search",
    )

    /** A git repository fetched at exactly [commit] (full SHA-1), never a moving branch. */
    data class GitPin(val url: String, val commit: String)

    /*
     * Pinned upstream commits (default-branch HEAD, looked up 2026-10-20). The
     * guest customization scripts carry the same values as shell variables
     * (OMZ_SHA, ZSH_AUTOSUGGEST_SHA, …); CustomizationScriptPinsContractTest
     * fails when they drift. Bump deliberately after reviewing the upstream diff.
     */
    val OMZ = GitPin(
        "https://github.com/ohmyzsh/ohmyzsh.git",
        "0650a250405af1e480b1ecf8c33bc960b9f333f5",
    )

    /**
     * Plugins cloned into `$ZSH_CUSTOM/plugins`. fzf-tab is not bundled with
     * oh-my-zsh. Recent oh-my-zsh bundles autosuggestions / syntax-highlighting,
     * but older trees on existing installs do not, so Flux keeps cloning them.
     */
    val EXTERNAL_PLUGINS: Map<String, GitPin> = linkedMapOf(
        "zsh-autosuggestions" to GitPin(
            "https://github.com/zsh-users/zsh-autosuggestions.git",
            "85919cd1ffa7d2d5412f6d3fe437ebdbeeec4fc5",
        ),
        "zsh-syntax-highlighting" to GitPin(
            "https://github.com/zsh-users/zsh-syntax-highlighting.git",
            "e6683f20e5c38d3e5447f45845d04ddda75d0f23",
        ),
        "fzf-tab" to GitPin(
            "https://github.com/Aloxaf/fzf-tab.git",
            "24105b15714bfec37989ed5c5b6e60f572253019",
        ),
    )

    /** agnosterzak theme: raw file at a pinned commit, accepted only on this sha256. */
    const val AGNOSTERZAK_COMMIT = "312f93f00b199d6c2506221f8366a7f216023100"
    const val AGNOSTERZAK_SHA256 = "b03b3de1a172097b0cf2695d705c1018b772ff4543562e624c972a9e5070dee2"
    const val AGNOSTERZAK_URL =
        "https://raw.githubusercontent.com/zakaziko99/agnosterzak-ohmyzsh-theme/" +
            "$AGNOSTERZAK_COMMIT/agnosterzak.zsh-theme"

    /** Plugins only the Ubuntu set adds; other distros keep the legacy two. */
    private val UBUNTU_ONLY_EXTERNAL = setOf("fzf-tab")

    /**
     * Guest apt packages (best-effort) mapped to the rootfs-relative path that
     * proves the package is installed. Mirrored in setup_customization_xfce.sh.
     */
    val APT_PACKAGES: Map<String, String> = linkedMapOf(
        "fzf" to "usr/bin/fzf",
        "zoxide" to "usr/bin/zoxide",
        "command-not-found" to "usr/lib/command-not-found",
    )

    /** Present in every block Flux writes; its presence means "already upgraded". */
    const val MARKER = "# flux-zsh-plugins v1"

    /** The Flux-managed default line every older profile shipped. */
    const val LEGACY_PLUGINS_LINE = "plugins=(git zsh-autosuggestions zsh-syntax-highlighting)"

    const val OMZ_SOURCE_LINE = "source \"\$ZSH/oh-my-zsh.sh\""

    /** Pre-upgrade copy of the user's profile, next to it. */
    const val BACKUP_SUFFIX = ".flux-bak"

    /** One-shot SharedPreferences flag (in [GuestRepairCache.PREFS]) per distro card. */
    fun upgradeKey(id: String): String = "guest_zsh_plugins_v1_$id"

    /** Ubuntu proot (`ubuntu`) and chroot (`ubuntu_chroot`). */
    fun appliesTo(distroId: String?): Boolean =
        !distroId.isNullOrBlank() && GuestZshrcRepair.resolveProotName(distroId) == "ubuntu"

    /** Plugins to clone for [prootName]: Ubuntu gets fzf-tab on top of the legacy pair. */
    fun externalPluginsFor(prootName: String?): Map<String, GitPin> =
        if (appliesTo(prootName)) {
            EXTERNAL_PLUGINS
        } else {
            EXTERNAL_PLUGINS.filterKeys { it !in UBUNTU_ONLY_EXTERNAL }
        }

    /**
     * External plugins neither cloned into [customPlugins] nor bundled in
     * [bundledPlugins] (`$ZSH/plugins`).
     */
    fun missingExternalPlugins(
        customPlugins: File,
        bundledPlugins: File,
        prootName: String?,
    ): Map<String, GitPin> = externalPluginsFor(prootName).filterKeys { name ->
        val custom = File(customPlugins, name)
        val cloned = custom.isDirectory && custom.list()?.isNotEmpty() == true
        !cloned && !File(bundledPlugins, name).isDirectory
    }

    /** Apt packages whose probe path is absent from the guest [rootfs]. */
    fun missingAptPackages(rootfs: File): List<String> =
        APT_PACKAGES.filter { (_, probe) -> !File(rootfs, probe).exists() }.keys.toList()

    /**
     * Guest `sh` script (run as root) installing [packages] one by one so one
     * unavailable package never blocks the others; refreshes indexes once when
     * the first pass misses any. Contains no single quotes (host wraps it in '').
     */
    fun aptInstallScript(packages: List<String>): String {
        val list = packages.joinToString(" ")
        return "command -v apt-get >/dev/null 2>&1 || exit 0; " +
            "export DEBIAN_FRONTEND=noninteractive; " +
            "_flux_try() { _miss=; for _p in $list; do " +
            "apt-get -o DPkg::Lock::Timeout=60 install -y --no-install-recommends \"\$_p\" " +
            ">/dev/null 2>&1 || _miss=1; done; [ -z \"\$_miss\" ]; }; " +
            "_flux_try || { apt-get -o DPkg::Lock::Timeout=60 update >/dev/null 2>&1; _flux_try; }"
    }

    private val BLOCK_TEMPLATE = """
$MARKER: managed by FluxLinux (ZshPluginSet.kt). Edit freely; Flux never rewrites it.
_flux_zc="${'$'}{ZSH_CUSTOM:-${'$'}ZSH/custom}"
plugins=()
for _flux_p in ${PLUGINS.joinToString(" ")}; do
  [[ -d "${'$'}_flux_zc/plugins/${'$'}_flux_p" || -d "${'$'}ZSH/plugins/${'$'}_flux_p" ]] || continue
  case "${'$'}_flux_p" in
    fzf|fzf-tab) (( ${'$'}+commands[fzf] )) || continue ;;
    zoxide) (( ${'$'}+commands[zoxide] )) || continue ;;
    command-not-found) [[ -x /usr/lib/command-not-found || -x /usr/share/command-not-found/command-not-found ]] || continue ;;
  esac
  plugins+=("${'$'}_flux_p")
done
unset _flux_zc _flux_p
$OMZ_SOURCE_LINE
if (( ${'$'}+functions[history-substring-search-up] )); then
  bindkey '^[[A' history-substring-search-up
  bindkey '^[[B' history-substring-search-down
  bindkey '^[OA' history-substring-search-up
  bindkey '^[OB' history-substring-search-down
  if [[ -n "${'$'}{terminfo[kcuu1]}" ]]; then
    bindkey "${'$'}{terminfo[kcuu1]}" history-substring-search-up
  fi
  if [[ -n "${'$'}{terminfo[kcud1]}" ]]; then
    bindkey "${'$'}{terminfo[kcud1]}" history-substring-search-down
  fi
fi
""".trim()

    /**
     * Replacement for the legacy `plugins=(…)` + `source "$ZSH/oh-my-zsh.sh"`
     * pair inside the oh-my-zsh guard. Every line is prefixed with [indent];
     * ends with a newline.
     */
    fun managedBlock(indent: String = "  "): String =
        BLOCK_TEMPLATE.lines().joinToString("\n", postfix = "\n") { "$indent$it" }

    /**
     * Upgrade a profile that still carries the Flux default plugin line,
     * immediately followed by the oh-my-zsh source line. Returns null (leave
     * the file alone) when already upgraded, when the user changed the plugin
     * line, or when the line is not the single managed occurrence.
     */
    fun upgradeManagedZshrc(text: String): String? {
        if (text.contains(MARKER)) return null
        val lines = text.split("\n")
        val hits = lines.indices.filter { lines[it].trim() == LEGACY_PLUGINS_LINE }
        if (hits.size != 1) return null
        val start = hits.single()
        val sourceIdx = (start + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
            ?: return null
        if (lines[sourceIdx].trim() != OMZ_SOURCE_LINE) return null
        val indent = lines[start].takeWhile { it == ' ' || it == '\t' }
        val block = managedBlock(indent).removeSuffix("\n").split("\n")
        return (lines.subList(0, start) + block + lines.subList(sourceIdx + 1, lines.size))
            .joinToString("\n")
    }

    enum class UpgradeResult {
        /** Rewritten; the original is at `.zshrc.flux-bak`. */
        UPGRADED,

        /** Checked: already upgraded or customized — left untouched. */
        UNCHANGED,

        /** Could not check (missing, not writable, or a symlink) — retry later. */
        SKIPPED,
    }

    /**
     * Upgrade [zshrc] in place via [upgradeManagedZshrc], writing the original
     * to `<name>.flux-bak` first. Symlinks (dotfile managers) are never followed.
     */
    fun upgradeZshrcFile(zshrc: File): UpgradeResult {
        val backup = File(zshrc.parentFile, zshrc.name + BACKUP_SUFFIX)
        if (Files.isSymbolicLink(zshrc.toPath()) || Files.isSymbolicLink(backup.toPath())) {
            return UpgradeResult.SKIPPED
        }
        if (!zshrc.isFile || !zshrc.canWrite()) return UpgradeResult.SKIPPED
        val text = zshrc.readText()
        val upgraded = upgradeManagedZshrc(text) ?: return UpgradeResult.UNCHANGED
        backup.writeText(text)
        zshrc.writeText(upgraded)
        return UpgradeResult.UPGRADED
    }

    /** True when [zshrc] carries the Flux-managed block (not customized away). */
    fun isManaged(zshrc: File): Boolean =
        runCatching {
            !Files.isSymbolicLink(zshrc.toPath()) && zshrc.isFile && zshrc.readText().contains(MARKER)
        }.getOrDefault(false)
}
