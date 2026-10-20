package top.zainzh.rearscreen;

/** Session-owned navigation feedback and exclusive menu input; never injected into display 1. */
oneway interface IRearGestureListener {
    void onBackGesture(String sessionId, float inward, float y, boolean active, boolean ready, boolean menuArmed);
    void onCastMenu(String sessionId, boolean open);
    void onCastMenuTouch(String sessionId, int action, float x, float y, long downTime, long eventTime);
}
