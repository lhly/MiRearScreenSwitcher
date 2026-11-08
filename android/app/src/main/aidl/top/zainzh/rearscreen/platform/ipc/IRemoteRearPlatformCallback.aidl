package top.zainzh.rearscreen.platform.ipc;

oneway interface IRemoteRearPlatformCallback {
    void onEvent(String eventJson);
}
