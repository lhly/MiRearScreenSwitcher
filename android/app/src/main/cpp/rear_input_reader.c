/* SPDX-License-Identifier: GPL-3.0-or-later
 * Rear evdev reader. No main-display device is ever grabbed.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>
#include "rear_input_reader_control.h"

#define MAX_CONTACTS 16
static volatile sig_atomic_t stopping;
static void stop(int sig) { (void)sig; stopping = 1; }
static int64_t millis(void) {
    struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}
static int checked_open(const char *path, int flags, const char *expected_name) {
    if (strncmp(path, "/dev/input/event", 16) || !path[16] || strspn(path + 16, "0123456789") != strlen(path + 16)) {
        errno = EINVAL; return -1;
    }
    int fd = open(path, flags | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return -1;
    char name[128] = {0};
    if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0 || strcmp(name, expected_name)) {
        close(fd); errno = ENODEV; return -1;
    }
    return fd;
}
static int snapshot(int fd, int code, int slots, int *values) {
    int data[MAX_CONTACTS + 1] = {0}; data[0] = code;
    if (ioctl(fd, EVIOCGMTSLOTS((slots + 1) * sizeof(int)), data) < 0) return -1;
    memcpy(values, data + 1, slots * sizeof(int)); return 0;
}
static void frame(int slots, const int *ids, const int *xs, const int *ys, int64_t time_ms) {
    int count = 0;
    for (int i = 0; i < slots; i++) if (ids[i] >= 0) count++;
    printf("F %lld %d", (long long)time_ms, count);
    for (int i = 0; i < slots; i++) if (ids[i] >= 0) printf(" %d:%d:%d:%d", i, ids[i], xs[i], ys[i]);
    putchar('\n');
    if (fflush(stdout) == EOF) stopping = 1;
}
/* Try to restore the exclusive grab only at a neutral contact boundary. The fd stays
 * ungrabbed while waiting, so the system still receives wake gestures. */
static int reacquire_if_idle(int fd, int slots, int *ids, struct input_absinfo *slot) {
    struct input_event events[64];
    while (read(fd, events, sizeof(events)) > 0) { }
    if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), slot) || snapshot(fd, ABS_MT_TRACKING_ID, slots, ids)) return -1;
    for (int i = 0; i < slots; i++) if (ids[i] >= 0) return 0;
    if (ioctl(fd, EVIOCGRAB, 1)) return -1;
    if (snapshot(fd, ABS_MT_TRACKING_ID, slots, ids)) { ioctl(fd, EVIOCGRAB, 0); return -1; }
    for (int i = 0; i < slots; i++) if (ids[i] >= 0) { ioctl(fd, EVIOCGRAB, 0); return 0; }
    return 1;
}
static int stream(int fd, const char *path, const int *expected) {
    struct input_absinfo slot, ax, ay;
    if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &slot) || ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &ax)
            || ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &ay)) return 3;
    int slots = slot.maximum + 1;
    if (expected && (ax.minimum != expected[0] || ax.maximum != expected[1] || ay.minimum != expected[2]
            || ay.maximum != expected[3] || slots != expected[4])) {
        fprintf(stderr, "ERROR selected rear input capabilities changed\n"); return 3;
    }
    if (slot.minimum != 0 || slots < 1 || slots > MAX_CONTACTS || ax.maximum <= ax.minimum || ay.maximum <= ay.minimum) return 3;
    int ids[MAX_CONTACTS], xs[MAX_CONTACTS] = {0}, ys[MAX_CONTACTS] = {0};
    struct input_event events[64];
    int grabbed = 0;
    int clock_id = CLOCK_MONOTONIC;
    if (ioctl(fd, EVIOCSCLOCKID, &clock_id)) return 3;
    int64_t idle_deadline = millis() + 2500;
    while (!stopping && millis() < idle_deadline) {
        /* Discard only pre-grab events, never a new contact received after the grab. */
        while (read(fd, events, sizeof(events)) > 0) { }
        if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &slot)) return 3;
        if (snapshot(fd, ABS_MT_TRACKING_ID, slots, ids)) return 3;
        int active = 0;
        for (int i = 0; i < slots; i++) if (ids[i] >= 0) active++;
        if (!active) {
            if (ioctl(fd, EVIOCGRAB, 1)) { fprintf(stderr, "ERROR grab errno=%d\n", errno); return 4; }
            grabbed = 1;
            if (snapshot(fd, ABS_MT_TRACKING_ID, slots, ids)) { ioctl(fd, EVIOCGRAB, 0); return 3; }
            for (int i = 0; i < slots; i++) if (ids[i] >= 0) active++;
            if (!active) break;
            ioctl(fd, EVIOCGRAB, 0); grabbed = 0;
        }
        usleep(20000);
    }
    if (!grabbed) { fprintf(stderr, "ERROR rear fingers not released\n"); return 5; }
    for (int i = 0; i < slots; i++) ids[i] = -1;
    printf("READY2 0 %s %d %d %d %d %d\n", path, slots, ax.minimum, ax.maximum, ay.minimum, ay.maximum); fflush(stdout);
    int current_slot = slot.value, dropped = 0;
    if (current_slot < 0 || current_slot >= slots) { ioctl(fd, EVIOCGRAB, 0); return 3; }
    int64_t heartbeat = millis();
    ReaderControlState control = {0};
    while (!stopping && millis() - heartbeat < 4000) {
        struct pollfd polls[2] = {{fd, POLLIN, 0}, {STDIN_FILENO, POLLIN | POLLHUP, 0}};
        int status = poll(polls, 2, reader_watchdog_timeout_ms(heartbeat, millis()));
        if (status < 0) { if (errno == EINTR) continue; break; }
        if (status == 0) break; /* Preserve the existing 4 s native watchdog even if stdin stays open. */
        if (polls[1].revents & (POLLHUP | POLLERR | POLLNVAL)) break;
        if (polls[1].revents & POLLIN) {
            char command[64]; ssize_t n = read(STDIN_FILENO, command, sizeof(command));
            if (n <= 0) break;
            for (ssize_t i = 0; i < n; i++) {
                enum ReaderControlAction action = reader_control_stream_byte(&control, command[i]);
                if (action == CONTROL_IGNORE) continue;
                if (action == CONTROL_QUIT) break; /* Q is terminal; ignore all following bytes. */
                if (action == CONTROL_HEARTBEAT) heartbeat = millis();
                if (action == CONTROL_PAUSE) {
                    if (grabbed) { ioctl(fd, EVIOCGRAB, 0); grabbed = 0; }
                    for (int slot_index = 0; slot_index < slots; slot_index++) ids[slot_index] = -1;
                    printf("PAUSED %llu\n", control.epoch); fflush(stdout);
                }
            }
            if (control.quit) break;
        }
        if (polls[0].revents & (POLLHUP | POLLERR | POLLNVAL)) break;
        if (control.paused && control.resume_pending && (control.resume_edge || (polls[0].revents & POLLIN))) {
            int acquired = reacquire_if_idle(fd, slots, ids, &slot);
            if (acquired < 0) break;
            if (acquired > 0) {
                control.paused = 0; control.resume_pending = 0; grabbed = 1; current_slot = slot.value; dropped = 0;
                for (int i = 0; i < slots; i++) ids[i] = -1;
                printf("READY2 %llu %s %d %d %d %d %d\n", control.epoch, path, slots, ax.minimum, ax.maximum, ay.minimum, ay.maximum);
                fflush(stdout);
            }
            control.resume_edge = 0;
            if (control.paused && control.resume_pending && (polls[0].revents & POLLIN)) continue;
        }
        if (!(polls[0].revents & POLLIN)) continue;
        if (control.paused) {
            struct input_event discarded[64]; while (read(fd, discarded, sizeof(discarded)) > 0) { }
            continue;
        }
        ssize_t size = read(fd, events, sizeof(events));
        if (size < 0) { if (errno == EAGAIN || errno == EINTR) continue; break; }
        if (!size || size % sizeof(struct input_event)) break;
        for (size_t i = 0; i < (size_t)size / sizeof(struct input_event); i++) {
            struct input_event *event = events + i;
            if (event->type == EV_SYN && event->code == SYN_DROPPED) { dropped = 1; puts("RESET"); fflush(stdout); continue; }
            if (dropped) {
                if (event->type != EV_SYN || event->code != SYN_REPORT) continue;
                if (snapshot(fd, ABS_MT_TRACKING_ID, slots, ids) || snapshot(fd, ABS_MT_POSITION_X, slots, xs)
                        || snapshot(fd, ABS_MT_POSITION_Y, slots, ys)) { stopping = 1; break; }
                dropped = 0;
                frame(slots, ids, xs, ys, (int64_t)event->time.tv_sec * 1000 + event->time.tv_usec / 1000); continue;
            }
            if (event->type == EV_ABS && event->code == ABS_MT_SLOT) {
                current_slot = event->value;
                if (current_slot < 0 || current_slot >= slots) { stopping = 1; break; }
            } else if (event->type == EV_ABS) {
                if (event->code == ABS_MT_TRACKING_ID) ids[current_slot] = event->value;
                else if (event->code == ABS_MT_POSITION_X) xs[current_slot] = event->value;
                else if (event->code == ABS_MT_POSITION_Y) ys[current_slot] = event->value;
            } else if (event->type == EV_SYN && event->code == SYN_REPORT)
                frame(slots, ids, xs, ys, (int64_t)event->time.tv_sec * 1000 + event->time.tv_usec / 1000);
        }
    }
    ioctl(fd, EVIOCGRAB, 0);
    puts("RELEASED"); fflush(stdout);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--find")) {
        char found[64] = {0}; int count = 0;
        DIR *directory = opendir("/dev/input");
        if (!directory) return 3;
        struct dirent *entry;
        while ((entry = readdir(directory))) {
            if (strncmp(entry->d_name, "event", 5) || !entry->d_name[5]
                    || strspn(entry->d_name + 5, "0123456789") != strlen(entry->d_name + 5)) continue;
            char path[512]; int length = snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
            if (length < 0 || (size_t) length >= sizeof(path) || (size_t) length >= sizeof(found)) continue;
            int fd = checked_open(path, O_RDONLY, "Xiaomi_Touch_Input_1");
            if (fd >= 0) { memcpy(found, path, (size_t) length + 1); count++; close(fd); }
        }
        closedir(directory);
        if (count != 1) { fprintf(stderr, "ERROR expected one rear touch device, found %d\n", count); return 3; }
        puts(found); return 0;
    }
    if (argc < 3) return 2;
    if ((argc != 3 && argc != 9) || strcmp(argv[1], "--stream")) return 2;
    const char *expected_name = argc == 9 ? argv[3] : "Xiaomi_Touch_Input_1";
    if (!*expected_name || !strcmp(expected_name, "Xiaomi_Touch_Input_0")) return 2;
    int expected[5];
    if (argc == 9) for (int i = 0; i < 5; i++) {
        char *end; errno = 0; long value = strtol(argv[i + 4], &end, 10);
        if (errno || !*argv[i + 4] || *end || value < INT32_MIN || value > INT32_MAX) return 2;
        expected[i] = (int) value;
    }
    int fd = checked_open(argv[2], O_RDONLY, expected_name);
    if (fd < 0) { fprintf(stderr, "ERROR open rear input errno=%d\n", errno); return 3; }
    pid_t parent = getppid();
    signal(SIGTERM, stop); signal(SIGINT, stop); signal(SIGPIPE, SIG_IGN);
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent) { close(fd); return 3; }
    prctl(PR_SET_NAME, "remrss-input", 0, 0, 0);
    setvbuf(stdout, NULL, _IOLBF, 0);
    /* A stalled Java reader must never keep the grab alive by blocking stdout. */
    fcntl(STDOUT_FILENO, F_SETFL, fcntl(STDOUT_FILENO, F_GETFL) | O_NONBLOCK);
    int result = stream(fd, argv[2], argc == 9 ? expected : NULL); close(fd); return result;
}
