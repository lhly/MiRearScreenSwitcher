package top.zainzh.rearscreen.platform.ipc;

import top.zainzh.rearscreen.platform.ipc.IRemoteRearPlatformCallback;
import android.app.PendingIntent;

/** Public bounded control surface. Does not expose ITaskService, shell commands or backend selection. */
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
    /** Caller-visible, user-confirmed data access; callbacks carry scoped snapshot events. */
    PendingIntent requestDataAuthorization(String callerPackageName, String fieldsJson,
            IRemoteRearPlatformCallback callback);
    String queryData(String callerPackageName, String fieldsJson, IRemoteRearPlatformCallback callback);
    String observeData(String callerPackageName, String fieldsJson, long intervalMs,
            IRemoteRearPlatformCallback callback);
    String closeDataObservation(String callerPackageName, String subscriptionId,
            IRemoteRearPlatformCallback callback);
    /** Read-only hardware and capability snapshot; dimensions are physical px, not window dp. */
    String queryRearInfo(String callerPackageName);
    /** Opens the host-owned picker; the returned Activity result contains only an opaque ref. */
    PendingIntent requestRegisteredActionSelection(String callerPackageName,
            IRemoteRearPlatformCallback callback);
    /** Host confirmation Activity is the only path that creates the short-lived invocation grant. */
    PendingIntent requestRegisteredActionInvocation(String callerPackageName, String refId, int refVersion,
            IRemoteRearPlatformCallback callback);
}
