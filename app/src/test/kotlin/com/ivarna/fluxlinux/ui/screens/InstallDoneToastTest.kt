package com.ivarna.fluxlinux.ui.screens

import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.install.InstallKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The install wizard's one success toast says "with a warning" instead of "complete" when it has one. */
class InstallDoneToastTest {

    @Test
    fun cleanRun_namesWhatWasSetUp() {
        assertEquals(R.string.install_done_terminal, installDoneToastRes(InstallKind.TERMINAL, null))
        assertEquals(
            R.string.install_done_terminal_desktop,
            installDoneToastRes(InstallKind.TERMINAL_AND_DESKTOP, null),
        )
        assertEquals(R.string.install_done_terminal_desktop, installDoneToastRes(InstallKind.DESKTOP_ADD_ON, null))
    }

    @Test
    fun runWithAWarning_saysSo() {
        assertEquals(R.string.install_done_terminal_warning, installDoneToastRes(InstallKind.TERMINAL, "w"))
        assertEquals(
            R.string.install_done_terminal_desktop_warning,
            installDoneToastRes(InstallKind.TERMINAL_AND_DESKTOP, "w"),
        )
        assertEquals(
            R.string.install_done_terminal_desktop_warning,
            installDoneToastRes(InstallKind.DESKTOP_ADD_ON, "w"),
        )
    }
}
