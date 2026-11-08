/* Shared production protocol parser and watchdog deadline; kept independent of Android headers. */
#ifndef REMRSS_REAR_INPUT_READER_CONTROL_H
#define REMRSS_REAR_INPUT_READER_CONTROL_H
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

typedef struct {
    int paused;
    int resume_pending;
    int resume_edge;
    int quit;
    unsigned long long epoch;
    char line[64];
    size_t line_size;
    int overflow;
} ReaderControlState;
enum ReaderControlAction { CONTROL_IGNORE, CONTROL_HEARTBEAT, CONTROL_PAUSE, CONTROL_CANCEL_RESUME, CONTROL_RESUME, CONTROL_QUIT };

static enum ReaderControlAction reader_control_line(ReaderControlState *state, const char *line) {
    if (state->quit) return CONTROL_IGNORE;
    if (!strcmp(line, "Q")) { state->quit = 1; return CONTROL_QUIT; }
    if (!strcmp(line, "P")) return CONTROL_HEARTBEAT;
    char command = 0, trailing = 0;
    unsigned long long epoch = 0;
    if (sscanf(line, "%c %llu%c", &command, &epoch, &trailing) != 2
            || (command != 'S' && command != 'R')) return CONTROL_IGNORE;
    if (epoch < state->epoch) return CONTROL_IGNORE;
    state->epoch = epoch;
    if (command == 'S') {
        if (!state->paused) {
            state->paused = 1; state->resume_pending = 0; state->resume_edge = 0;
            return CONTROL_PAUSE;
        }
        state->resume_pending = 0; state->resume_edge = 0;
        return CONTROL_CANCEL_RESUME;
    }
    if (command == 'R' && state->paused) {
        state->resume_pending = 1; state->resume_edge = 1;
        return CONTROL_RESUME;
    }
    return CONTROL_IGNORE;
}

static enum ReaderControlAction reader_control_stream_byte(ReaderControlState *state, char byte) {
    if (state->quit) return CONTROL_IGNORE;
    if (byte != '\n') {
        if (state->line_size + 1 < sizeof(state->line)) state->line[state->line_size++] = byte;
        else state->overflow = 1;
        return CONTROL_IGNORE;
    }
    if (state->overflow) { state->line_size = 0; state->overflow = 0; return CONTROL_IGNORE; }
    state->line[state->line_size] = '\0'; state->line_size = 0;
    return reader_control_line(state, state->line);
}

static int reader_watchdog_timeout_ms(int64_t heartbeat, int64_t now) {
    int64_t remaining = heartbeat + 4000 - now;
    if (remaining <= 0) return 0;
    return remaining > 4000 ? 4000 : (int) remaining;
}
#endif
