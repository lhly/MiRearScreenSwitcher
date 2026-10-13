package com.tgwgroup.MiRearScreenSwitcher;

import java.util.Map;

/** Small native interface for the existing Android control operations. */
final class NativeAction {
    final String method;
    final Object arguments;

    NativeAction(String method, Object arguments) {
        this.method = method;
        this.arguments = arguments;
    }

    @SuppressWarnings("unchecked")
    <T> T argument(String key) {
        return arguments instanceof Map ? (T) ((Map<?, ?>) arguments).get(key) : null;
    }

    interface Result {
        void success(Object value);
        void error(String code, String message, Object details);
        default void notImplemented() { error("UNSUPPORTED", "Unknown native action", null); }
    }

    interface Callback {
        void complete(Object value, String error);
    }
}
