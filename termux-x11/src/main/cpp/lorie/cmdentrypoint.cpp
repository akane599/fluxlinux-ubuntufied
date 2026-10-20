#pragma clang diagnostic ignored "-Wunknown-pragmas"
#pragma clang diagnostic ignored "-Wmissing-prototypes"
#pragma ide diagnostic ignored "bugprone-reserved-identifier"
#pragma ide diagnostic ignored "OCUnusedMacroInspection"
#pragma ide diagnostic ignored "EndlessLoop"
#define __USE_GNU
#ifdef HAVE_DIX_CONFIG_H
#include <dix-config.h>
#endif
#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <libgen.h>
#include <cerrno>
extern "C" {
#include <globals.h>
#define class lorie_reserved_class
#define public lorie_reserved_public
#include <xkbsrv.h>
#include <inpututils.h>
#include <randrstr.h>
#undef class
#undef public
}
#include <linux/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include "lorie.h"
#include "conn_io.h"

#define log(prio, ...) __android_log_print(ANDROID_LOG_ ## prio, "LorieNative", __VA_ARGS__)

static int argc = 0;
static char** argv = nullptr;
__LIBC_HIDDEN__ volatile int conn_fd = -1;
// Serialises every writer of conn_fd and every change of it, so a header and its payload (or
// passed fd) are never split by another event and nothing is written into a just-closed fd.
// Separate from the activity side's lock: in the embedded case both ends live in one process.
static pthread_mutex_t connWriteLock = PTHREAD_MUTEX_INITIALIZER;
// Clipboard payloads from the activity larger than this are drained and dropped.
#define LORIE_MAX_CLIPBOARD LORIE_MAX_PAYLOAD

static bool serverSend(const void* header, size_t headerLen, const void* payload, size_t payloadLen) {
    pthread_mutex_lock(&connWriteLock);
    int fd = conn_fd;
    bool ok = fd != -1 && lorie_write_full(fd, header, headerLen)
            && (!payloadLen || lorie_write_full(fd, payload, payloadLen));
    pthread_mutex_unlock(&connWriteLock);
    return ok;
}
extern DeviceIntPtr lorieMouse, lorieTouch, lorieKeyboard, loriePen, lorieEraser;
extern ScreenPtr pScreenPtr;
extern "C" int ucs2keysym(long ucs);
extern "C" void lorieKeysymKeyboardEvent(KeySym keysym, int down);

char *xtrans_unix_path_x11 = nullptr;
char *xtrans_unix_dir_x11 = nullptr;

struct xorg_list registeredBuffers;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_x11_CmdEntryPoint_start(JNIEnv *env, __unused jclass cls, jobjectArray args) {
    pthread_t t;
    JavaVM* vm = nullptr;
    auto detectTracer = []() -> Bool {
        FILE *fp;
        char line[256];
        int pid = 0;

        fp = fopen("/proc/self/status", "r");
        if (!fp)
            return TRUE;

        while (fgets(line, sizeof(line), fp)) {
            if (strncmp(line, "TracerPid:", 10) == 0) {
                sscanf(line+10, "%d", &pid);
                break;
            }
        }

        if (pid != 0)
            log(INFO, "Tracer detected");

        fclose(fp);
        return pid != 0;
    };
    // execv's argv array is a bit incompatible with Java's String[], so we do some converting here...
    argc = env->GetArrayLength(args) + 1; // Leading executable path
    argv = (char**) calloc(argc, sizeof(char*));

    argv[0] = (char*) "Xlorie";
    for(int i=1; i<argc; i++) {
        auto js = (jstring) env->GetObjectArrayElement(args, i - 1);
        const char *pjc = env->GetStringUTFChars(js, JNI_FALSE);
        argv[i] = (char *) calloc(strlen(pjc) + 1, sizeof(char)); //Extra char for the terminating NULL
        strcpy((char *) argv[i], pjc);
        env->ReleaseStringUTFChars(js, pjc);
    }

    {
        cpu_set_t mask;
        long num_cpus = sysconf(_SC_NPROCESSORS_ONLN);

        for (int i = num_cpus/2; i < num_cpus; i++)
            CPU_SET(i, &mask);

        if (sched_setaffinity(0, sizeof(cpu_set_t), &mask) == -1)
            log(ERROR, "Failed to set process affinity: %s", strerror(errno));
    }

    if (getenv("TERMUX_X11_DEBUG") && !fork()) {
        // Printing logs of local logcat.
        char pid[32] = {0};
        prctl(PR_SET_PDEATHSIG, SIGTERM);
        sprintf(pid, "%d", getppid());
        execlp("logcat", "logcat", "--pid", pid, nullptr);
    }

    // No matter what tracer is attached.
    // In the case of gdb or lldb LD_PRELOAD is already set.
    // In the case of proot or proot-distro libtermux-exec in LD_PRELOAD will break linking.
    if (access("/data/data/com.termux/files/usr/lib/libtermux-exec.so", F_OK) == 0 && !detectTracer()
            && !getenv("XSTARTUP_LD_PRELOAD"))
        setenv("LD_PRELOAD", "/data/data/com.termux/files/usr/lib/libtermux-exec.so", 1);

    // adb sets TMPDIR to /data/local/tmp which is pretty useless.
    if (!strcmp("/data/local/tmp", getenv("TMPDIR") ?: ""))
        unsetenv("TMPDIR");

    if (!getenv("TMPDIR")) {
        if (access("/tmp", F_OK) == 0)
            setenv("TMPDIR", "/tmp", 1);
        else if (access("/data/data/com.termux/files/usr/tmp", F_OK) == 0)
            setenv("TMPDIR", "/data/data/com.termux/files/usr/tmp", 1);
    }

    if (!getenv("TMPDIR")) {
        char* error = (char*) "$TMPDIR is not set. Normally it is pointing to /tmp of a container.";
        log(ERROR, "%s", error);
        dprintf(2, "%s\n", error);
        return JNI_FALSE;
    }

    {
        char* tmp = getenv("TMPDIR");
        char cwd[1024] = {0};

        if (!getcwd(cwd, sizeof(cwd)) || access(cwd, F_OK) != 0)
            chdir(tmp);
        asprintf(&xtrans_unix_path_x11, "%s/.X11-unix/X", tmp);
        asprintf(&xtrans_unix_dir_x11, "%s/.X11-unix/", tmp);
    }

    log(VERBOSE, "Using TMPDIR=\"%s\"", getenv("TMPDIR"));

    {
        const char *root_dir = dirname(getenv("TMPDIR"));
        const char* pathes[] = {
                "/etc/X11/fonts", "/usr/share/fonts/X11", "/share/fonts", nullptr
        };
        for (int i=0; pathes[i]; i++) {
            char current_path[1024] = {0};
            snprintf(current_path, sizeof(current_path), "%s%s", root_dir, pathes[i]);
            if (access(current_path, F_OK) == 0) {
                char default_font_path[4096] = {0};
                snprintf(default_font_path, sizeof(default_font_path),
                         "%s/misc,%s/TTF,%s/OTF,%s/Type1,%s/100dpi,%s/75dpi",
                         current_path, current_path, current_path, current_path, current_path, current_path);
                defaultFontPath = strdup(default_font_path);
                break;
            }
        }
    }

    if (!getenv("XKB_CONFIG_ROOT")) {
        // chroot case
        const char *root_dir = dirname(getenv("TMPDIR"));
        char current_path[1024] = {0};
        snprintf(current_path, sizeof(current_path), "%s/usr/share/X11/xkb", root_dir);
        if (access(current_path, F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", current_path, 1);
    }

    if (!getenv("XKB_CONFIG_ROOT")) {
        // proot case
        if (access("/usr/share/xkeyboard-config-2", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/usr/share/xkeyboard-config-2", 1);
        else if (access("/usr/share/X11/xkb", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/usr/share/X11/xkb", 1);
        // Termux case
        else if (access("/data/data/com.termux/files/usr/share/xkeyboard-config-2", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/data/data/com.termux/files/usr/share/xkeyboard-config-2", 1);
        else if (access("/data/data/com.termux/files/usr/share/X11/xkb", F_OK) == 0)
            setenv("XKB_CONFIG_ROOT", "/data/data/com.termux/files/usr/share/X11/xkb", 1);
    }

    if (!getenv("XKB_CONFIG_ROOT")) {
        char* error = (char*) "$XKB_CONFIG_ROOT is not set. Normally it is pointing to /usr/share/X11/xkb of a container.";
        log(ERROR, "%s", error);
        dprintf(2, "%s\n", error);
        return JNI_FALSE;
    }

    XkbBaseDirectory = getenv("XKB_CONFIG_ROOT");
    if (access(XkbBaseDirectory, F_OK) != 0) {
        log(ERROR, "%s is unaccessible: %s\n", XkbBaseDirectory, strerror(errno));
        printf("%s is unaccessible: %s\n", XkbBaseDirectory, strerror(errno));
        return JNI_FALSE;
    }

    env->GetJavaVM(&vm);

    AChoreographer *choreographer = AChoreographer_getInstance();
    // Trigger it first time
    AChoreographer_postFrameCallback(choreographer, (AChoreographer_frameCallback) lorieChoreographerFrameCallback, choreographer);

    xorg_list_init(&registeredBuffers);
    pthread_create(&t, nullptr, +[](__unused void* cookie) -> void* {
        exit(dix_main(argc, (char**) argv, (char*[]) { nullptr }));
    }, vm);
    return JNI_TRUE;
}

static Bool handleTouchEvent(__unused ClientPtr pClient, void *closure) {
    ValuatorMask mask;
    auto *e = (lorieEvent*) closure;
    double x = max(min((float) e->touch.x, pScreenPtr->width), 0);
    double y = max(min((float) e->touch.y, pScreenPtr->height), 0);
    valuator_mask_zero(&mask);
    DDXTouchPointInfoPtr touch = TouchFindByDDXID(lorieTouch, e->touch.id, FALSE);

    // Avoid duplicating events
    if (touch && touch->active) {
        double oldx = 0, oldy = 0;
        if (e->touch.type == XI_TouchUpdate &&
            valuator_mask_fetch_double(touch->valuators, 0, &oldx) &&
            valuator_mask_fetch_double(touch->valuators, 1, &oldy) &&
            oldx == x && oldy == y)
            goto end;
    }

    // Sometimes activity part does not send XI_TouchBegin and sends only XI_TouchUpdate.
    if (e->touch.type == XI_TouchUpdate && (!touch || !touch->active))
        e->touch.type = XI_TouchBegin;

    if (e->touch.type == XI_TouchEnd && (!touch || !touch->active))
        goto end;

    valuator_mask_set_double(&mask, 0, x * 0xFFFF / (float) pScreenPtr->width);
    valuator_mask_set_double(&mask, 1, y * 0xFFFF / (float) pScreenPtr->height);
    QueueTouchEvents(lorieTouch, e->touch.type, e->touch.id, 0, &mask);
    lorieSetCursorVisible(FALSE);

    end:
    free(e);
    return TRUE;
}

// Tears down the activity connection: on socket error, or when the stream can no longer be
// parsed (short read inside a message), since every later event would be misframed.
static void dropLorieConnection(int fd) {
    LorieBuffer* buf;
    InputThreadUnregisterDev(fd);
    pthread_mutex_lock(&connWriteLock);
    close(fd);
    if (conn_fd == fd)
        conn_fd = -1;
    pthread_mutex_unlock(&connWriteLock);
    lorieEnableClipboardSync(FALSE);
    while ((buf = LorieBufferList_first(&registeredBuffers)))
        LorieBuffer_removeFromList(buf);
}

void handleLorieEvents(int fd, __unused int ready, __unused void *ignored) {
    ValuatorMask mask;
    lorieEvent e = {0};
    valuator_mask_zero(&mask);

    if (ready & X_NOTIFY_ERROR) {
        dropLorieConnection(fd);
        return;
    }

    again:
    if (!lorie_read_full(fd, &e, sizeof(e)))
        return dropLorieConnection(fd);
    {
        switch(e.type) {
            case EVENT_SCREEN_SIZE: {
                size_t nameSize = e.screenSize.name_size;
                if (nameSize > LORIE_MAX_NAME) {
                    // Not a name we would ever send: drain it to stay in sync and use none.
                    if (!lorie_skip_full(fd, nameSize))
                        return dropLorieConnection(fd);
                    nameSize = 0;
                }
                auto *copy = (lorieEvent*) calloc(1, sizeof(lorieEvent) + nameSize + 1);
                if (!copy) {
                    if (!lorie_skip_full(fd, nameSize))
                        return dropLorieConnection(fd);
                    break;
                }
                memcpy(copy, &e, sizeof(e));
                copy->screenSize.name_size = nameSize;
                copy->screenSize.name = nameSize ? (char*) (copy + 1) : nullptr;
                if (nameSize && !lorie_read_full(fd, copy->screenSize.name, nameSize)) {
                    free(copy);
                    return dropLorieConnection(fd);
                }
                QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
                    // This must be done only on X server thread.
                    auto* e = (lorieEvent*) closure;
                    __android_log_print(ANDROID_LOG_ERROR, "tx11-request", "window changed: %d %d %s", e->screenSize.width, e->screenSize.height, e->screenSize.name);
                    lorieConfigureNotify(e->screenSize.width, e->screenSize.height, e->screenSize.framerate, e->screenSize.name_size, e->screenSize.name);
                    free(e);
                    return TRUE;
                }, nullptr, copy);
                lorieWakeServer();
                break;
            }
            case EVENT_TOUCH: {
                auto *copy = (lorieEvent*) calloc(1, sizeof(lorieEvent));
                memcpy(copy, &e, sizeof(e));
                QueueWorkProc(handleTouchEvent, nullptr, copy);
                lorieWakeServer();
                break;
            }
            case EVENT_STYLUS: {
                static int buttons_prev = 0;
                uint32_t released, pressed, diff;
                DeviceIntPtr device = e.stylus.mouse ? lorieMouse : (e.stylus.eraser ? lorieEraser : loriePen);
                if (!device) {
                    __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "got stylus event but device is not requested\n");
                    break;
                }
                __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "got stylus event %f %f %d %d %d %d %s\n", e.stylus.x, e.stylus.y, e.stylus.pressure, e.stylus.tilt_x, e.stylus.tilt_y, e.stylus.orientation,
                                    device == lorieMouse ? "lorieMouse" : (device == loriePen ? "loriePen" : "lorieEraser"));

                valuator_mask_set_double(&mask, 0, max(min(e.stylus.x, pScreenPtr->width), 0));
                valuator_mask_set_double(&mask, 1, max(min(e.stylus.y, pScreenPtr->height), 0));
                if (device != lorieMouse) {
                    valuator_mask_set_double(&mask, 2, e.stylus.pressure);
                    valuator_mask_set_double(&mask, 3, e.stylus.tilt_x);
                    valuator_mask_set_double(&mask, 4, e.stylus.tilt_y);
                    valuator_mask_set_double(&mask, 5, e.stylus.orientation);
                }
                QueuePointerEvents(device, MotionNotify, 0, POINTER_ABSOLUTE | POINTER_DESKTOP | (device == lorieMouse ? POINTER_NORAW : 0), &mask);

                diff = buttons_prev ^ e.stylus.buttons;
                released = diff & ~e.stylus.buttons;
                pressed = diff & e.stylus.buttons;

                for (int i=0; i<3; i++) {
                    if (released & 0x1) {
                        QueuePointerEvents(device, ButtonRelease, i + 1, POINTER_RELATIVE, nullptr);
                        __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "sending %d press", i+1);
                    }
                    if (pressed & 0x1) {
                        QueuePointerEvents(device, ButtonPress, i + 1, POINTER_RELATIVE, nullptr);
                        __android_log_print(ANDROID_LOG_DEBUG, "LorieNative", "sending %d release", i+1);
                    }
                    released >>= 1;
                    pressed >>= 1;
                }
                buttons_prev = e.stylus.buttons;

                break;
            }
            case EVENT_STYLUS_ENABLE: {
                lorieSetStylusEnabled(e.stylusEnable.enable);
                break;
            }
            case EVENT_MOUSE: {
                int flags;
                lorieSetCursorVisible(TRUE);
                switch(e.mouse.detail) {
                    case 0: // BUTTON_UNDEFINED
                        flags = (e.mouse.relative) ? POINTER_RELATIVE | POINTER_ACCELERATE : POINTER_ABSOLUTE | POINTER_SCREEN | POINTER_NORAW;
                        if (!e.mouse.relative) {
                            e.mouse.x = max(0, min(e.mouse.x, pScreenPtr->width));
                            e.mouse.y = max(0, min(e.mouse.y, pScreenPtr->height));
                        }
                        valuator_mask_set_double(&mask, 0, (double) e.mouse.x);
                        valuator_mask_set_double(&mask, 1, (double) e.mouse.y);
                        QueuePointerEvents(lorieMouse, MotionNotify, 0, flags, &mask);
                        break;
                    case 1: // BUTTON_LEFT
                    case 2: // BUTTON_MIDDLE
                    case 3: // BUTTON_RIGHT
                        QueuePointerEvents(lorieMouse, e.mouse.down ? ButtonPress : ButtonRelease, e.mouse.detail, POINTER_RELATIVE, nullptr);
                        break;
                    case 4: // BUTTON_SCROLL
                        if (e.mouse.x) {
                            valuator_mask_zero(&mask);
                            valuator_mask_set_double(&mask, 2, (double) e.mouse.x / 120);
                            QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_RELATIVE, &mask);
                        }
                        if (e.mouse.y) {
                            valuator_mask_zero(&mask);
                            valuator_mask_set_double(&mask, 3, (double) e.mouse.y / 120);
                            QueuePointerEvents(lorieMouse, MotionNotify, 0, POINTER_RELATIVE, &mask);
                        }
                        break;
                }
                break;
            }
            case EVENT_KEY:
                QueueKeyboardEvents(lorieKeyboard, e.key.state ? KeyPress : KeyRelease, e.key.key);
                break;
            case EVENT_UNICODE: {
                int ks = ucs2keysym((long) e.unicode.code);
                lorieKeysymKeyboardEvent(ks, TRUE);
                lorieKeysymKeyboardEvent(ks, FALSE);
                break;
            }
            case EVENT_CLIPBOARD_ENABLE:
                lorieEnableClipboardSync(e.clipboardEnable.enable);
                break;
            case EVENT_CLIPBOARD_ANNOUNCE:
                QueueWorkProc(+[](__unused ClientPtr pClient, __unused void *closure) -> Bool {
                    // This must be done only on X server thread.
                    lorieHandleClipboardAnnounce();
                    return TRUE;
                }, nullptr, nullptr);
                lorieWakeServer();
                break;
            case EVENT_CLIPBOARD_SEND: {
                uint32_t count = e.clipboardSend.count;
                char *data = count <= LORIE_MAX_CLIPBOARD ? (char*) calloc(1, (size_t) count + 1) : nullptr;
                if (!data) {
                    // Too large (or no memory): drain it to stay in sync and answer with nothing.
                    log(ERROR, "Dropping clipboard payload of %u bytes", count);
                    if (!lorie_skip_full(fd, count))
                        return dropLorieConnection(fd);
                    data = (char*) calloc(1, 1);
                    if (!data)
                        break;
                } else if (!lorie_read_full(fd, data, count)) {
                    free(data);
                    return dropLorieConnection(fd);
                } else
                    data[count] = 0;
                QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
                    // This must be done only on X server thread.
                    lorieHandleClipboardData((const char*) closure);
                    return TRUE;
                }, nullptr, data);
                lorieWakeServer();
                break;
            }
            case EVENT_RENDERER_WAKEUP_COND: {
                int wakeupFd = ancil_recv_fd(fd);
                if (wakeupFd >= 0)
                    lorieSetRendererWakeupCond(wakeupFd);
                break;
            }
            case EVENT_GPU_COPY_DONE:
                QueueWorkProc(+[](__unused ClientPtr pClient, __unused void *closure) -> Bool {
                    // This must be done only on X server thread (touches present's internal vblank queue).
                    lorieRecheckGpuCopies();
                    return TRUE;
                }, nullptr, nullptr);
                lorieWakeServer();
                break;
            case EVENT_LOCK_KEYS_STATE: {
                auto *copy = (lorieEvent*) calloc(1, sizeof(lorieEvent));
                memcpy(copy, &e, sizeof(e));
                QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
                    // This must be done only on X server thread (touches XKB state directly).
                    auto *e = (lorieEvent*) closure;
                    lorieSyncLockKeysState(e->lockKeysState.state);
                    free(e);
                    return TRUE;
                }, nullptr, copy);
                lorieWakeServer();
                break;
            }
        }

        int n;
        if (ioctl(fd, FIONREAD, &n) >= 0 && n > sizeof(e))
            goto again;
    }
}

void lorieSendClipboardData(const char* data) {
    if (data && conn_fd != -1) {
        size_t len = strlen(data);
        if (len > LORIE_MAX_PAYLOAD) {
            log(ERROR, "Not sending a %zu byte clipboard to the activity", len);
            return;
        }
        lorieEvent e = { .clipboardSend = { .t = EVENT_CLIPBOARD_SEND, .count = (uint32_t) len } };
        serverSend(&e, sizeof(e), data, len);
    }
}

void lorieRequestClipboard(void) {
    if (conn_fd != -1) {
        lorieEvent e = { .type = EVENT_CLIPBOARD_REQUEST };
        serverSend(&e, sizeof(e), nullptr, 0);
    }
}

bool lorieConnectionAlive(void) {
    if (conn_fd == -1)
        return false;

    // Check if socket is closed or has errors.
    struct pollfd p = { .fd = conn_fd, .events = POLLIN | POLLHUP | POLLERR | POLLRDHUP };
    return !(poll(&p, 1, 0) == 1 && (p.revents & (POLLERR | POLLNVAL | POLLRDHUP | POLLHUP)));
}

void lorieSendSharedServerState(int memfd) {
    pthread_mutex_lock(&connWriteLock);
    if (conn_fd != -1) {
        lorieEvent e = { .type = EVENT_SHARED_SERVER_STATE };
        if (lorie_write_full(conn_fd, &e, sizeof(e)))
            ancil_send_fd(conn_fd, memfd);
    }
    pthread_mutex_unlock(&connWriteLock);
}

void lorieRegisterBuffer(LorieBuffer* buffer) {
    unsigned long id = LorieBuffer_description(buffer)->id;
    if (conn_fd == -1 || LorieBufferList_findById(&registeredBuffers, id))
        return; // Already registered

    if (conn_fd != -1 && buffer) {
        lorieEvent e = { .type = EVENT_ADD_BUFFER };
        pthread_mutex_lock(&connWriteLock);
        if (conn_fd != -1 && lorie_write_full(conn_fd, &e, sizeof(e)))
            LorieBuffer_sendHandleToUnixSocket(buffer, conn_fd);
        pthread_mutex_unlock(&connWriteLock);
        LorieBuffer_addToList(buffer, &registeredBuffers);
        const LorieBuffer_Desc* desc = LorieBuffer_description(buffer);
        log(INFO, "Sent shared buffer width %d stride %d height %d format %d type %d id %llu", desc->width, desc->stride, desc->height, desc->format, desc->type, desc->id);
    }
}

void lorieUnregisterBuffer(LorieBuffer* buffer) {
    unsigned long id;
    if (!buffer || (!LorieBufferList_findById(&registeredBuffers, (id = LorieBuffer_description(buffer)->id))))
        return;  // Not exist or not registered so no need to unregister

    if (conn_fd != -1 && buffer) {
        lorieEvent e = { .removeBuffer = { .t = EVENT_REMOVE_BUFFER, .id = id } };
        serverSend(&e, sizeof(e), nullptr, 0);
        LorieBuffer_removeFromList(buffer);
    }
}

extern "C" void DDXNotifyFocusChanged(void) {
    if (conn_fd != -1) {
        lorieEvent e = { .type = EVENT_WINDOW_FOCUS_CHANGED };
        serverSend(&e, sizeof(e), nullptr, 0);
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_termux_x11_CmdEntryPoint_getXConnection(JNIEnv *env, __unused jobject cls) {
    int client[2];
    jclass ParcelFileDescriptorClass = env->FindClass("android/os/ParcelFileDescriptor");
    jmethodID adoptFd = env->GetStaticMethodID(ParcelFileDescriptorClass, "adoptFd", "(I)Landroid/os/ParcelFileDescriptor;");
    socketpair(AF_UNIX, SOCK_STREAM, 0, client);
    QueueWorkProc(+[](__unused ClientPtr pClient, void *closure) -> Bool {
        InputThreadRegisterDev((int) (int64_t) closure, handleLorieEvents, nullptr);
        pthread_mutex_lock(&connWriteLock);
        conn_fd = (int) (int64_t) closure;
        pthread_mutex_unlock(&connWriteLock);
        lorieActivityConnected();
        return TRUE;
    }, nullptr, (void*) (int64_t) client[1]);
    lorieWakeServer();

    return env->CallStaticObjectMethod(ParcelFileDescriptorClass, adoptFd, client[0]);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_termux_x11_CmdEntryPoint_getLogcatOutput(JNIEnv *env, __unused jobject cls) {
    jclass ParcelFileDescriptorClass = env->FindClass("android/os/ParcelFileDescriptor");
    jmethodID adoptFd = env->GetStaticMethodID(ParcelFileDescriptorClass, "adoptFd", "(I)Landroid/os/ParcelFileDescriptor;");
    const char *debug = getenv("TERMUX_X11_DEBUG");
    if (debug && !strcmp(debug, "1")) {
        pthread_t t;
        int p[2];
        pipe(p);
        fchmod(p[1], 0777);
        pthread_create(&t, nullptr, +[](void *arg) -> void* {
            char buffer[4096];
            size_t len;
            while((len = read((int) (int64_t) arg, buffer, 4096)) >=0)
                write(2, buffer, len);
            close((int) (int64_t) arg);
            return nullptr;
        }, (void*) (uint64_t) p[0]);
        return env->CallStaticObjectMethod(ParcelFileDescriptorClass, adoptFd, p[1]);
    }
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_x11_CmdEntryPoint_connected(__unused JNIEnv *env, __unused jclass clazz) {
    return conn_fd != -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_x11_CmdEntryPoint_listenForConnections(JNIEnv *env, jobject thiz) {
    int server_fd, client, count;
    struct sockaddr_in address = { .sin_family = AF_INET, .sin_port = htons(PORT), .sin_addr = { .s_addr = INADDR_ANY } };
    int addrlen = sizeof(address);
    jmethodID sendBroadcast = env->GetMethodID(env->GetObjectClass(thiz), "sendBroadcast", "()V");
    uint8_t buffer[512] = {0};
    int reuse = 1;

    // Even in the case if it will fail for some reason everything will work fine
    // But connection will be delayed a bit

    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        log(ERROR, "Socket creation failed: %s", strerror(errno));
        return;
    }

    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEPORT, &reuse, sizeof(reuse));

    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        log(ERROR, "Socket bind failed: %s", strerror(errno));
        close(server_fd);
        return;
    }

    if (listen(server_fd, 5) < 0) {
        log(ERROR, "Socket listen failed: %s", strerror(errno));
        close(server_fd);
        return;
    }

    while(true) {
        if ((client = accept(server_fd, (struct sockaddr *)&address, (socklen_t *)&addrlen)) < 0) {
            log(ERROR, "Socket accept failed: %s", strerror(errno));
            continue;
        }

        if ((count = read(client, buffer, sizeof(buffer))) > 0) {
            if (!memcmp(buffer, MAGIC, min(count, sizeof(MAGIC)))) {
                log(DEBUG, "New client connection!\n");
                env->CallVoidMethod(thiz, sendBroadcast);
            }
        }
        close(client);
    }
}

void abort(void) {
    _exit(134);
}

void exit(int code) {
    _exit(code);
}
