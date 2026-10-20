#pragma once

// Full-length I/O for the activity <-> X server event socket (a blocking AF_UNIX stream).
// A bare read()/write() may transfer fewer bytes than asked (large clipboard payloads, signals),
// which would leave the rest of a payload to be parsed as events. These helpers loop until the
// whole buffer is transferred, or report failure so the caller can drop the connection.

#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

// Upper bound for a variable-length payload that follows an event header (clipboard text).
// A larger announced payload is drained and dropped so the event stream stays in sync.
#define LORIE_MAX_PAYLOAD (16u * 1024u * 1024u)
// Upper bound for the output name that follows EVENT_SCREEN_SIZE.
#define LORIE_MAX_NAME 4096u

static inline bool lorie_read_full(int fd, void* buf, size_t count) {
    char* p = (char*) buf;
    while (count > 0) {
        ssize_t n = read(fd, p, count);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0)
            return false;
        p += n;
        count -= (size_t) n;
    }
    return true;
}

static inline bool lorie_write_full(int fd, const void* buf, size_t count) {
    const char* p = (const char*) buf;
    while (count > 0) {
        // MSG_NOSIGNAL: a vanished peer must surface as an error, not as SIGPIPE.
        ssize_t n = send(fd, p, count, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR)
            continue;
        if (n <= 0)
            return false;
        p += n;
        count -= (size_t) n;
    }
    return true;
}

// Reads and discards exactly `count` bytes.
static inline bool lorie_skip_full(int fd, size_t count) {
    char scratch[4096];
    while (count > 0) {
        size_t chunk = count < sizeof(scratch) ? count : sizeof(scratch);
        if (!lorie_read_full(fd, scratch, chunk))
            return false;
        count -= chunk;
    }
    return true;
}

#ifdef __cplusplus
// Activity side: the single serialised writer for the GUI end of the socket (defined in
// activity.cpp). Reads *fdPtr under the writer lock, then writes header and optional payload
// back to back so no other thread's event can land between them. Returns false when not
// connected or the write failed.
bool lorieActivitySend(volatile int* fdPtr, const void* header, size_t headerLen, const void* payload, size_t payloadLen);
#endif
