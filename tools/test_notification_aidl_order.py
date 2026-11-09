"""Prevent RC4 notification methods from moving any published RC3 Binder slots."""

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SERVICE_AIDL = Path("android/app/src/main/aidl/top/zainzh/rearscreen/platform/ipc/IRemoteRearPlatformService.aidl")
SDK_AIDL = Path("sdk/android/src/main/aidl/top/zainzh/rearscreen/platform/ipc/IRemoteRearPlatformService.aidl")

RC3_METHOD_ORDER = [
    "getContractVersion",
    "queryCapabilities",
    "releaseClient",
    "requestSceneActivation",
    "reportSceneWindowReady",
    "reportSceneViewport",
    "attachSceneObserver",
    "reportSceneVisibility",
    "queryScenePresentation",
    "closeScene",
    "requestDataAuthorization",
    "queryData",
    "observeData",
    "closeDataObservation",
    "queryRearInfo",
    "requestRegisteredActionSelection",
    "requestRegisteredActionInvocation",
]
RC4_METHOD_APPEND = [
    "requestNotificationAuthorization",
    "publishNotification",
    "cancelNotification",
]


def method_order(path: Path) -> list[str]:
    source = path.read_text(encoding="utf-8")
    return re.findall(r"\b(?:String|void|PendingIntent)\s+(\w+)\s*\(", source)


class NotificationAidlOrderTests(unittest.TestCase):
    def test_host_preserves_rc3_transaction_slots_and_appends_notifications(self):
        self.assertEqual(RC3_METHOD_ORDER + RC4_METHOD_APPEND, method_order(ROOT / SERVICE_AIDL))

    def test_sdk_copy_preserves_rc3_transaction_slots_and_appends_notifications(self):
        self.assertEqual(RC3_METHOD_ORDER + RC4_METHOD_APPEND, method_order(ROOT / SDK_AIDL))

    def test_host_and_sdk_aidl_method_orders_are_identical(self):
        self.assertEqual(method_order(ROOT / SERVICE_AIDL), method_order(ROOT / SDK_AIDL))


if __name__ == "__main__":
    unittest.main()
