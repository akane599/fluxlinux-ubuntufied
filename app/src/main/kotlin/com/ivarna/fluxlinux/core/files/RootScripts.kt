package com.ivarna.fluxlinux.core.files

import java.io.ByteArrayOutputStream

/** One `lstat` result from a root script; [stat] null = the entry does not exist. */
internal data class RootEntry(val path: String, val stat: GuestFs.Stat?, val linkTarget: String? = null)

/** Parsed output of a read script: mount points inside the rootfs (if reported) plus entries. */
internal data class RootSnapshot(val mounts: List<String>?, val entries: List<RootEntry>)

/** A root-side mutation. Every path is a resolved host path strictly inside the rootfs. */
internal sealed interface RootMutation {
    data class CreateFile(val path: String) : RootMutation
    data class Mkdir(val path: String) : RootMutation
    data class Delete(val path: String) : RootMutation
    data class Move(val from: String, val to: String) : RootMutation
    data class Copy(val from: String, val to: String) : RootMutation

    /** Phase 1 of a SAF write: stdin → new temp file (0600, never clobbers). */
    data class WriteTemp(val temp: String) : RootMutation

    /** Phase 2: give the temp file the target's (or parent dir's) owner and mode, then rename over the target. */
    data class CommitTemp(val temp: String, val target: String) : RootMutation
    data class AbortTemp(val temp: String) : RootMutation
    data class Append(val path: String) : RootMutation
    data class Read(val path: String) : RootMutation
}

/**
 * POSIX `sh` scripts run as root by [ShellRootOps], and parsers for their output. Pure: no
 * android.*, so JVM tests run the very same scripts against a temp dir with the host `sh`.
 *
 * Injection safety: every path goes through [q] (single quotes; `'` becomes `'\''`), which
 * is inert for every byte but NUL, and NUL never reaches here ([q] rejects it). Paths are
 * always absolute, so no operand can be read as an option; `--` is passed anyway.
 *
 * Every script re-checks, as root: the path is strictly inside the rootfs (`_inside`), no
 * directory on the way is a symlink (`_nolink`, TOCTOU guard on top of [GuestTree]'s walk),
 * and no filesystem mounted inside the rootfs (`/proc`, `/sys`, `/dev`, the `/sdcard` bind
 * the chroot helper adds) is at, above or below the path (`_guard`).
 *
 * Output framing never depends on names being free of newlines: listing records are
 * `./NAME/FIELDS\n` (a name cannot contain `/`), and link targets / sections are NUL-framed.
 *
 * Commands used (all as `$X <applet>`, i.e. the resolved BusyBox when known, else toybox):
 * `stat -c` with `%f %s %Y %u %g %n %a`, `find . -mindepth 1 -maxdepth 1 [-type l] -exec … {} +`,
 * `printf '\0'` / `'%s\0'`, plain `readlink` (no -f), `cat`, `cp -a`, `mv`, `mv -f`, `rm -f`,
 * `rm -rf`, `rmdir`, `mkdir`, `chown UID:GID`, `chmod OCTAL`. No `-print0`, `readlink -f`,
 * `mv -T` or GNU-only flags. Shell: POSIX `sh` (mksh on Android) builtins only otherwise.
 */
internal class RootScripts(
    private val rootfs: String,
    /** Command search path inside the root shell (Android toybox by default; tests use the host's). */
    private val searchPath: String = ANDROID_PATH,
    /** Mount table read by the guards (tests point it at a fixture). */
    private val mountsFile: String = "/proc/self/mounts",
    /**
     * Resolved root BusyBox ([com.ivarna.fluxlinux.core.root.RootShell.resolveBusyBox]). When set,
     * every external command runs as a BusyBox applet (`$X stat …`); otherwise the toybox applets
     * on [searchPath]. Ignored unless it is a plain absolute path (it is expanded unquoted).
     */
    busybox: String? = null,
) {
    private val busybox: String? = busybox?.takeIf { SAFE_BUSYBOX_PATH.matches(it) }

    fun chain(base: String, names: List<String>): String = buildString {
        append(header())
        append("B=").append(q(base)).append('\n')
        append(
            """
            |{ [ "${'$'}B" = "${'$'}R" ] || _inside "${'$'}B"; } || exit 34
            |[ -d "${'$'}R" ] && [ ! -L "${'$'}R" ] || exit 3
            |_mnt || exit 6
            |${'$'}X printf '\0'
            |[ -d "${'$'}B" ] && [ ! -L "${'$'}B" ] || exit 0
            |_p=${'$'}B
            |for _n in ${names.joinToString(" ") { q(it) }}; do
            |  _p=${'$'}_p/${'$'}_n
            |  if [ ! -e "${'$'}_p" ] && [ ! -L "${'$'}_p" ]; then ${'$'}X printf '%s\0' -; exit 0; fi
            |  ${'$'}X stat -c '$STAT_FMT' -- "${'$'}_p" || exit 4
            |  ${'$'}X printf '\0'
            |  if [ -L "${'$'}_p" ]; then ${'$'}X readlink -- "${'$'}_p"; ${'$'}X printf '\0'; exit 0; fi
            |  [ -d "${'$'}_p" ] || exit 0
            |done
            |exit 0
            |
            """.trimMargin(),
        )
    }

    fun list(dir: String): String = buildString {
        append(header())
        append("D=").append(q(dir)).append('\n')
        append(
            """
            |{ [ "${'$'}D" = "${'$'}R" ] || _inside "${'$'}D"; } || exit 34
            |[ -d "${'$'}D" ] && [ ! -L "${'$'}D" ] || exit 3
            |_mnt || exit 6
            |${'$'}X printf '\0'
            |cd "${'$'}D" || exit 3
            |${'$'}X find . -mindepth 1 -maxdepth 1 -exec ${'$'}X stat -c '%n/$STAT_FMT' {} +
            |[ ${'$'}? -le 1 ] || exit 4
            |${'$'}X printf '\0'
            |${'$'}X find . -mindepth 1 -maxdepth 1 -type l -exec sh -c 'X=${'$'}1; shift; for l; do ${'$'}X printf "%s\0" "${'$'}l"; ${'$'}X readlink -- "${'$'}l"; ${'$'}X printf "\0"; done' sh "${'$'}X" {} +
            |[ ${'$'}? -le 1 ] || exit 5
            |exit 0
            |
            """.trimMargin(),
        )
    }

    fun mounts(): String = header() + "_mnt || exit 6\nexit 0\n"

    fun mutation(m: RootMutation): String = buildString {
        append(header())
        when (m) {
            is RootMutation.CreateFile -> {
                entryChecks(m.path)
                append("{ [ -e \"\$P\" ] || [ -L \"\$P\" ]; } && exit 17\n")
                append("set -C\n: > \"\$P\" || exit 32\nset +C\n")
                append("_o=\$(\$X stat -c %u:%g -- \"\$D\") && \$X chown \"\$_o\" \"\$P\" || { \$X rm -f -- \"\$P\"; exit 33; }\n")
            }
            is RootMutation.Mkdir -> {
                entryChecks(m.path)
                append("{ [ -e \"\$P\" ] || [ -L \"\$P\" ]; } && exit 17\n")
                append("\$X mkdir -- \"\$P\" || exit 32\n")
                append("_o=\$(\$X stat -c %u:%g -- \"\$D\") && \$X chown \"\$_o\" \"\$P\" || { \$X rmdir -- \"\$P\"; exit 33; }\n")
            }
            is RootMutation.Delete -> {
                entryChecks(m.path)
                append("{ [ -e \"\$P\" ] || [ -L \"\$P\" ]; } || exit 2\n")
                // rm -r never follows links; _guard proved no mount lies inside.
                append("\$X rm -rf -- \"\$P\" || exit 33\n")
            }
            is RootMutation.Move -> {
                entryChecks(m.from)
                append("F=\$P\n")
                entryChecks(m.to)
                append("{ [ -e \"\$F\" ] || [ -L \"\$F\" ]; } || exit 2\n")
                append("{ [ -e \"\$P\" ] || [ -L \"\$P\" ]; } && exit 17\n")
                append("\$X mv -- \"\$F\" \"\$P\" || exit 33\n")
            }
            is RootMutation.Copy -> {
                entryChecks(m.from)
                append("F=\$P\n")
                entryChecks(m.to)
                append("{ [ -e \"\$F\" ] || [ -L \"\$F\" ]; } || exit 2\n")
                append("{ [ -e \"\$P\" ] || [ -L \"\$P\" ]; } && exit 17\n")
                // -a = -dpR: links are copied as links, never followed.
                // Past this marker the destination is ours: a failed copy may remove exactly it.
                append("\$X printf '%s\\n' $COPY_STARTED\n")
                append("\$X cp -a -- \"\$F\" \"\$P\" || exit 33\n")
            }
            is RootMutation.WriteTemp -> {
                entryChecks(m.temp)
                append("umask 077\nset -C\n")
                // Noclobber open first: a failure here must not remove a file we did not create.
                append("exec 3> \"\$P\" || exit 17\n")
                append("\$X cat >&3 || { \$X rm -f -- \"\$P\"; exit 32; }\n")
                append("exec 3>&-\n")
            }
            is RootMutation.CommitTemp -> {
                entryChecks(m.temp)
                append("N=\$P\n")
                entryChecks(m.target)
                append(
                    """
                    |[ -f "${'$'}N" ] && [ ! -L "${'$'}N" ] || exit 2
                    |[ -L "${'$'}P" ] && { ${'$'}X rm -f -- "${'$'}N"; exit 35; }
                    |if [ -e "${'$'}P" ]; then
                    |  [ -f "${'$'}P" ] || { ${'$'}X rm -f -- "${'$'}N"; exit 36; }
                    |  _o=${'$'}(${'$'}X stat -c %u:%g -- "${'$'}P") && _m=${'$'}(${'$'}X stat -c %a -- "${'$'}P") || { ${'$'}X rm -f -- "${'$'}N"; exit 33; }
                    |else
                    |  _o=${'$'}(${'$'}X stat -c %u:%g -- "${'$'}D") || { ${'$'}X rm -f -- "${'$'}N"; exit 33; }
                    |  _m=644
                    |fi
                    |${'$'}X chown "${'$'}_o" "${'$'}N" && ${'$'}X chmod "${'$'}_m" "${'$'}N" && ${'$'}X mv -f -- "${'$'}N" "${'$'}P" || { ${'$'}X rm -f -- "${'$'}N"; exit 37; }
                    |
                    """.trimMargin(),
                )
            }
            is RootMutation.AbortTemp -> {
                append("P=").append(q(m.temp)).append('\n')
                append("_inside \"\$P\" || exit 34\n")
                append("[ -L \"\$P\" ] || [ -f \"\$P\" ] || exit 0\n")
                append("\$X rm -f -- \"\$P\"\n")
            }
            is RootMutation.Append -> {
                entryChecks(m.path)
                append("[ -f \"\$P\" ] && [ ! -L \"\$P\" ] || exit 36\n")
                append("\$X cat >> \"\$P\" || exit 32\n")
            }
            is RootMutation.Read -> {
                entryChecks(m.path)
                append("[ -f \"\$P\" ] && [ ! -L \"\$P\" ] || exit 36\n")
                append("exec \$X cat -- \"\$P\"\n")
            }
        }
        append("exit 0\n")
    }

    /** Sets `P` (entry) and `D` (its parent) and refuses outside/linked/mounted paths. */
    private fun StringBuilder.entryChecks(path: String) {
        val parent = HostPaths.parent(path) ?: "/"
        append("P=").append(q(path)).append("; D=").append(q(parent)).append('\n')
        append("_inside \"\$P\" || exit 34\n")
        val prefixes = HostPaths.prefixes(rootfs, parent)
        if (prefixes.isEmpty()) {
            append("exit 34\n")
        } else {
            append("_nolink ").append(prefixes.joinToString(" ") { q(it) }).append(" || exit 31\n")
        }
        append("_guard ").append(q(mountEscape(path))).append(" || exit 30\n")
    }

    private fun header(): String = buildString {
        append("export PATH=").append(q(searchPath)).append('\n')
        append("X=").append(busybox?.let(::q) ?: "").append('\n')
        append("umask 022\n")
        append("R=").append(q(rootfs)).append("; ER=").append(q(mountEscape(rootfs)))
        append("; MT=").append(q(mountsFile)).append('\n')
        append(FUNCTIONS)
    }

    companion object {
        const val ANDROID_PATH = "/system/bin:/system/xbin"

        /** Printed by the Copy script right before `cp`, after every refusal check passed. */
        const val COPY_STARTED = "__FLUX_COPY_STARTED__"

        private val SAFE_BUSYBOX_PATH = Regex("^/[A-Za-z0-9._+/-]+$")

        /** Raw mode (hex) : size : mtime (s) : uid : gid — no `/`, no newline. */
        const val STAT_FMT = "%f:%s:%Y:%u:%g"

        /**
         * `_mnt`: mount points strictly inside the rootfs, one per line, kernel-escaped.
         * `_guard ESC`: false when a mount inside the rootfs is at/above ESC or below it.
         * `_inside P`: P strictly inside the rootfs. `_nolink DIR…`: every DIR is a real dir.
         * Both mount helpers fail closed when the mount table cannot be read.
         */
        private val FUNCTIONS = """
            |_mnt() {
            |  [ -r "${'$'}MT" ] || return 1
            |  while read -r _d _m _r; do
            |    case "${'$'}_m" in "${'$'}ER"/*) ${'$'}X printf '%s\n' "${'$'}_m" ;; esac
            |  done < "${'$'}MT"
            |  return 0
            |}
            |_guard() {
            |  [ -r "${'$'}MT" ] || return 1
            |  while read -r _d _m _r; do
            |    case "${'$'}_m" in "${'$'}ER"/*)
            |      case "${'$'}1" in "${'$'}_m"|"${'$'}_m"/*) return 1 ;; esac
            |      case "${'$'}_m" in "${'$'}1"/*) return 1 ;; esac
            |    ;; esac
            |  done < "${'$'}MT"
            |  return 0
            |}
            |_inside() { case "${'$'}1" in "${'$'}R"/?*) return 0 ;; esac; return 1; }
            |_nolink() { for _x; do [ -L "${'$'}_x" ] && return 1; [ -d "${'$'}_x" ] || return 1; done; return 0; }
            |
            """.trimMargin()

        /** Single-quoted shell word for any string without NUL. */
        fun q(s: String): String {
            require('\u0000' !in s) { "NUL in shell argument" }
            return "'" + s.replace("'", "'\\''") + "'"
        }

        /** Escapes a path the way the kernel prints mount points (`\040` space, `\011`, `\012`, `\134`). */
        fun mountEscape(path: String): String = buildString {
            for (c in path) {
                when (c) {
                    ' ' -> append("\\040")
                    '\t' -> append("\\011")
                    '\n' -> append("\\012")
                    '\\' -> append("\\134")
                    else -> append(c)
                }
            }
        }

        fun mountUnescape(s: String): String {
            val out = ByteArrayOutputStream()
            val bytes = s.toByteArray(Charsets.UTF_8)
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i]
                if (b == '\\'.code.toByte() && i + 3 < bytes.size &&
                    (1..3).all { k -> bytes[i + k] in '0'.code.toByte()..'7'.code.toByte() }
                ) {
                    out.write(((bytes[i + 1] - 48) shl 6) or ((bytes[i + 2] - 48) shl 3) or (bytes[i + 3] - 48))
                    i += 4
                } else {
                    out.write(b.toInt())
                    i++
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }

        // ---- parsers ------------------------------------------------------------------

        private const val NUL: Byte = 0
        private const val NL: Byte = '\n'.code.toByte()
        private const val SLASH: Byte = '/'.code.toByte()

        /** Parses `hexmode:size:mtime:uid:gid`. */
        fun parseStat(fields: String): GuestFs.Stat? {
            val f = fields.trim().split(':')
            if (f.size != 5) return null
            val mode = f[0].toIntOrNull(16) ?: return null
            val type = when (mode and 0xF000) {
                0x4000 -> GuestFs.Type.DIR
                0x8000 -> GuestFs.Type.FILE
                0xA000 -> GuestFs.Type.LINK
                else -> GuestFs.Type.OTHER
            }
            return GuestFs.Stat(
                type = type,
                size = f[1].toLongOrNull() ?: return null,
                mtimeMillis = (f[2].substringBefore('.').toLongOrNull() ?: return null) * 1000L,
                mode = mode,
                uid = f[3].toIntOrNull() ?: return null,
                gid = f[4].toIntOrNull() ?: return null,
            )
        }

        private fun splitNul(out: ByteArray): List<ByteArray> {
            val parts = mutableListOf<ByteArray>()
            var start = 0
            for (i in out.indices) {
                if (out[i] == NUL) {
                    parts += out.copyOfRange(start, i)
                    start = i + 1
                }
            }
            if (start < out.size) parts += out.copyOfRange(start, out.size)
            return parts
        }

        private fun parseMounts(block: ByteArray): List<String> =
            String(block, Charsets.UTF_8).lines().filter { it.isNotEmpty() }.map(::mountUnescape)

        /** Strict UTF-8 decode; null when the bytes are not valid UTF-8 (such names cannot round-trip a doc id). */
        private fun utf8(bytes: ByteArray): String? {
            val s = String(bytes, Charsets.UTF_8)
            return if (s.toByteArray(Charsets.UTF_8).contentEquals(bytes)) s else null
        }

        /** Link target as printed by `readlink` (exactly one trailing newline removed); null when empty/invalid. */
        private fun target(bytes: ByteArray): String? {
            val t = utf8(bytes) ?: return null
            return t.removeSuffix("\n").takeIf { it.isNotEmpty() }
        }

        fun parseMountsOutput(out: ByteArray): List<String> = parseMounts(out)

        fun parseChain(base: String, names: List<String>, out: ByteArray): RootSnapshot {
            val parts = splitNul(out)
            if (parts.isEmpty()) return RootSnapshot(null, emptyList())
            val mounts = parseMounts(parts[0])
            val entries = mutableListOf<RootEntry>()
            var idx = 1
            var path = base
            for (name in names) {
                val rec = parts.getOrNull(idx++) ?: break
                path = HostPaths.child(path, name)
                if (String(rec, Charsets.UTF_8) == "-") {
                    entries += RootEntry(path, null)
                    break
                }
                val stat = parseStat(String(rec, Charsets.UTF_8)) ?: break
                if (stat.type == GuestFs.Type.LINK) {
                    entries += RootEntry(path, stat, parts.getOrNull(idx++)?.let(::target))
                    break
                }
                entries += RootEntry(path, stat)
                if (stat.type != GuestFs.Type.DIR) break
            }
            return RootSnapshot(mounts, entries)
        }

        /** Entries of [dir] in listing order; names that are not valid UTF-8 are skipped. */
        fun parseList(dir: String, out: ByteArray): RootSnapshot {
            val first = out.indexOf(NUL)
            if (first < 0) return RootSnapshot(null, emptyList())
            val mounts = parseMounts(out.copyOfRange(0, first))
            val secondRel = out.copyOfRange(first + 1, out.size).indexOf(NUL)
            val statEnd = if (secondRel < 0) out.size else first + 1 + secondRel
            val stats = LinkedHashMap<String, GuestFs.Stat>()
            var i = first + 1
            // Records: "./" NAME "/" FIELDS "\n" — NAME has no '/', FIELDS have no '/' or '\n'.
            while (i + 2 < statEnd && out[i] == '.'.code.toByte() && out[i + 1] == SLASH) {
                var slash = i + 2
                while (slash < statEnd && out[slash] != SLASH) slash++
                var nl = slash + 1
                while (nl < statEnd && out[nl] != NL) nl++
                if (slash >= statEnd || nl > statEnd) break
                val name = utf8(out.copyOfRange(i + 2, slash))
                val stat = parseStat(String(out, slash + 1, nl - slash - 1, Charsets.UTF_8))
                if (name != null && stat != null && GuestDocumentPaths.isValidName(name)) stats[name] = stat
                i = nl + 1
            }
            val links = HashMap<String, String>()
            if (secondRel >= 0) {
                val pairs = splitNul(out.copyOfRange(statEnd + 1, out.size))
                var k = 0
                while (k + 1 < pairs.size) {
                    val rawName = pairs[k]
                    val name = if (rawName.size > 2 && rawName[0] == '.'.code.toByte() && rawName[1] == SLASH) {
                        utf8(rawName.copyOfRange(2, rawName.size))
                    } else {
                        null
                    }
                    val tgt = target(pairs[k + 1])
                    if (name != null && tgt != null) links[name] = tgt
                    k += 2
                }
            }
            val entries = stats.map { (name, stat) ->
                RootEntry(HostPaths.child(dir, name), stat, if (stat.type == GuestFs.Type.LINK) links[name] else null)
            }
            return RootSnapshot(mounts, entries)
        }

        private fun ByteArray.indexOf(b: Byte): Int {
            for (i in indices) if (this[i] == b) return i
            return -1
        }
    }
}
