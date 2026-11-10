package top.zainzh.rearscreen;

import static org.junit.Assert.*;
import org.junit.Test;

public final class OrdinaryNotificationChargeReturnTest {
    private static OrdinaryNotificationChargeReturn.Notice notice(String key, long postTime, long sessionId) {
        return new OrdinaryNotificationChargeReturn.Notice(key, postTime, "pkg." + key,
                "title-" + key, "text-" + key, 1234, sessionId);
    }

    @Test public void chargeCoverActivityDestructionPreservesOnlyBoundNotice() {
        OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
        OrdinaryNotificationChargeReturn.Notice a = notice("A", 10, 7);
        lease.noticePresented(a);
        lease.chargingStarted(50, a.sessionId, true, false);

        // Production Activity-destroy callback distinguishes the charge owner from ordinary close.
        lease.ordinaryActivityDestroyed(a.sessionId, 50);
        assertEquals(a.key, lease.noticeCoveredBy(50).key);
        assertNull("a different charge generation cannot use A's lease", lease.noticeCoveredBy(51));
    }

    @Test public void userEndsNoticeBeforeLaterChargeAndCannotResurrectIt() {
        OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
        OrdinaryNotificationChargeReturn.Notice a = notice("A", 10, 7);
        lease.noticePresented(a);

        // The Activity ends while the manager still belongs to A: this is an actual end, not a cover.
        lease.ordinaryActivityDestroyed(a.sessionId, OrdinaryNotificationChargeReturn.NO_SESSION);
        lease.chargingStarted(51, OrdinaryNotificationChargeReturn.NO_SESSION, false, false);

        assertNull(lease.noticeCoveredBy(51));
        assertNull(lease.noticeForSession(a.sessionId));
    }


    @Test public void clickRequestedBeforeDestroyCannotBeInheritedByChargingOwner() {
        OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
        OrdinaryNotificationChargeReturn.Notice a = notice("A", 10, 7);
        lease.noticePresented(a);

        // The real click handler revokes synchronously; B may start before A's Activity reaches onDestroy.
        assertTrue(lease.userHandled(a.sessionId));
        lease.chargingStarted(50, a.sessionId, true, false);
        lease.ordinaryActivityDestroyed(a.sessionId, 50); // production passes the actual current B token
        assertNull("a user-consumed source may remain posted but cannot be continued", lease.noticeCoveredBy(50));
        assertNull(lease.noticeForSession(a.sessionId));
    }

    @Test public void staleUserClickCannotClearNewerNoticeLease() {
        OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
        OrdinaryNotificationChargeReturn.Notice a = notice("A", 10, 7);
        OrdinaryNotificationChargeReturn.Notice b = notice("B", 20, 8);
        lease.noticePresented(a);
        lease.noticePresented(b);

        assertFalse(lease.userHandled(a.sessionId));
        assertTrue("A's delayed UI callback cannot clear B", lease.isCurrent(b));
    }

    @Test public void newerNoticeInvalidatesPreviousChargeContinuation() {
        OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
        OrdinaryNotificationChargeReturn.Notice a = notice("A", 10, 7);
        lease.noticePresented(a);
        lease.chargingStarted(50, a.sessionId, true, false);
        OrdinaryNotificationChargeReturn.Notice b = notice("B", 20, 8);
        lease.noticePresented(b);

        assertNull(lease.noticeCoveredBy(50));
        lease.chargingStarted(51, 50, false, true);
        assertNull("replacement charging cannot inherit A after B took the notice owner", lease.noticeCoveredBy(51));
    }

    @Test public void sameKeyNewPostTimeIsAReplacementNotTheCoveredGeneration() {
        OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
        OrdinaryNotificationChargeReturn.Notice old = notice("same", 10, 7);
        lease.noticePresented(old);
        lease.chargingStarted(50, old.sessionId, true, false);
        OrdinaryNotificationChargeReturn.Notice updated = notice("same", 11, 8);
        lease.noticePresented(updated);

        assertNull(lease.noticeCoveredBy(50));
        assertTrue(lease.isCurrent(updated));
    }

    @Test public void sourceRemovalAndGlobalCloseClearBoundContinuation() {
        OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
        OrdinaryNotificationChargeReturn.Notice a = notice("A", 10, 7);
        lease.noticePresented(a);
        lease.chargingStarted(50, a.sessionId, true, false);
        lease.sourceRemoved(a.key, a.postTime);
        assertNull(lease.noticeCoveredBy(50));

        lease.noticePresented(a);
        lease.chargingStarted(51, a.sessionId, true, false);
        lease.clear(); // service off / listener disconnect / destroy
        assertNull(lease.noticeCoveredBy(51));
    }


    @Test public void chainedChargeOwnerKeepsNoticeLeaseWhenOriginalActivityDestroysLate() {
        OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
        OrdinaryNotificationChargeReturn.Notice a = notice("A", 10, 7);
        lease.noticePresented(a);

        // A→B covers A; C replaces B before A's interrupted Activity delivers onDestroy.
        lease.chargingStarted(50, a.sessionId, true, false);
        lease.chargingStarted(60, 50, false, true);
        lease.ordinaryActivityDestroyed(a.sessionId, 60); // manager's actual current CHARGING owner
        assertEquals("A remains bound to the current inherited charge", a.key, lease.noticeCoveredBy(60).key);

        final long[] owner = {60};
        final int[] launchCount = {0};
        OrdinaryNotificationChargeReturn.Host host = new OrdinaryNotificationChargeReturn.Host() {
            @Override public OrdinaryNotificationChargeReturn.Notice currentPersistentNotice(long chargeId) {
                return chargeId == owner[0] ? lease.noticeCoveredBy(chargeId) : null;
            }
            @Override public OrdinaryNotificationChargeReturn.Notice refreshForCurrentPolicy(
                    OrdinaryNotificationChargeReturn.Notice notice) { return lease.isCurrent(notice) ? notice : null; }
            @Override public void discardIfCurrent(OrdinaryNotificationChargeReturn.Notice notice) { lease.clear(); }
            @Override public OrdinaryNotificationChargeReturn.ResumedSession beginAfterCharge(
                    OrdinaryNotificationChargeReturn.Notice notice, long chargeId, int taskId) {
                if (owner[0] != chargeId || lease.noticeCoveredBy(chargeId) == null) return null;
                owner[0] = OrdinaryNotificationChargeReturn.NO_SESSION; // successful exact-owner transfer
                lease.resumed(notice, notice, 70);
                return new OrdinaryNotificationChargeReturn.ResumedSession(70, taskId, notice.withSessionId(70));
            }
            @Override public void launch(OrdinaryNotificationChargeReturn.Notice notice,
                    OrdinaryNotificationChargeReturn.ResumedSession resumed) { launchCount[0]++; }
        };

        assertFalse("stale B completion cannot consume or transfer C's lease",
                OrdinaryNotificationChargeReturn.resumeAfterCharge(host, 50, 1));
        assertEquals(0, launchCount[0]);
        assertTrue("C restores A exactly once", OrdinaryNotificationChargeReturn.resumeAfterCharge(host, 60, 1));
        assertFalse("duplicate C completion cannot restore twice",
                OrdinaryNotificationChargeReturn.resumeAfterCharge(host, 60, 1));
        assertEquals(1, launchCount[0]);
    }

    @Test public void userClosedOrRemovedNoticeIsNotInheritedByChainedCharges() {
        for (boolean removeSource : new boolean[] {false, true}) {
            OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
            OrdinaryNotificationChargeReturn.Notice a = notice("A", 10, 7);
            lease.noticePresented(a);
            lease.chargingStarted(50, a.sessionId, true, false);
            if (removeSource) lease.sourceRemoved(a.key, a.postTime);
            else lease.ordinaryActivityDestroyed(a.sessionId, OrdinaryNotificationChargeReturn.NO_SESSION);
            lease.chargingStarted(60, 50, false, true);
            lease.ordinaryActivityDestroyed(a.sessionId, 60);
            assertNull("closed/retracted A cannot regain a chained charge lease", lease.noticeCoveredBy(60));
        }
    }

    @Test public void managerTransferRequiresTheExactLiveChargeOwner() {
        assertFalse(PersistentNotificationChargeGate.canTransfer(false, 50, 50));
        assertFalse(PersistentNotificationChargeGate.canTransfer(true, 51, 50));
        assertFalse(PersistentNotificationChargeGate.canTransfer(true, 50, OrdinaryNotificationChargeReturn.NO_SESSION));
        assertTrue(PersistentNotificationChargeGate.canTransfer(true, 50, 50));
    }

    @Test public void successfulHandoffChangesTheOwnerSessionWithoutLosingNoticeIdentity() {
        OrdinaryNotificationChargeReturn.Lease lease = new OrdinaryNotificationChargeReturn.Lease();
        OrdinaryNotificationChargeReturn.Notice a = notice("A", 10, 7);
        lease.noticePresented(a);
        lease.chargingStarted(50, a.sessionId, true, false);
        lease.resumed(a, a.withContent("safe title", "safe content", 99), 80);

        OrdinaryNotificationChargeReturn.Notice resumed = lease.noticeForSession(80);
        assertNotNull(resumed);
        assertEquals(a.key, resumed.key);
        assertEquals(a.postTime, resumed.postTime);
        assertEquals("safe title", resumed.title);
        assertNull("the completed charge no longer owns a continuation lease", lease.noticeCoveredBy(50));
    }
}
