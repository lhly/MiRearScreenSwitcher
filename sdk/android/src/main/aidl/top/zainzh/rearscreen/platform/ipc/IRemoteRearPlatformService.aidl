package top.zainzh.rearscreen.platform.ipc;

import android.app.PendingIntent;
import top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformCallback;

interface IRemoteRearPlatformService {
    String getContractVersion();
    String queryCapabilities(String callerPackageName);
    void releaseClient(String callerPackageName, IRemoteRearPlatformCallback callback);
    PendingIntent requestSceneActivation(String callerPackageName, String requestJson,
            IRemoteRearPlatformCallback callback);
    String reportSceneWindowReady(String callerPackageName, String launchTicket, String windowReportJson,
            IRemoteRearPlatformCallback callback);
    String reportSceneViewport(String callerPackageName, String sessionId, long generation,
            String windowReportJson, IRemoteRearPlatformCallback callback);
    String attachSceneObserver(String callerPackageName, String sessionId, long generation,
            IRemoteRearPlatformCallback callback);
    String reportSceneVisibility(String callerPackageName, String sessionId, long generation,
            boolean visible, IRemoteRearPlatformCallback callback);
    String queryScenePresentation(String callerPackageName, String sessionId, long generation,
            IRemoteRearPlatformCallback callback);
    String closeScene(String callerPackageName, String sessionId, IRemoteRearPlatformCallback callback);
    PendingIntent requestDataAuthorization(String callerPackageName, String fieldsJson,
            IRemoteRearPlatformCallback callback);
    String queryData(String callerPackageName, String fieldsJson, IRemoteRearPlatformCallback callback);
    String observeData(String callerPackageName, String fieldsJson, long intervalMs,
            IRemoteRearPlatformCallback callback);
    String closeDataObservation(String callerPackageName, String subscriptionId,
            IRemoteRearPlatformCallback callback);
    /** Read-only hardware and capability snapshot; dimensions are physical px, not window dp. */
    String queryRearInfo(String callerPackageName);
    PendingIntent requestRegisteredActionSelection(String callerPackageName,
            IRemoteRearPlatformCallback callback);
    PendingIntent requestRegisteredActionInvocation(String callerPackageName, String refId, int refVersion,
            IRemoteRearPlatformCallback callback);
    /** Independently consented SDK-owned rear notifications; namespace is derived from Binder identity. */
    PendingIntent requestNotificationAuthorization(String callerPackageName, IRemoteRearPlatformCallback callback);
    String publishNotification(String callerPackageName, String requestJson, IRemoteRearPlatformCallback callback);
    String cancelNotification(String callerPackageName, String notificationId, IRemoteRearPlatformCallback callback);
}
