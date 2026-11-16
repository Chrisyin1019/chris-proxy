package hk.chris.proxy;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Drives the production gate through a delayed UI queue, without Android stubs. */
public class UpdateUiGateTest {
    private static final class Fixture {
        final Queue<Runnable> queue = new ArrayDeque<>();
        final AtomicBoolean busy = new AtomicBoolean();
        boolean finishing, destroyed;
        int dialogs, progress, dismissals;
        final UpdateUiGate ui = new UpdateUiGate(() -> !finishing && !destroyed, queue::add, busy);

        void drain() {
            while (!queue.isEmpty()) queue.remove().run();
        }

        void dismiss() { dismissals++; }

        void dialog() { dialogs++; }
    }

    @Test
    public void completionAfterDestructionReleasesBusyWithoutTouchingUi() {
        for (String outcome : new String[]{"check-success", "check-failure", "download-success", "download-failure"}) {
            Fixture f = new Fixture();
            assertTrue(outcome, f.ui.begin());
            f.ui.progress(() -> f.progress++);
            f.ui.complete(f::dismiss, f::dialog);
            f.destroyed = true;
            f.drain();
            assertFalse(outcome + " must release BUSY after destruction", f.busy.get());
            assertEquals(outcome + " dismissed the dead dialog", 1, f.dismissals);
            assertEquals(outcome + " showed a dialog on a dead activity", 0, f.dialogs);
            assertEquals(outcome + " updated dead progress", 0, f.progress);
        }
    }

    @Test
    public void finishingActivityIsTreatedAsDead() {
        Fixture f = new Fixture();
        assertTrue(f.ui.begin());
        f.ui.complete(f::dismiss, f::dialog);
        f.finishing = true;
        f.drain();
        assertFalse(f.busy.get());
        assertEquals(0, f.dialogs);
    }

    @Test
    public void detachedDialogDoesNotLockUpdater() {
        Fixture f = new Fixture();
        assertTrue(f.ui.begin());
        f.ui.complete(() -> {
            assertFalse("BUSY must be released before dismissal", f.busy.get());
            throw new IllegalArgumentException("View not attached to window manager");
        }, f::dialog);
        f.destroyed = true;
        f.drain();
        assertFalse(f.busy.get());
        assertEquals(0, f.dialogs);
    }

    @Test
    public void liveProgressKeepsBusyUntilCompletion() {
        Fixture f = new Fixture();
        assertTrue(f.ui.begin());
        f.ui.progress(() -> f.progress++);
        f.drain();
        assertTrue(f.busy.get());
        assertEquals(1, f.progress);
        f.ui.complete(f::dismiss, f::dialog);
        f.drain();
        assertFalse(f.busy.get());
        assertEquals(1, f.dialogs);
        assertEquals(1, f.dismissals);
    }

    @Test
    public void recreatedActivityWaitsForOldWorkerToFinish() {
        Fixture f = new Fixture();
        assertTrue(f.ui.begin());
        // ON_DESTROY may precede Activity.isDestroyed(): the explicit gate must still close.
        f.ui.destroy(f::dismiss);
        assertTrue("Destroy unlocked a still-writing worker", f.busy.get());
        assertFalse(f.ui.isAlive());

        AtomicBoolean shared = f.busy;
        UpdateUiGate replacement = new UpdateUiGate(() -> true, f.queue::add, shared);
        assertFalse("New activity raced an old partial APK writer", replacement.begin());

        f.ui.progress(() -> f.progress++);
        f.ui.complete(f::dismiss, f::dialog);
        f.drain();
        assertFalse(f.busy.get());
        assertEquals(0, f.dialogs);
        assertEquals(0, f.progress);

        assertTrue("Recreated activity could not check again", replacement.begin());
        f.ui.progress(() -> f.progress++);
        f.drain();
        assertTrue(f.busy.get());
        assertEquals(0, f.progress);
    }

    @Test
    public void staleInstallClicksAreDropped() {
        Fixture f = new Fixture();
        int[] installs = {0};
        Runnable click = () -> f.ui.runIfAlive(() -> installs[0]++);
        f.destroyed = true;
        click.run();
        assertEquals(0, installs[0]);
        assertFalse(f.ui.begin());
        assertFalse(f.busy.get());
    }

    @Test
    public void liveInstallClickRuns() {
        Fixture f = new Fixture();
        int[] installs = {0};
        f.ui.runIfAlive(() -> installs[0]++);
        assertEquals(1, installs[0]);
    }

    @Test
    public void failedProgressWindowShowStillReleasesBusy() {
        Fixture f = new Fixture();
        assertTrue(f.ui.begin());
        f.ui.complete(() -> {
            throw new IllegalStateException("Window already dismissed");
        }, () -> {});
        f.drain();
        assertFalse(f.busy.get());
    }
}
