package com.ivarna.fluxlinux.core.files

import com.ivarna.fluxlinux.core.files.GuestDocumentPaths.DocId
import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * [GuestTree] over the root backend ([RootGuestFs] → [ShellRootOps] → host `sh`) for a fake
 * chroot rootfs in a temp dir: confinement, constant root calls per listing, mount
 * handling, and per-mode document-id namespacing. Links only point inside temp fixtures.
 */
class RootGuestFsTest {
    private lateinit var tmp: File
    private lateinit var rootfs: File
    private lateinit var home: File
    private lateinit var victim: File
    private lateinit var mounts: File
    private lateinit var ops: RecordingOps

    @Before
    fun setUp() {
        assumeTrue("host sh + /proc needed", HostShellRunner.available)
        tmp = Files.createTempDirectory("root-guestfs").toFile().canonicalFile
        rootfs = File(tmp, "chrootUbuntu").apply { mkdirs() }
        home = File(rootfs, "home/akane").apply { mkdirs() }
        File(rootfs, "root").mkdirs()
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/tool").writeText("#!/bin/sh")
        victim = Files.createTempDirectory("root-guestfs-victim").toFile().canonicalFile
        File(victim, "sentinel.txt").writeText("keep me")
        mounts = File(tmp, "mounts").apply { writeText("") }
        val cwd = File(tmp, "cwd").apply { mkdirs() }
        ops = RecordingOps(
            ShellRootOps(HostShellRunner(cwd), rootfs.path, RootScripts(rootfs.path, HostShellRunner.hostPath, mounts.path)),
        )
    }

    @After
    fun tearDown() {
        for (dir in listOfNotNull(if (::tmp.isInitialized) tmp else null, if (::victim.isInitialized) victim else null)) {
            assertTrue("cleanup $dir", SafeFiles.deleteTree(dir))
        }
    }

    /** Fresh per-call view, as the provider builds one per SAF call. */
    private fun tree(): GuestTree = GuestTree(RootGuestFs(ops, rootfs.path), rootfs.path, null, GuestLayout.CHROOT)

    private fun link(at: String, target: String) {
        val f = File(rootfs, at)
        f.parentFile.mkdirs()
        Files.createSymbolicLink(f.toPath(), File(target).toPath())
    }

    private fun assertAllRootPathsInside() {
        for (p in ops.paths) assertTrue("root call outside rootfs: $p", HostPaths.isUnder(p, rootfs.path))
    }

    @Test
    fun homeRoot_isAkaneHome_andFallsBackToRoot() {
        assertEquals("/home/akane", tree().rootBase(GuestDocumentPaths.ROOT_CHROOT_HOME))
        assertEquals("/", tree().rootBase(GuestDocumentPaths.ROOT_CHROOT_SYSTEM))
        assertTrue(home.delete())
        assertEquals("/root", tree().rootBase(GuestDocumentPaths.ROOT_CHROOT_HOME))
    }

    @Test
    fun resolve_followsGuestLinksInsideRootfs() {
        link("bin", "usr/bin")
        link("home/akane/abs", "/usr/bin/tool")
        val t = tree()
        assertEquals(File(rootfs, "usr/bin/tool").path, t.resolve("/bin/tool"))
        assertEquals(File(rootfs, "usr/bin/tool").path, t.resolve("/home/akane/abs"))
        assertEquals(File(rootfs, "home/akane/abs").path, t.resolveEntry("/home/akane/abs"))
        assertTrue(t.traversesLink("/bin/tool"))
        assertFalse(t.traversesLink("/home/akane/abs"))
        assertAllRootPathsInside()
    }

    @Test
    fun confinement_noLinkOrDotDotEscapesTheRootfs() {
        link("home/akane/slash", "/")
        link("home/akane/up", "../../../../../../..")
        link("home/akane/absUp", "/../../..${victim.path}")
        // A host-absolute path into the rootfs' parent (a PRoot .l2s alias) is NOT special here.
        link("home/akane/sibling", "${tmp.path}/outside")
        File(tmp, "outside").writeText("host file next to the rootfs")
        link("home/akane/victim", victim.path)
        val t = tree()

        assertEquals(rootfs.path, t.resolve("/home/akane/slash"))
        assertEquals(File(rootfs, "usr/bin/tool").path, t.resolve("/home/akane/slash/usr/bin/tool"))
        assertNull(t.resolve("/home/akane/up"))
        assertNull(t.resolve("/home/akane/up/etc/passwd"))
        assertNull(t.resolve("/home/akane/absUp/sentinel.txt"))
        assertNull(t.resolve("/../.."))
        assertNull(t.resolveEntry("/"))
        // Absolute targets land inside the rootfs: the host path does not exist there.
        assertEquals(File(rootfs, tmp.path.removePrefix("/") + "/outside").path, t.resolve("/home/akane/sibling"))
        assertEquals(File(rootfs, victim.path.removePrefix("/") + "/sentinel.txt").path, t.resolve("/home/akane/victim/sentinel.txt"))
        assertTrue(t.finalLinkLeavesContainer("/home/akane/up"))
        assertAllRootPathsInside()
    }

    @Test
    fun sharedStorageLinks_areUnresolvableInChrootMode() {
        link("home/akane/storage/shared", "/storage/emulated/0")
        link("home/akane/sd", "/sdcard/Download")
        val t = tree()
        assertNull(t.resolve("/home/akane/storage/shared"))
        assertNull(t.resolve("/home/akane/sd"))
        assertTrue(t.finalLinkLeavesContainer("/home/akane/storage/shared"))
        val parent = DocId(GuestDocumentPaths.ROOT_CHROOT_HOME, "")
        assertFalse(t.isChildWithoutLink(parent, DocId(GuestDocumentPaths.ROOT_CHROOT_HOME, "storage/shared")))
        assertTrue(t.isChildWithoutLink(parent, DocId(GuestDocumentPaths.ROOT_CHROOT_HOME, "storage")))
    }

    @Test
    fun listing_costsAConstantNumberOfRootCalls() {
        repeat(40) { File(home, "file $it").writeText("x") }
        repeat(10) { link("home/akane/link $it", "file $it") }
        val t = tree()
        val dir = t.resolve(t.guestPath(DocId(GuestDocumentPaths.ROOT_CHROOT_HOME, "")))!!
        val names = t.fs.list(dir)
        assertEquals(50, names.size)
        // What addRow does per child: entry, lstat, target resolution, link checks.
        for (n in names) {
            val path = "/home/akane/$n"
            val entry = t.resolveEntry(path)!!
            val st = t.fs.lstat(entry)!!
            if (st.type == GuestFs.Type.LINK) assertNotNull(t.resolve(path))
            t.traversesLink(path)
            t.finalLinkLeavesContainer(path)
            t.fs.isForeignMount(entry)
        }
        assertTrue("root calls: ${ops.calls}", ops.calls <= 3)
        assertAllRootPathsInside()
    }

    @Test
    fun deleteEntry_neverFollowsLinks() {
        link("home/akane/victim", victim.path)
        link("home/akane/dirlink", "/usr")
        val t = tree()
        assertFalse("through a link", t.deleteEntry("/home/akane/dirlink/bin/tool"))
        assertTrue(File(rootfs, "usr/bin/tool").exists())
        assertTrue(tree().deleteEntry("/home/akane/victim"))
        assertTrue(tree().deleteEntry("/home/akane/dirlink"))
        assertFalse(Files.exists(File(home, "victim").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep me", File(victim, "sentinel.txt").readText())
        assertTrue(File(rootfs, "usr/bin/tool").exists())
        assertFalse(tree().deleteEntry("/"))
        assertAllRootPathsInside()
    }

    @Test
    fun foreignMounts_areListOnly() {
        val proc = File(rootfs, "proc").apply { mkdirs() }
        File(proc, "cpuinfo").writeText("cpu")
        mounts.writeText("proc ${proc.path} proc rw 0 0\n")
        val t = tree()
        val fs = t.fs
        val cpu = t.resolve("/proc/cpuinfo")!!
        assertTrue(fs.isForeignMount(cpu))
        assertTrue(fs.isForeignMount(proc.path))
        assertFalse(fs.isForeignMount(home.path))
        assertTrue(fs.containsForeignMount(rootfs.path))
        assertFalse(fs.canWrite(cpu))
        assertTrue(fs.canWrite(home.path))
        assertEquals(listOf("cpuinfo"), fs.list(proc.path))
        assertFalse(fs.deleteTree(cpu))
        assertFalse(fs.createFile(File(proc, "x").path))
        assertFalse(tree().fs.copyTree(proc.path, File(home, "proc copy").path))
        val e = runCatching { tree().fs.open(cpu, "r") }.exceptionOrNull()
        assertTrue(e is java.io.FileNotFoundException)
        assertTrue(File(proc, "cpuinfo").exists())
    }

    @Test
    fun mutations_throughTheBackend() {
        val fs = tree().fs
        val a = File(home, "a 'b'").path
        assertTrue(fs.createFile(a))
        assertTrue(fs.mkdir(File(home, "d").path))
        fs.move(a, File(home, "d/a 'b'").path)
        assertTrue(fs.copyTree(File(home, "d").path, File(home, "d2").path))
        assertTrue(File(home, "d2/a 'b'").exists())
        assertTrue(fs.deleteTree(File(home, "d").path))
        assertFalse(File(home, "d").exists())
    }

    @Test
    fun randomAccessMode_isRefusedClearly() {
        File(home, "f").writeText("x")
        for (mode in listOf("rw", "rwt")) {
            val e = runCatching { tree().fs.open(File(home, "f").path, mode) }.exceptionOrNull()
            assertTrue(mode, e is java.io.FileNotFoundException && e.message!!.contains("not supported"))
        }
    }

    @Test
    fun outsideRootfs_isRefusedBeforeAnyRootCall() {
        val fs = RootGuestFs(ops, rootfs.path)
        for (p in listOf(victim.path, "${rootfs.path}-x", "/", tmp.path)) {
            assertTrue(p, runCatching { fs.lstat(p) }.exceptionOrNull() is RootFsException)
            assertTrue(p, runCatching { fs.list(p) }.exceptionOrNull() is RootFsException)
            assertTrue(p, runCatching { fs.deleteTree(p) }.exceptionOrNull() is RootFsException)
        }
        assertEquals(0, ops.calls)
    }

    @Test
    fun missingRootfs_isAClearErrorNotACrash() {
        assertTrue(SafeFiles.deleteTree(rootfs))
        val t = tree()
        val e = runCatching { t.resolve("/home/akane/x") }.exceptionOrNull()
        assertTrue("$e", e is RootFsException)
    }

    // ---- shared storage (direct access, through links only) -----------------------------

    @Test
    fun sharedStorageLinks_reachHostSharedStorageDirectly_browseCreateOnly() {
        val shared = Files.createTempDirectory("root-guestfs-shared").toFile().canonicalFile
        try {
            File(shared, "Download").mkdirs()
            File(shared, "Download/report.pdf").writeText("pdf")
            link("home/akane/storage/downloads", "/sdcard/Download")
            link("home/akane/storage/shared", "/storage/emulated/0")
            link("home/akane/storage/up", "/sdcard/../..")
            val t = GuestTree(
                SharedStorageRoutingFs(RootGuestFs(ops, rootfs.path), LocalGuestFs, shared.path),
                rootfs.path,
                shared.path,
                GuestLayout.CHROOT,
            )
            val report = "/home/akane/storage/downloads/report.pdf"
            assertEquals(File(shared, "Download/report.pdf").path, t.resolve(report))
            assertEquals(shared.path, t.resolve("/home/akane/storage/shared"))
            assertNull(t.resolve("/home/akane/storage/up"))

            // Reached through a link: browse/create only, never granted, never deleted.
            val home = DocId(GuestDocumentPaths.ROOT_CHROOT_HOME, "")
            assertTrue(t.traversesLink(report))
            assertTrue(t.finalLinkLeavesContainer("/home/akane/storage/downloads"))
            assertFalse(t.isChildWithoutLink(home, DocId(GuestDocumentPaths.ROOT_CHROOT_HOME, "storage/downloads/report.pdf")))
            assertFalse(t.deleteEntry(report))
            assertTrue(File(shared, "Download/report.pdf").exists())

            // Shared paths never go through root.
            val before = ops.calls
            val dir = t.resolve("/home/akane/storage/downloads")!!
            assertEquals(listOf("report.pdf"), t.fs.list(dir))
            assertEquals(GuestFs.Type.FILE, t.fs.lstat(File(dir, "report.pdf").path)?.type)
            assertTrue(t.fs.canWrite(dir))
            assertFalse(t.fs.isForeignMount(dir))
            assertTrue(t.fs.createFile(File(dir, "new.txt").path))
            assertTrue(File(shared, "Download/new.txt").exists())
            assertEquals(before, ops.calls)

            // No moves/copies across the two backends.
            assertFalse(t.fs.copyTree(File(dir, "report.pdf").path, File(rootfs, "home/akane/r.pdf").path))
            assertTrue(runCatching { t.fs.move(File(dir, "new.txt").path, File(rootfs, "home/akane/n.txt").path) }.isFailure)
            assertTrue(File(shared, "Download/new.txt").exists())
            assertAllRootPathsInside()
        } finally {
            assertTrue(SafeFiles.deleteTree(shared))
        }
    }

    // ---- document-id namespacing --------------------------------------------------------

    @Test
    fun docIds_areNamespacedPerMode() {
        val chroot = GuestDocumentPaths.parse("ubuntu_chroot_home:Documents/a.txt")!!
        val proot = GuestDocumentPaths.parse("ubuntu_home:Documents/a.txt")!!
        assertEquals(GuestLayout.CHROOT, GuestLayout.forRootId(chroot.rootId))
        assertEquals(GuestLayout.PROOT, GuestLayout.forRootId(proot.rootId))
        assertFalse(GuestLayout.CHROOT.owns(proot.rootId))
        assertFalse(GuestLayout.PROOT.owns(chroot.rootId))
        assertTrue(GuestLayout.CHROOT.owns("ubuntu_chroot_root"))
        assertFalse(GuestDocumentPaths.isChild(GuestDocumentPaths.parse("ubuntu_home:")!!, chroot))
        assertNull(GuestDocumentPaths.parse("ubuntu_chroot:x"))
        assertNull(GuestDocumentPaths.parse("ubuntu_chroot_home:../x"))
        val roots = GuestLayout.entries.flatMap { listOf(it.homeRootId, it.systemRootId) }
        assertEquals(roots.size, roots.toSet().size)
    }

    @Test
    fun uiRootIds_mapToTheActiveMode() {
        assertEquals("ubuntu_chroot_home", GuestLayout.CHROOT.mapRootId(UbuntuFiles.ROOT_HOME))
        assertEquals("ubuntu_chroot_root", GuestLayout.CHROOT.mapRootId(UbuntuFiles.ROOT_SYSTEM))
        assertEquals("ubuntu_home", GuestLayout.PROOT.mapRootId(UbuntuFiles.ROOT_HOME))
        assertEquals("ubuntu_home", GuestLayout.PROOT.mapRootId("ubuntu_chroot_home"))
        assertEquals("ubuntu_root", GuestLayout.PROOT.mapRootId(UbuntuFiles.ROOT_SYSTEM))
    }

    @Test
    fun prootHome_prefersAkaneThenLegacyFlux() {
        val p = Files.createTempDirectory("proot-home").toFile().canonicalFile
        try {
            val fs = File(p, "containers/ubuntu/rootfs").apply { mkdirs() }
            File(fs, "root").mkdirs()
            assertEquals("/root", GuestDocumentPaths.rootBase(fs, GuestDocumentPaths.ROOT_HOME))
            File(fs, "home/flux").mkdirs()
            assertEquals("/home/flux", GuestDocumentPaths.rootBase(fs, GuestDocumentPaths.ROOT_HOME))
            File(fs, "home/akane").mkdirs()
            assertEquals("/home/akane", GuestDocumentPaths.rootBase(fs, GuestDocumentPaths.ROOT_HOME))
        } finally {
            assertTrue(SafeFiles.deleteTree(p))
        }
    }
}
