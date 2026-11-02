package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Host-side, in-place rename of the Ubuntu PRoot guest user `flux` to `akane`
 * ([UbuntuRuntime.LEGACY_GUEST_USER] to [UbuntuRuntime.UBUNTU_GUEST_USER]).
 *
 * A PRoot rootfs is owned by the app uid, so plain java.nio edits it; the chroot install
 * (root-owned, created as `akane`) and every other distro are out of scope. Nothing here
 * walks, copies or follows into the guest home: it holds `~/storage/` links to shared
 * storage, and the home is moved with a single rename(2), which carries links as links.
 *
 * Steps, in this order, each idempotent:
 *  1. `home/flux` (real dir) is renamed to `home/akane` unless `home/akane` already exists
 *     (then both are left alone, no merge);
 *  2. the compat link `home/flux -> akane` is created when `home/flux` is gone, so absolute
 *     `/home/flux` paths in XFCE configs, desktop files and shell history keep resolving;
 *  3. per-user entries are renamed: `var/lib/AccountsService/users`, `var/spool/cron/crontabs`,
 *     `var/mail`;
 *  4. `etc/sudoers.d/` lines `flux ALL=...` (file `sudoers.d/flux` renamed too) and the
 *     `permit nopass flux` rule in `etc/doas.conf` are rewritten token-wise;
 *  5. account files, field-wise and atomically, in this order: `subuid`, `subgid`, `gshadow`,
 *     `group`, `shadow`, and `passwd` LAST. `passwd` is the commit point.
 *
 * State machine, keyed on `etc/passwd` (O = has a `flux:` entry, N = has an `akane:` entry):
 *  - !O, !N: not set up (or another layout): [Outcome.NOT_APPLICABLE], nothing touched.
 *  - O, N: ambiguous (someone created akane by hand): [Outcome.CONFLICT], nothing touched.
 *  - O, !N: not committed. Steps 1-5 run; every step skips work already done, so any crash
 *    (home moved, link missing, some account files rewritten) resumes on the next call. The
 *    group-rename decision reads `group`, which is written after `gshadow`, so a resumed run
 *    still renames the gshadow entry. A failed home move or account write aborts before the
 *    commit ([Outcome.FAILED]) and is retried next time.
 *  - !O, N: committed (or a fresh akane install). The only repair is akane's passwd home
 *    `/home/akane` missing while `home/flux` is still a real dir: step 1 and 2 run. A fresh
 *    install without `home/flux` is never given a compat link. Otherwise
 *    [Outcome.ALREADY_MIGRATED], a passwd read and two stats.
 *
 * Callers on different threads (desktop start, sessions, install) are serialised by one
 * process-wide lock, so a run never sees another run's half-written state or temp files.
 *
 * Never throws; failures are logged.
 */
object GuestUserMigration {

    private const val TAG = "GuestUserMigration"
    private const val TMP_SUFFIX = ".flux-migrate"
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)
    private val LOCK = Any()
    private val WS_SPLIT = Regex("""(?<=\s)(?=\S)|(?<=\S)(?=\s)""")

    /** Per-user entries (files or dirs) named after the user, relative to the rootfs. */
    private val PER_USER_DIRS = listOf("var/lib/AccountsService/users", "var/spool/cron/crontabs", "var/mail")

    internal enum class Outcome { NOT_APPLICABLE, ALREADY_MIGRATED, MIGRATED, CONFLICT, FAILED }

    /**
     * Migrates the Ubuntu PRoot install. Returns true when this call changed the rootfs;
     * false when not applicable (any id but [UbuntuRuntime.PROOT_ID], no rootfs, no `flux`),
     * already migrated, ambiguous, or failed.
     */
    fun migrateIfNeeded(ctx: Context, distroId: String): Boolean {
        if (distroId != UbuntuRuntime.PROOT_ID) return false
        return try {
            val prootName = DistroInstallProfile.forId(distroId)?.prootName?.ifBlank { null } ?: return false
            val rootfs = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/$prootName/rootfs").toPath()
            migrate(rootfs) == Outcome.MIGRATED
        } catch (e: Exception) {
            Log.w(TAG, "migration of $distroId failed: ${e.message}")
            false
        }
    }

    /** Testable core; see the class kdoc for the state machine. Never throws. */
    internal fun migrate(
        rootfs: Path,
        oldUser: String = UbuntuRuntime.LEGACY_GUEST_USER,
        newUser: String = UbuntuRuntime.UBUNTU_GUEST_USER,
    ): Outcome = synchronized(LOCK) {
        try {
            migrateOrThrow(rootfs, oldUser, newUser)
        } catch (e: Exception) {
            Log.w(TAG, "$oldUser -> $newUser failed in $rootfs: ${e.message}")
            Outcome.FAILED
        }
    }

    private fun migrateOrThrow(rootfs: Path, oldUser: String, newUser: String): Outcome {
        val etc = realDirOrNull(rootfs, "etc") ?: return Outcome.NOT_APPLICABLE
        val passwd = etc.resolve("passwd")
        if (!Files.isRegularFile(passwd, *NOFOLLOW)) return Outcome.NOT_APPLICABLE
        val entries = accountLines(readText(passwd)).map { it.split(':') }
        val old = entries.firstOrNull { it.size >= 7 && it[0] == oldUser }
        val new = entries.firstOrNull { it.size >= 7 && it[0] == newUser }
        return when {
            old != null && new != null -> {
                Log.w(TAG, "both $oldUser and $newUser exist in passwd; leaving the rootfs alone")
                Outcome.CONFLICT
            }
            old != null -> {
                migrateUncommitted(rootfs, etc, oldUser, newUser, primaryGid = old[3])
                Outcome.MIGRATED
            }
            new != null -> {
                if (new[5] == "/home/$newUser" && finishHome(rootfs, oldUser, newUser)) {
                    Outcome.MIGRATED
                } else {
                    Outcome.ALREADY_MIGRATED
                }
            }
            else -> Outcome.NOT_APPLICABLE
        }
    }

    /** Steps 1-5; throws (before the passwd commit) when the home move or an account write fails. */
    private fun migrateUncommitted(rootfs: Path, etc: Path, oldUser: String, newUser: String, primaryGid: String) {
        moveHome(rootfs, oldUser, newUser, required = true)
        for (dir in PER_USER_DIRS) renamePerUser(rootfs, dir, oldUser, newUser)
        runCatching { rewriteSudoers(etc, oldUser, newUser) }
            .onFailure { Log.w(TAG, "sudoers rewrite failed: ${it.message}") }
        runCatching { rewriteDoas(etc, oldUser, newUser) }
            .onFailure { Log.w(TAG, "doas.conf rewrite failed: ${it.message}") }

        val group = etc.resolve("group")
        val groupLines = if (Files.isRegularFile(group, *NOFOLLOW)) readText(group) else null
        val renameGroup = groupLines != null && shouldRenameGroup(groupLines, oldUser, newUser, primaryGid)

        rewriteIfPresent(etc.resolve("subuid")) { renameFirstField(it, oldUser, newUser, minFields = 3) }
        rewriteIfPresent(etc.resolve("subgid")) { renameFirstField(it, oldUser, newUser, minFields = 3) }
        rewriteIfPresent(etc.resolve("gshadow")) {
            rewriteGroupLike(it, oldUser, newUser, renameGroup, memberFields = intArrayOf(2, 3))
        }
        rewriteIfPresent(group) { rewriteGroupLike(it, oldUser, newUser, renameGroup, memberFields = intArrayOf(3)) }
        rewriteIfPresent(etc.resolve("shadow")) { renameFirstField(it, oldUser, newUser, minFields = 2) }
        rewriteIfPresent(etc.resolve("passwd")) { rewritePasswd(it, oldUser, newUser) }
        Log.i(TAG, "renamed guest user $oldUser to $newUser")
    }

    /** Committed-state repair: akane's home is missing while `home/flux` is still a real dir. */
    private fun finishHome(rootfs: Path, oldUser: String, newUser: String): Boolean {
        val home = realDirOrNull(rootfs, "home") ?: return false
        val oldHome = home.resolve(oldUser)
        if (Files.exists(home.resolve(newUser), *NOFOLLOW) || !Files.isDirectory(oldHome, *NOFOLLOW)) return false
        return moveHome(rootfs, oldUser, newUser, required = false)
    }

    /**
     * Steps 1 and 2. Returns true when it moved the home or created the link. With [required],
     * a failed move throws so the account files are not committed to a home that isn't there.
     */
    private fun moveHome(rootfs: Path, oldUser: String, newUser: String, required: Boolean): Boolean {
        val home = realDirOrNull(rootfs, "home") ?: return false
        val oldHome = home.resolve(oldUser)
        val newHome = home.resolve(newUser)
        var changed = false
        if (Files.isDirectory(oldHome, *NOFOLLOW)) {
            if (Files.exists(newHome, *NOFOLLOW)) {
                Log.w(TAG, "home/$newUser already exists; leaving home/$oldUser in place (no merge)")
            } else {
                try {
                    // rename(2): links inside (~/storage/...) move as links, nothing is traversed.
                    Files.move(oldHome, newHome, StandardCopyOption.ATOMIC_MOVE, LinkOption.NOFOLLOW_LINKS)
                    changed = true
                } catch (e: IOException) {
                    Log.w(TAG, "cannot move home/$oldUser: ${e.message}")
                    if (required) throw e
                    return false
                }
            }
        }
        if (!Files.exists(oldHome, *NOFOLLOW) && Files.isDirectory(newHome, *NOFOLLOW)) {
            try {
                Files.createSymbolicLink(oldHome, Paths.get(newUser))
                changed = true
            } catch (e: IOException) {
                Log.w(TAG, "cannot create compat link home/$oldUser: ${e.message}")
            }
        }
        return changed
    }

    private fun renamePerUser(rootfs: Path, rel: String, oldUser: String, newUser: String) {
        val dir = realDirOrNull(rootfs, rel) ?: return
        val from = dir.resolve(oldUser)
        val to = dir.resolve(newUser)
        if (!Files.exists(from, *NOFOLLOW) || Files.exists(to, *NOFOLLOW)) return
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            Log.w(TAG, "cannot rename $rel/$oldUser: ${e.message}")
        }
    }

    /** `flux ALL=(ALL) NOPASSWD:ALL` as flux_guest_common.sh writes it, in every sudoers.d file. */
    private fun rewriteSudoers(etc: Path, oldUser: String, newUser: String) {
        val dir = realDirOrNull(etc, "sudoers.d") ?: return
        val files = Files.newDirectoryStream(dir).use { s ->
            s.filter { Files.isRegularFile(it, *NOFOLLOW) && !it.fileName.toString().contains(TMP_SUFFIX) }
        }
        for (f in files) {
            rewriteIfPresent(f) { text ->
                mapLines(text) { line ->
                    if (line.trimStart().startsWith("#")) return@mapLines line
                    val parts = line.split(WS_SPLIT).toMutableList()
                    val first = parts.indexOfFirst { it.isNotBlank() }
                    if (first >= 0 && parts[first] == oldUser) parts[first] = newUser
                    parts.joinToString("")
                }
            }
        }
        val from = dir.resolve(oldUser)
        val to = dir.resolve(newUser)
        if (Files.isRegularFile(from, *NOFOLLOW) && !Files.exists(to, *NOFOLLOW)) {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, LinkOption.NOFOLLOW_LINKS)
        }
    }

    /** `permit|deny [options] flux [as target] [cmd ...]`: only the identity token is renamed. */
    private fun rewriteDoas(etc: Path, oldUser: String, newUser: String) {
        rewriteIfPresent(etc.resolve("doas.conf")) { text ->
            mapLines(text) { line ->
                val parts = line.split(WS_SPLIT).toMutableList()
                val words = parts.indices.filter { parts[it].isNotBlank() }
                if (words.isEmpty() || parts[words[0]] !in setOf("permit", "deny")) return@mapLines line
                for (i in words.drop(1)) {
                    val w = parts[i]
                    if (w == "as" || w == "cmd" || w.startsWith("#")) break
                    if (w == oldUser) {
                        parts[i] = newUser
                        break
                    }
                }
                parts.joinToString("")
            }
        }
    }

    /** True when the group named [oldUser] is the user's own primary group and [newUser] is free. */
    private fun shouldRenameGroup(text: String, oldUser: String, newUser: String, primaryGid: String): Boolean {
        val groups = accountLines(text).map { it.split(':') }.filter { it.size >= 4 }
        val own = groups.any { it[0] == oldUser && it[2] == primaryGid }
        if (own && groups.any { it[0] == newUser }) {
            Log.w(TAG, "group $newUser already exists; keeping group $oldUser")
            return false
        }
        return own
    }

    /** group (`name:pw:gid:members`) / gshadow (`name:pw:admins:members`). */
    internal fun rewriteGroupLike(
        text: String,
        oldUser: String,
        newUser: String,
        renameGroup: Boolean,
        memberFields: IntArray,
    ): String = mapLines(text) { line ->
        if (isComment(line)) return@mapLines line
        val f = line.split(':').toMutableList()
        if (f.size < 4) return@mapLines line
        if (renameGroup && f[0] == oldUser) f[0] = newUser
        for (i in memberFields) {
            if (f[i].isEmpty()) continue
            val members = f[i].split(',')
            if (oldUser in members) f[i] = members.map { if (it == oldUser) newUser else it }.distinct().joinToString(",")
        }
        f.joinToString(":")
    }

    /** passwd: name `flux` to `akane`, home `/home/flux` to `/home/akane` (exact match only). */
    internal fun rewritePasswd(text: String, oldUser: String, newUser: String): String = mapLines(text) { line ->
        if (isComment(line)) return@mapLines line
        val f = line.split(':').toMutableList()
        if (f.size < 7 || f[0] != oldUser) return@mapLines line
        f[0] = newUser
        if (f[5] == "/home/$oldUser") f[5] = "/home/$newUser"
        f.joinToString(":")
    }

    /** shadow / subuid / subgid: rename the first field, unless [newUser] already has an entry. */
    internal fun renameFirstField(text: String, oldUser: String, newUser: String, minFields: Int): String {
        if (accountLines(text).any { it.substringBefore(':') == newUser }) return text
        return mapLines(text) { line ->
            val f = line.split(':')
            if (isComment(line) || f.size < minFields || f[0] != oldUser) line else (listOf(newUser) + f.drop(1)).joinToString(":")
        }
    }

    private fun isComment(line: String) = line.isBlank() || line.trimStart().startsWith("#")

    private fun accountLines(text: String) = text.split('\n').filterNot { isComment(it) }

    /** Maps every line; the trailing newline (an empty last element) round-trips unchanged. */
    private fun mapLines(text: String, transform: (String) -> String): String =
        text.split('\n').joinToString("\n") { if (it.isEmpty()) it else transform(it) }

    private fun rewriteIfPresent(path: Path, transform: (String) -> String) {
        if (!Files.isRegularFile(path, *NOFOLLOW)) return
        val text = readText(path)
        val updated = transform(text)
        if (updated != text) atomicWrite(path, updated)
    }

    /**
     * Reads [path]; an owner-unreadable file (shadow can be 0000/0400 depending on the
     * rootfs) is made owner-readable, read, and given its mode back.
     */
    private fun readText(path: Path): String = try {
        String(Files.readAllBytes(path), Charsets.UTF_8)
    } catch (e: AccessDeniedException) {
        val perms = Files.getPosixFilePermissions(path, *NOFOLLOW)
        Files.setPosixFilePermissions(path, perms + PosixFilePermission.OWNER_READ)
        try {
            String(Files.readAllBytes(path), Charsets.UTF_8)
        } finally {
            Files.setPosixFilePermissions(path, perms)
        }
    }

    /**
     * Temp file in the same dir, fsynced, original mode, then an atomic rename over [path].
     * The temp name is unique per write (`.<name>.flux-migrate.<random>`, dotted so sudo
     * ignores it in sudoers.d); leftovers of an interrupted run are removed first, which is
     * safe because [LOCK] is held.
     */
    private fun atomicWrite(path: Path, text: String) {
        val perms = Files.getPosixFilePermissions(path, *NOFOLLOW)
        val tmpPrefix = ".${path.fileName}$TMP_SUFFIX."
        removeStaleTemps(path.parent, tmpPrefix)
        val tmp = path.resolveSibling(tmpPrefix + UUID.randomUUID().toString().substring(0, 8))
        try {
            FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
                val buf = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            Files.setPosixFilePermissions(tmp, perms)
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    private fun removeStaleTemps(dir: Path, prefix: String) {
        val stale = Files.newDirectoryStream(dir).use { s -> s.filter { it.fileName.toString().startsWith(prefix) } }
        for (f in stale) runCatching { Files.deleteIfExists(f) }
    }

    /** [root]/[rel] when every component below [root] is a real directory (never a symlink). */
    private fun realDirOrNull(root: Path, rel: String): Path? {
        var p = root
        for (part in rel.split('/')) {
            p = p.resolve(part)
            if (!Files.isDirectory(p, *NOFOLLOW)) return null
        }
        return p
    }
}
