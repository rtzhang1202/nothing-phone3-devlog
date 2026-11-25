package io.github.rtzhang.keypilot.hook;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.view.KeyEvent;

import io.github.rtzhang.keypilot.config.KeyId;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Per-key three-gesture state machine matching Nothing Phone (3) native thresholds:
 * - Long press: held >= longPressMs (default 500ms)
 * - Double press: 2nd DOWN within doublePressWindowMs (default 300ms) of 1st UP
 * - Short press: released < longPressMs and no 2nd press within doublePressWindowMs
 *
 * Uses a temporary PARTIAL_WAKE_LOCK during active gesture windows so screen-off timers
 * never stall without unnecessarily waking the display.
 */
public final class GestureStateMachine {

    public interface Callback {
        void onShortPress(KeyId key, KeyEvent down1, KeyEvent up1);
        void onLongPress(KeyId key, KeyEvent down1);
        void onLongPressUp(KeyId key, KeyEvent up1);
        void onDoublePress(KeyId key, KeyEvent down1, KeyEvent down2);
    }

    private enum State {
        IDLE,
        DOWN_1,
        WAIT_DOUBLE,
        DOWN_2,
        LONG_FIRED
    }

    private static volatile Handler sFsmHandler;

    private final KeyId keyId;
    private final Callback callback;
    private final Object lock = new Object();

    private State state = State.IDLE;
    private KeyEvent pendingDown1;
    private KeyEvent pendingUp1;
    private Runnable longPressTask;
    private Runnable singlePressTask;
    private PowerManager.WakeLock wakeLock;

    public GestureStateMachine(KeyId keyId, Callback callback) {
        this.keyId = keyId;
        this.callback = callback;
    }

    private static Handler getHandler() {
        Handler h = sFsmHandler;
        if (h == null) {
            synchronized (GestureStateMachine.class) {
                h = sFsmHandler;
                if (h == null) {
                    HandlerThread ht = new HandlerThread("KeyPilot-FSM");
                    ht.start();
                    h = new Handler(ht.getLooper());
                    sFsmHandler = h;
                }
            }
        }
        return h;
    }

    /**
     * Feed a physical DOWN or UP KeyEvent into the state machine.
     * Note: KeyEvent is cloned immediately because Android may recycle the original instance.
     */
    public void onKeyEvent(KeyEvent rawEvent) {
        if (rawEvent == null) {
            return;
        }
        final int action = rawEvent.getAction();
        final int repeat = rawEvent.getRepeatCount();
        final boolean canceled = (rawEvent.getFlags() & KeyEvent.FLAG_CANCELED) != 0;
        final KeyEvent copy = new KeyEvent(rawEvent);

        synchronized (lock) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (repeat > 0) {
                    // Hardware auto-repeat is ignored; our own 500ms timer handles long press.
                    return;
                }
                handleDownLocked(copy);
            } else if (action == KeyEvent.ACTION_UP) {
                handleUpLocked(copy, canceled);
            }
        }
    }

    private void handleDownLocked(final KeyEvent downEvent) {
        KeyPilotConfig cfg = ConfigBridge.getConfig();
        final int longMs = cfg.longPressMs;

        if (state == State.IDLE) {
            acquireWakeLockLocked(longMs + 800);
            state = State.DOWN_1;
            pendingDown1 = downEvent;
            pendingUp1 = null;
            cancelTasksLocked();

            longPressTask = new Runnable() {
                @Override
                public void run() {
                    KeyEvent d1;
                    synchronized (lock) {
                        if (state != State.DOWN_1) {
                            return;
                        }
                        state = State.LONG_FIRED;
                        d1 = pendingDown1;
                        longPressTask = null;
                    }
                    safeDispatchLong(d1);
                }
            };
            getHandler().postDelayed(longPressTask, longMs);
        } else if (state == State.WAIT_DOUBLE) {
            // Second press arrived inside doublePressWindowMs!
            cancelTasksLocked();
            final KeyEvent d1 = pendingDown1;
            state = State.DOWN_2;
            pendingDown1 = null;
            pendingUp1 = null;
            releaseWakeLockLocked();
            safeDispatchDouble(d1, downEvent);
        } else if (state == State.DOWN_2 || state == State.LONG_FIRED) {
            // Stale UP missed; recover cleanly as a fresh DOWN_1
            resetLocked();
            handleDownLocked(downEvent);
        }
    }

    private void handleUpLocked(final KeyEvent upEvent, boolean canceled) {
        if (canceled) {
            resetLocked();
            return;
        }
        KeyPilotConfig cfg = ConfigBridge.getConfig();
        final int doubleMs = cfg.doublePressWindowMs;

        if (state == State.DOWN_1) {
            // Released before longPressMs -> enter double-press wait window
            cancelTasksLocked();
            state = State.WAIT_DOUBLE;
            pendingUp1 = upEvent;
            acquireWakeLockLocked(doubleMs + 500);

            singlePressTask = new Runnable() {
                @Override
                public void run() {
                    KeyEvent d1;
                    KeyEvent u1;
                    synchronized (lock) {
                        if (state != State.WAIT_DOUBLE) {
                            return;
                        }
                        d1 = pendingDown1;
                        u1 = pendingUp1;
                        resetLocked();
                    }
                    safeDispatchShort(d1, u1);
                }
            };
            getHandler().postDelayed(singlePressTask, doubleMs);
        } else if (state == State.LONG_FIRED) {
            resetLocked();
            safeDispatchLongUp(upEvent);
        } else if (state == State.DOWN_2) {
            // Consume UP #2 of double press and return to IDLE
            resetLocked();
        }
    }

    /** Immediately abort any pending gesture recognition (e.g., when a hardware chord is detected). */
    public void cancel() {
        synchronized (lock) {
            resetLocked();
        }
    }

    private void resetLocked() {
        cancelTasksLocked();
        state = State.IDLE;
        pendingDown1 = null;
        pendingUp1 = null;
        releaseWakeLockLocked();
    }

    private void cancelTasksLocked() {
        Handler h = getHandler();
        if (longPressTask != null) {
            h.removeCallbacks(longPressTask);
            longPressTask = null;
        }
        if (singlePressTask != null) {
            h.removeCallbacks(singlePressTask);
            singlePressTask = null;
        }
    }

    private void acquireWakeLockLocked(long timeoutMs) {
        try {
            if (wakeLock == null) {
                Context ctx = ConfigBridge.getSystemContext();
                if (ctx != null) {
                    PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                "KeyPilot:FSM-" + keyId.id);
                        wakeLock.setReferenceCounted(false);
                    }
                }
            }
            if (wakeLock != null) {
                wakeLock.acquire(Math.max(500L, timeoutMs));
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLockLocked() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private void safeDispatchShort(final KeyEvent d1, final KeyEvent u1) {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onShortPress(keyId, d1, u1);
                } catch (Throwable t) {
                    HookLog.e("FSM short callback error on " + keyId.id, t);
                }
            }
        });
    }

    private void safeDispatchLong(final KeyEvent d1) {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onLongPress(keyId, d1);
                } catch (Throwable t) {
                    HookLog.e("FSM long callback error on " + keyId.id, t);
                }
            }
        });
    }

    private void safeDispatchLongUp(final KeyEvent u1) {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onLongPressUp(keyId, u1);
                } catch (Throwable t) {
                    HookLog.e("FSM longUp callback error on " + keyId.id, t);
                }
            }
        });
    }

    private void safeDispatchDouble(final KeyEvent d1, final KeyEvent d2) {
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onDoublePress(keyId, d1, d2);
                } catch (Throwable t) {
                    HookLog.e("FSM double callback error on " + keyId.id, t);
                }
            }
        });
    }
}
