package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.core.root.GuestUsers
import com.ivarna.fluxlinux.core.root.RootShell
import java.util.Base64

/**
 * [ZshPluginSet.upgradeZshrcFile] for a chroot guest, whose rootfs the app uid cannot read.
 *
 * The file is read and written by root **inside** the chroot (the helper's `b64` mode), so a
 * guest symlink can never redirect the write to a host path. Same contract as the PRoot
 * upgrade: missing or symlinked `.zshrc` / `.zshrc.flux-bak` are skipped (SKIPPED, so
 * retried later and never marked done), the original is kept as `.zshrc.flux-bak` (owner
 * and mode preserved), the new file gets the original's owner and mode and replaces it by
 * rename, and nothing is written when the file changed since it was
 * read (SHA-256 re-check).
 */
internal object ChrootZshrcUpgrade {

    private const val TAG = "ChrootZshrcUpgrade"
    private const val TIMEOUT_MS = 30_000L

    internal const val SKIP = "FLUX_ZSHRC_SKIP"
    internal const val BEGIN = "FLUX_ZSHRC_BEGIN"
    internal const val END = "FLUX_ZSHRC_END"
    internal const val WRITTEN = "FLUX_ZSHRC_WRITTEN"

    internal sealed interface ReadResult {
        /** Missing, symlinked or not a regular file: never touch it, check again later. */
        data object Skip : ReadResult

        data class Text(val text: String) : ReadResult

        /** su failed, timed out or the output was not understood: retry later. */
        data object Failed : ReadResult
    }

    /** Blocking (su): background thread only. */
    fun run(ctx: Context, chrootPath: String, user: String): ZshPluginSet.UpgradeResult {
        require(GuestUsers.isValidName(user) && user != GuestUsers.ROOT) { "invalid user: $user" }
        if (!RootShell.isRootAvailable()) return ZshPluginSet.UpgradeResult.SKIPPED
        val home = GuestUsers.homeOf(user)
        val read = RootShell.captureInChroot(
            readScript(home), user = GuestUsers.ROOT, chrootPath = chrootPath,
            timeoutMs = TIMEOUT_MS, context = ctx,
        )
        val text = when (val r = parseRead(read.exitCode, read.stdout)) {
            // Same as the PRoot upgrade: not checked, so not marked done. A home that is not
            // there yet (user still flux, rename pending) is retried on the next session.
            ReadResult.Skip -> return ZshPluginSet.UpgradeResult.SKIPPED
            ReadResult.Failed -> return ZshPluginSet.UpgradeResult.SKIPPED
            is ReadResult.Text -> r.text
        }
        val upgraded = ZshPluginSet.upgradeManagedZshrc(text)
            ?: return ZshPluginSet.UpgradeResult.UNCHANGED
        val write = RootShell.captureInChroot(
            writeScript(home, RootShell.sha256Hex(text.toByteArray(Charsets.UTF_8)), upgraded),
            user = GuestUsers.ROOT, chrootPath = chrootPath,
            timeoutMs = TIMEOUT_MS, context = ctx,
        )
        return if (write.exitCode == 0 && write.stdout.lineSequence().any { it.trim() == WRITTEN }) {
            Log.i(TAG, "Upgraded zsh plugin set in $chrootPath$home/.zshrc")
            ZshPluginSet.UpgradeResult.UPGRADED
        } else {
            Log.w(TAG, "chroot .zshrc write skipped exit=${write.exitCode}")
            ZshPluginSet.UpgradeResult.SKIPPED
        }
    }

    /** Guest root script printing [home]`/.zshrc` as one base64 line between markers. */
    internal fun readScript(home: String): String {
        val f = "$home/.zshrc"
        return "f='$f'; b='$f${ZshPluginSet.BACKUP_SUFFIX}'; " +
            "if [ -L \"\$f\" ] || [ -L \"\$b\" ] || [ ! -f \"\$f\" ]; then echo $SKIP; exit 0; fi; " +
            "echo $BEGIN; base64 \"\$f\" | tr -d '\\n'; echo; echo $END"
    }

    /**
     * Guest root script replacing [home]`/.zshrc` with [newText] when it still hashes to
     * [originalSha]: backup with `cp -p`, temp file with the original's owner/mode, rename.
     */
    internal fun writeScript(home: String, originalSha: String, newText: String): String {
        val f = "$home/.zshrc"
        val b64 = Base64.getEncoder().encodeToString(newText.toByteArray(Charsets.UTF_8))
        return "f='$f'; b='$f${ZshPluginSet.BACKUP_SUFFIX}'; n=\"\$f.flux-new.\$\$\"; " +
            "if [ -L \"\$f\" ] || [ -L \"\$b\" ] || [ ! -f \"\$f\" ]; then exit 3; fi; " +
            "[ \"\$(sha256sum < \"\$f\" | cut -c1-64)\" = '$originalSha' ] || exit 4; " +
            "rm -f \"\$n\"; cp -p \"\$f\" \"\$b\" || exit 5; " +
            "printf '%s' '$b64' | base64 -d > \"\$n\" && " +
            "chown --reference=\"\$f\" \"\$n\" && chmod --reference=\"\$f\" \"\$n\" && " +
            "mv -f \"\$n\" \"\$f\" || { rm -f \"\$n\"; exit 6; }; echo $WRITTEN"
    }

    internal fun parseRead(exitCode: Int, stdout: String): ReadResult {
        if (exitCode != 0) return ReadResult.Failed
        val lines = stdout.lines().map { it.trim() }
        if (lines.any { it == SKIP }) return ReadResult.Skip
        val begin = lines.indexOf(BEGIN)
        val end = lines.indexOf(END)
        if (begin < 0 || end <= begin) return ReadResult.Failed
        val b64 = lines.subList(begin + 1, end).joinToString("")
        return try {
            ReadResult.Text(String(Base64.getDecoder().decode(b64), Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            ReadResult.Failed
        }
    }
}
