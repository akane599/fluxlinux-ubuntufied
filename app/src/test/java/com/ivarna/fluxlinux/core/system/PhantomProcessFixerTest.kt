package com.ivarna.fluxlinux.core.system

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.Shizuku

class PhantomProcessFixerTest {

    private class FakeShizuku(private val failRequest: Boolean) {
        val listeners = mutableListOf<Shizuku.OnRequestPermissionResultListener>()
        val requested = mutableListOf<Int>()

        fun add(l: Shizuku.OnRequestPermissionResultListener) {
            listeners += l
        }

        fun remove(l: Shizuku.OnRequestPermissionResultListener) {
            listeners -= l
        }

        fun request(code: Int) {
            if (failRequest) throw IllegalStateException("binder haven't been received")
            requested += code
        }
    }

    @Test
    fun requestThatThrows_removesListenerAndReportsDenied() {
        val fake = FakeShizuku(failRequest = true)
        val results = mutableListOf<Boolean>()
        PhantomProcessFixer.requestShizukuWith(fake::add, fake::remove, fake::request) { results += it }
        assertEquals(listOf(false), results)
        assertTrue("listener must not leak", fake.listeners.isEmpty())
    }

    @Test
    fun grantedResult_isReportedOnceAndListenerRemoved() {
        val fake = FakeShizuku(failRequest = false)
        val results = mutableListOf<Boolean>()
        PhantomProcessFixer.requestShizukuWith(fake::add, fake::remove, fake::request) { results += it }
        assertEquals(listOf(PhantomProcessFixer.SHIZUKU_REQUEST_CODE), fake.requested)
        val listener = fake.listeners.single()
        // Another app's request code is ignored.
        listener.onRequestPermissionResult(PhantomProcessFixer.SHIZUKU_REQUEST_CODE + 1, PackageManager.PERMISSION_GRANTED)
        assertTrue(results.isEmpty())
        listener.onRequestPermissionResult(PhantomProcessFixer.SHIZUKU_REQUEST_CODE, PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf(true), results)
        assertTrue(fake.listeners.isEmpty())
    }
}
