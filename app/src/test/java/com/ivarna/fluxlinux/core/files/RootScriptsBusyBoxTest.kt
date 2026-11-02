package com.ivarna.fluxlinux.core.files

import java.io.File

/**
 * Runs every [RootScriptsTest] case with the scripts on BusyBox applets (`$X stat …`), as on a
 * device where [ChrootFilesBusyBox] resolved one. The search path holds only an `sh` wrapper,
 * so any command the scripts forgot to route through BusyBox fails with "not found".
 */
class RootScriptsBusyBoxTest : RootScriptsTest() {
    override val busybox: String = "/usr/bin/busybox"

    override fun searchPath(tmp: File): String {
        val bin = File(tmp, "only-sh").apply { mkdirs() }
        File(bin, "sh").apply {
            writeText("#!/bin/sh\nexec /bin/sh \"\$@\"\n")
            setExecutable(true)
        }
        return bin.path
    }
}
