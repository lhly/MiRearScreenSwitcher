/* Host regression against the production byte parser and watchdog deadline helper. */
#include "../../main/cpp/rear_input_reader_control.h"
#include <assert.h>

static enum ReaderControlAction feed(ReaderControlState *state, const char *bytes) {
    enum ReaderControlAction last = CONTROL_IGNORE;
    for (size_t i = 0; bytes[i] != '\0'; i++) last = reader_control_stream_byte(state, bytes[i]);
    return last;
}

int main(void) {
    ReaderControlState state = {0};
    assert(feed(&state, "S 1\nR 2\n") == CONTROL_RESUME);
    assert(state.epoch == 2 && state.paused && state.resume_pending && state.resume_edge);

    ReaderControlState split = {0};
    assert(feed(&split, "S 7\n") == CONTROL_PAUSE);
    assert(feed(&split, "R ") == CONTROL_IGNORE);
    assert(feed(&split, "8\n") == CONTROL_RESUME);
    assert(split.epoch == 8 && split.paused && split.resume_pending);
    assert(feed(&split, "S 7\nR 6\n") == CONTROL_IGNORE);
    assert(split.epoch == 8 && split.paused && split.resume_pending);

    /* Rapid ordered transitions leave the newest pause in force. */
    ReaderControlState rapid = {0};
    assert(feed(&rapid, "S 3\nR 4\nS 5\n") == CONTROL_CANCEL_RESUME);
    assert(rapid.epoch == 5 && rapid.paused && !rapid.resume_pending);

    /* A terminal Q consumes the stream; trailing R cannot revive the reader. */
    ReaderControlState terminal = {0};
    assert(feed(&terminal, "Q\nR 9\n") == CONTROL_IGNORE);
    assert(terminal.quit && !terminal.resume_pending && !terminal.resume_edge);

    assert(reader_watchdog_timeout_ms(1000, 1000) == 4000);
    assert(reader_watchdog_timeout_ms(1000, 4999) == 1);
    assert(reader_watchdog_timeout_ms(1000, 5000) == 0);
    return 0;
}
