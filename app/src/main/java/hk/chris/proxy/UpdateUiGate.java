package hk.chris.proxy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** UI-thread lifecycle gate. Workers retain BUSY until their terminal callback. */
final class UpdateUiGate {
    private final BooleanSupplier activityAlive;
    private final Consumer<Runnable> dispatch;
    private final AtomicBoolean busy;
    private boolean destroyed;

    UpdateUiGate(BooleanSupplier activityAlive, Consumer<Runnable> dispatch, AtomicBoolean busy) {
        this.activityAlive = activityAlive;
        this.dispatch = dispatch;
        this.busy = busy;
    }

    boolean isAlive() { return !destroyed && activityAlive.getAsBoolean(); }

    boolean begin() { return isAlive() && busy.compareAndSet(false, true); }

    void runIfAlive(Runnable update) {
        if (isAlive()) update.run();
    }

    void progress(Runnable update) {
        // Check when the UI queue runs, not when the worker posts its result.
        dispatch.accept(() -> runIfAlive(update));
    }

    void complete(Runnable dismiss, Runnable update) {
        dispatch.accept(() -> {
            busy.set(false);
            dismissSafely(dismiss);
            runIfAlive(update);
        });
    }

    void destroy(Runnable dismiss) {
        destroyed = true;
        // Do not unlock while an old worker can still write the shared APK file.
        dismissSafely(dismiss);
    }

    static void dismissSafely(Runnable dismiss) {
        try { dismiss.run(); }
        catch (IllegalArgumentException | IllegalStateException detachedWindow) {
            // Activity teardown may have already removed the dialog's window.
        }
    }
}
