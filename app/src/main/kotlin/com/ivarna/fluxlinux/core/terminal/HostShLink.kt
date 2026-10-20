package com.ivarna.fluxlinux.core.terminal

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Ensures the host prefix has `bin/sh`, the shebang interpreter of the Termux wrappers
 * (`am`, `su`, `getprop`, `logcat`, `termux-open`, ...). Without it termux-exec runs
 * `linker64 $PREFIX/bin/sh` and the wrapper dies with `unable to open file`.
 *
 * New bootstraps ship the same `sh -> bash` link (scripts/assemble_bootstrap.py); this
 * repairs prefixes extracted before that, since an app update never re-extracts.
 * An existing `sh` entry, even a dangling link, is never replaced.
 */
internal object HostShLink {

    enum class Result { CREATED, PRESENT, NO_BASH, FAILED }

    fun ensure(binDir: File): Result {
        val sh = File(binDir, "sh").toPath()
        if (Files.exists(sh, LinkOption.NOFOLLOW_LINKS)) return Result.PRESENT
        if (!File(binDir, "bash").isFile) return Result.NO_BASH
        return try {
            Files.createSymbolicLink(sh, Paths.get("bash"))
            Result.CREATED
        } catch (_: FileAlreadyExistsException) {
            Result.PRESENT
        } catch (_: IOException) {
            Result.FAILED
        } catch (_: UnsupportedOperationException) {
            Result.FAILED
        }
    }
}
