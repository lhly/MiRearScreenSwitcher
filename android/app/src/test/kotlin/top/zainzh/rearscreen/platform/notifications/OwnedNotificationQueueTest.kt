package top.zainzh.rearscreen.platform.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import top.zainzh.rearscreen.platform.SessionOwner
import top.zainzh.rearscreen.platform.SessionPrincipal

class OwnedNotificationQueueTest {
    private class FakePresentation : OwnedNotificationPresentation {
        data class Pending(
            val item: OwnedNotificationItem,
            val displayed: () -> Unit,
            val ended: (NotificationEndReason) -> Unit,
            val handle: Int,
        )
        val pending = CopyOnWriteArrayList<Pending>()
        val cancelled = CopyOnWriteArrayList<Pair<Int, NotificationEndReason>>()
        val suspended = CopyOnWriteArrayList<Int>()
        var deferNext = false
        @Volatile var deferredCount = 0
        override fun present(
            item: OwnedNotificationItem,
            onDisplayed: () -> Unit,
            onEnded: (NotificationEndReason) -> Unit,
        ): PresentationResult {
            if (deferNext) { deferNext = false; deferredCount++; return PresentationResult.Deferred }
            val handle = pending.size
            pending += Pending(item, onDisplayed, onEnded, handle)
            return PresentationResult.Started(handle)
        }
        override fun cancel(handle: Any, reason: NotificationEndReason) {
            cancelled += (handle as Int) to reason
        }
        override fun suspend(handle: Any) { suspended += handle as Int }
    }

    private class OccupiedPresentation : OwnedNotificationPresentation {
        data class Presented(
            val item: OwnedNotificationItem,
            val displayed: () -> Unit,
            val ended: (NotificationEndReason) -> Unit,
            val handle: Int,
        )

        val presented = CopyOnWriteArrayList<Presented>()
        @Volatile private var occupiedHandle: Int? = null
        private var nextHandle = 0
        @Volatile private var blockedHandle: Int? = null
        @Volatile private var cancelEntered = CountDownLatch(0)
        @Volatile private var releaseCancel = CountDownLatch(0)

        @Synchronized override fun present(
            item: OwnedNotificationItem,
            onDisplayed: () -> Unit,
            onEnded: (NotificationEndReason) -> Unit,
        ): PresentationResult {
            if (occupiedHandle != null) return PresentationResult.Deferred
            val handle = nextHandle++
            occupiedHandle = handle
            presented += Presented(item, onDisplayed, onEnded, handle)
            return PresentationResult.Started(handle)
        }

        override fun cancel(handle: Any, reason: NotificationEndReason) {
            val exact = handle as Int
            if (blockedHandle == exact) {
                cancelEntered.countDown()
                check(releaseCancel.await(3, TimeUnit.SECONDS)) { "controlled cancellation was not released" }
            }
            synchronized(this) { if (occupiedHandle == exact) occupiedHandle = null }
        }

        override fun suspend(handle: Any) { cancel(handle, NotificationEndReason.COVERED) }

        fun holdCancel(handle: Int): CountDownLatch {
            val entered = CountDownLatch(1)
            blockedHandle = handle
            cancelEntered = entered
            releaseCancel = CountDownLatch(1)
            return entered
        }

        fun releaseHeldCancel() { releaseCancel.countDown() }
    }

    private fun owner(packageName: String, epoch: Long): NotificationOwner {
        val principal = SessionPrincipal.AndroidClient(1000, packageName, "signer-$packageName", epoch)
        val session = SessionOwner(1000, packageName, "signer-$packageName", principal)
        return NotificationOwner(session, epoch.toString(), "sdk:1000:$packageName:signer-$packageName") { true }
    }

    private fun queue(presentation: FakePresentation, time: () -> Long = { 100L }) = OwnedNotificationQueue(
        presentation, { _, request -> NotificationPolicyResult.Allowed(request) }, { it.isCurrent() }, time
    )

    @Test fun sameOwnerIdUpdatesAndCancelCannotTouchAnotherOwner() {
        val renderer = FakePresentation()
        val queue = queue(renderer)
        try {
            val firstOwner = owner("sample.one", 1)
            val otherOwner = owner("sample.two", 2)
            val events = CopyOnWriteArrayList<NotificationEvent>()
            assertEquals(NotificationSubmitStatus.ACCEPTED,
                queue.publish(firstOwner, NotificationRequest("notice", "first"), NotificationObserver(events::add)).status)
            await { renderer.pending.size == 1 }
            val firstKey = renderer.pending.first().item.requestId
            assertEquals(NotificationSubmitStatus.ACCEPTED,
                queue.publish(firstOwner, NotificationRequest("notice", "updated"), NotificationObserver(events::add)).status)
            await { renderer.pending.size == 2 && events.any { it.requestId == firstKey && it.endedBy == NotificationEndReason.REPLACED } }
            assertEquals("replaced", events.single { it.requestId == firstKey }.reason)
            assertEquals(NotificationSubmitStatus.ACCEPTED,
                queue.publish(otherOwner, NotificationRequest("notice", "other owner"), NotificationObserver(events::add)).status)

            val cancel = queue.cancel(firstOwner, "notice")
            assertEquals(NotificationSubmitStatus.ACCEPTED, cancel.status)
            queue.presentationAvailable()
            await { renderer.pending.size == 3 && events.any { it.id == "notice" && it.endedBy == NotificationEndReason.CANCELLED } }
            renderer.pending.last().displayed()
            assertTrue(events.any { it.requestId == renderer.pending.last().item.requestId && it.status == NotificationEventStatus.DISPLAYED })
            assertNull(renderer.cancelled.firstOrNull { it.first == renderer.pending.last().handle })
        } finally { queue.close() }
    }

    @Test fun quotaRateLimitAndPolicySuppressionAreExplicit() {
        val renderer = FakePresentation()
        var at = 100L
        var policy: NotificationPolicyResult = NotificationPolicyResult.Allowed(NotificationRequest("x", "x"))
        val queue = OwnedNotificationQueue(renderer, { _, request ->
            when (policy) {
                is NotificationPolicyResult.Allowed -> NotificationPolicyResult.Allowed(request)
                else -> policy
            }
        }, { it.isCurrent() }, { at })
        try {
            val owner = owner("sample.quota", 10)
            for (i in 1..OwnedNotificationQueue.MAX_PER_OWNER) {
                assertEquals(NotificationSubmitStatus.ACCEPTED,
                    queue.publish(owner, NotificationRequest("n$i", "Notice $i")).status)
            }
            at += 2_000
            assertEquals("QUOTA_EXCEEDED", queue.publish(owner, NotificationRequest("fifth", "Notice 5")).reason)
            policy = NotificationPolicyResult.Suppressed("dnd", NotificationEndReason.DND)
            at += 2_000
            val suppressed = queue.publish(owner("sample.suppressed", 11), NotificationRequest("blocked", "Blocked"))
            assertEquals(NotificationSubmitStatus.SUPPRESSED, suppressed.status)
            assertEquals("dnd", suppressed.reason)
        } finally { queue.close() }
    }

    @Test fun coveredNoticeResumesWithoutDuplicateDisplayedEvent() {
        val renderer = FakePresentation()
        val queue = queue(renderer)
        try {
            val events = CopyOnWriteArrayList<NotificationEvent>()
            val owner = owner("sample.cover", 12)
            val result = queue.publish(owner, NotificationRequest("resume", "Resume"), NotificationObserver(events::add))
            assertEquals(NotificationSubmitStatus.ACCEPTED, result.status)
            await { renderer.pending.size == 1 }
            renderer.pending[0].displayed()
            renderer.pending[0].ended(NotificationEndReason.COVERED)
            assertEquals(0, events.count { it.status == NotificationEventStatus.ENDED })
            queue.presentationAvailable()
            await { renderer.pending.size == 2 }
            renderer.pending[1].displayed()
            assertEquals(1, events.count { it.status == NotificationEventStatus.DISPLAYED })
            assertFalse(renderer.cancelled.any { it.second == NotificationEndReason.COVERED })
            assertNotNull(result.requestId)
        } finally { queue.close() }
    }

    @Test fun coveredHandleIsCancelledWithoutRestoringItsOldContent() {
        val renderer = FakePresentation()
        val queue = queue(renderer)
        try {
            val owner = owner("sample.cancel.covered", 20)
            queue.publish(owner, NotificationRequest("covered", "old"))
            await { renderer.pending.size == 1 }
            renderer.pending.single().ended(NotificationEndReason.COVERED)
            assertEquals(NotificationSubmitStatus.ACCEPTED, queue.cancel(owner, "covered").status)
            queue.presentationAvailable()
            await { renderer.cancelled.any { it.first == renderer.pending.single().handle } }
            assertTrue(renderer.cancelled.any { it.first == renderer.pending.single().handle && it.second == NotificationEndReason.CANCELLED })
        } finally { queue.close() }
    }

    @Test fun privacyTighteningEndsPreviouslyRenderedCleartextAndDeferredItemsRetry() {
        val renderer = FakePresentation()
        var hideText = false
        val queue = OwnedNotificationQueue(renderer, { _, request ->
            NotificationPolicyResult.Allowed(if (hideText) request.copy(text = "private") else request)
        }, { it.isCurrent() }, { 100L })
        try {
            val owner = owner("sample.privacy", 21)
            val events = CopyOnWriteArrayList<NotificationEvent>()
            queue.publish(owner, NotificationRequest("privacy", "title", "clear"), NotificationObserver(events::add))
            await { renderer.pending.size == 1 }
            hideText = true
            queue.policyChanged()
            await { events.any { it.endedBy == NotificationEndReason.PRIVACY_POLICY } }
            assertTrue(events.any { it.endedBy == NotificationEndReason.PRIVACY_POLICY })
            await { renderer.cancelled.size == 1 }
            assertEquals(1, renderer.cancelled.size)

            renderer.deferNext = true
            val queuedOwner = owner("sample.deferred", 22)
            val accepted = queue.publish(queuedOwner, NotificationRequest("deferred", "retry"))
            assertEquals(NotificationSubmitStatus.ACCEPTED, accepted.status)
            await { renderer.deferredCount == 1 }
            queue.presentationAvailable()
            await { renderer.pending.size == 2 }
        } finally { queue.close() }
    }

    @Test fun reconnectDoesNotResetIdentityRateLimit() {
        val renderer = FakePresentation()
        val queue = OwnedNotificationQueue(renderer, { _, request -> NotificationPolicyResult.Allowed(request) },
            { it.isCurrent() }, { 100L }, maxPerOwner = 20, maxGlobal = 32)
        try {
            val oldConnection = owner("sample.rate", 30)
            repeat(OwnedNotificationQueue.BURST_LIMIT) { index ->
                assertEquals(NotificationSubmitStatus.ACCEPTED, queue.publish(oldConnection,
                    NotificationRequest("stable", "update $index")).status)
            }
            val reconnected = owner("sample.rate", 31)
            assertEquals("RATE_LIMITED", queue.publish(reconnected,
                NotificationRequest("stable", "reconnect")).reason)
        } finally { queue.close() }
    }

    @Test fun observerCallbackDoesNotHoldQueueLockAcrossExternalCode() {
        val renderer = FakePresentation()
        val owner = owner("sample.callback.lock", 40)
        lateinit var stateQueue: OwnedNotificationQueue
        val callbackFinished = CountDownLatch(1)
        val nestedPublishCompleted = AtomicBoolean(false)
        stateQueue = queue(renderer)
        try {
            val observer = NotificationObserver { event ->
                if (event.endedBy == NotificationEndReason.REPLACED) {
                    Thread {
                        stateQueue.publish(owner, NotificationRequest("callback-probe", "probe"))
                        nestedPublishCompleted.set(true)
                        callbackFinished.countDown()
                    }.start()
                    if (!callbackFinished.await(1, TimeUnit.SECONDS)) callbackFinished.countDown()
                }
            }
            stateQueue.publish(owner, NotificationRequest("lock", "first"), observer)
            stateQueue.publish(owner, NotificationRequest("lock", "updated"), observer)
            assertTrue(callbackFinished.await(2, TimeUnit.SECONDS))
            assertTrue("observer re-entry must not wait for the lock held by its caller", nestedPublishCompleted.get())
        } finally { stateQueue.close() }
    }


    @Test fun rendererCancelCompletesBeforeSameIdReplacementAndWaitingNextItemArePresented() {
        val renderer = OccupiedPresentation()
        val stateQueue = OwnedNotificationQueue(
            renderer,
            { _, request -> NotificationPolicyResult.Allowed(request) },
            { it.isCurrent() },
            { 100L },
        )
        val owner = owner("sample.cancel.serial", 42)
        try {
            assertEquals(NotificationSubmitStatus.ACCEPTED,
                stateQueue.publish(owner, NotificationRequest("notice", "A")).status)
            await { renderer.presented.size == 1 }
            val firstHandle = renderer.presented.single().handle

            val updateCancel = renderer.holdCancel(firstHandle)
            assertEquals(NotificationSubmitStatus.ACCEPTED,
                stateQueue.publish(owner, NotificationRequest("notice", "A2")).status)
            assertTrue("the replacement waits for the occupied renderer's release", updateCancel.await(2, TimeUnit.SECONDS))
            assertEquals("A remains the only render until its exact handle is released", 1, renderer.presented.size)
            renderer.releaseHeldCancel()
            await { renderer.presented.size == 2 }
            assertEquals("A2", renderer.presented[1].item.request.title)

            assertEquals(NotificationSubmitStatus.ACCEPTED,
                stateQueue.publish(owner, NotificationRequest("next", "B")).status)
            assertEquals("equal priority B waits behind the active A2", 2, renderer.presented.size)

            val activeHandle = renderer.presented[1].handle
            val nextCancel = renderer.holdCancel(activeHandle)
            assertEquals(NotificationSubmitStatus.ACCEPTED, stateQueue.cancel(owner, "notice").status)
            assertTrue("the next item remains queued while A2 owns the renderer", nextCancel.await(2, TimeUnit.SECONDS))
            assertEquals(2, renderer.presented.size)
            renderer.releaseHeldCancel()
            await { renderer.presented.size == 3 }
            assertEquals("B", renderer.presented[2].item.request.title)
        } finally { stateQueue.close() }
    }

    @Test fun committedSceneTakeoverClosesOnlyThePreCommitBatch() {
        val renderer = FakePresentation()
        val events = CopyOnWriteArrayList<NotificationEvent>()
        val owner = owner("sample.scene.takeover", 41)
        val stateQueue = queue(renderer)
        try {
            stateQueue.publish(owner, NotificationRequest("visible", "visible"), NotificationObserver(events::add))
            stateQueue.publish(owner, NotificationRequest("waiting", "waiting"), NotificationObserver(events::add))
            stateQueue.closeAll(NotificationEndReason.SCENE_TAKEOVER)
            await { events.count { it.endedBy == NotificationEndReason.SCENE_TAKEOVER } == 2 }
            val oldPresentationCount = renderer.pending.size
            stateQueue.presentationAvailable()
            assertEquals("the pre-commit batch cannot pump back over the committed owner", oldPresentationCount, renderer.pending.size)

            assertEquals(NotificationSubmitStatus.ACCEPTED,
                stateQueue.publish(owner, NotificationRequest("after-commit", "allowed after commit")).status)
            await { renderer.pending.size == oldPresentationCount + 1 }
        } finally { stateQueue.close() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("condition timed out", condition())
    }
}
