package top.zainzh.rearscreen;

import android.content.ServiceConnection;

interface PrivilegeBackend {
    enum State { DISCONNECTED, AUTHORIZING, NEEDS_AUTHORIZATION, UNAVAILABLE, DENIED, BINDING, READY, ERROR, SWITCHING }
    interface Prepared { void complete(State state); }
    State availability();
    void prepare(boolean allowRequest, Prepared callback);
    void bind(ServiceConnection connection);
    void unbind(ServiceConnection connection);
}
