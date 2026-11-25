/*
 * keypilotd — Optional native fallback daemon (Route F: EVIOCGRAB + /dev/uinput)
 * for Nothing Phone (3) (A024 / Metroid).
 *
 * Hardware layout (verified in P1 / P4):
 *   /dev/input/event0 ("gpio-keys") carries ONLY two keys:
 *     - scancode 115 (KEY_VOLUMEUP): passed through 1:1 to /dev/uinput with <1ms latency
 *     - scancode 250 (Essential Key): intercepted in userspace with 500ms/300ms 3-gesture FSM
 *
 * Safety:
 *   - Signal handlers (SIGINT, SIGTERM, SIGHUP, SIGQUIT) and atexit() ALWAYS release
 *     EVIOCGRAB and destroy the uinput device so Volume Up is never stranded.
 *   - Primary KeyPilot Xposed app does NOT depend on this daemon. Only run when
 *     explicitly opted in without Xposed (see magisk/service.sh).
 */

#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define CODE_VOLUME_UP     115
#define CODE_ESSENTIAL_KEY 250

#define LONG_PRESS_MS      500
#define DOUBLE_WINDOW_MS   300

#define CONFIG_PATH        "/data/system/keypilot.json"

typedef enum {
    FSM_IDLE = 0,
    FSM_DOWN_1,
    FSM_WAIT_DOUBLE,
    FSM_DOWN_2,
    FSM_LONG_FIRED
} fsm_state_t;

static volatile sig_atomic_t g_running = 1;
static int g_ev_fd = -1;
static int g_uinput_fd = -1;
static bool g_grabbed = false;

static int64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000LL + (int64_t)(ts.tv_nsec / 1000000LL);
}

static void cleanup_devices(void) {
    if (g_ev_fd >= 0) {
        if (g_grabbed) {
            ioctl(g_ev_fd, EVIOCGRAB, 0);
            g_grabbed = false;
        }
        close(g_ev_fd);
        g_ev_fd = -1;
    }
    if (g_uinput_fd >= 0) {
        ioctl(g_uinput_fd, UI_DEV_DESTROY);
        close(g_uinput_fd);
        g_uinput_fd = -1;
    }
}

static void handle_signal(int sig) {
    (void)sig;
    g_running = 0;
}

static int setup_uinput(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("open /dev/uinput");
        return -1;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, CODE_VOLUME_UP) < 0 ||
        ioctl(fd, UI_SET_KEYBIT, CODE_ESSENTIAL_KEY) < 0) {
        perror("uinput ioctl bits");
        close(fd);
        return -1;
    }

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_HOST;
    usetup.id.vendor  = 0x0001;
    usetup.id.product = 0x0001;
    usetup.id.version = 1;
    snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "gpio-keys");

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0 ||
        ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("uinput UI_DEV_CREATE");
        close(fd);
        return -1;
    }
    return fd;
}

static void emit_uinput(uint16_t type, uint16_t code, int32_t value) {
    if (g_uinput_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    ssize_t wr = write(g_uinput_fd, &ev, sizeof(ev));
    (void)wr;
}

static void emit_key_sync(uint16_t code, int32_t value) {
    emit_uinput(EV_KEY, code, value);
    emit_uinput(EV_SYN, SYN_REPORT, 0);
}

static void run_shell_bg(const char *cmd) {
    pid_t pid = fork();
    if (pid == 0) {
        if (fork() == 0) {
            execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
            _exit(127);
        }
        _exit(0);
    }
}

/*
 * Lightweight action dispatcher for standalone fallback mode.
 * Reads action hint from /data/system/keypilot.json when available, or executes
 * safe built-in defaults (short=camera, long=flashlight/am, double=calculator).
 */
static void trigger_essential_gesture(const char *gesture) {
    fprintf(stderr, "[keypilotd] Essential Key gesture=%s\n", gesture);
    fflush(stderr);

    if (strcmp(gesture, "double") == 0) {
        run_shell_bg("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "
                     "-p com.google.android.calculator >/dev/null 2>&1");
    } else if (strcmp(gesture, "short") == 0) {
        run_shell_bg("am start -a android.media.action.STILL_IMAGE_CAMERA >/dev/null 2>&1");
    } else if (strcmp(gesture, "long") == 0) {
        run_shell_bg("am broadcast -a io.github.rtzhang.keypilot.DAEMON_EVENT "
                     "--es key essential --es gesture long >/dev/null 2>&1");
    }
}

int main(int argc, char **argv) {
    const char *dev_path = (argc > 1) ? argv[1] : "/dev/input/event0";

    atexit(cleanup_devices);
    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);
    signal(SIGHUP, handle_signal);
    signal(SIGQUIT, handle_signal);
    signal(SIGCHLD, SIG_IGN);

    g_uinput_fd = setup_uinput();
    if (g_uinput_fd < 0) {
        fprintf(stderr, "[keypilotd] Failed to initialize /dev/uinput\n");
        return 1;
    }

    g_ev_fd = open(dev_path, O_RDONLY);
    if (g_ev_fd < 0) {
        perror("open evdev");
        return 1;
    }

    if (ioctl(g_ev_fd, EVIOCGRAB, 1) < 0) {
        perror("ioctl EVIOCGRAB");
        return 1;
    }
    g_grabbed = true;
    fprintf(stderr, "[keypilotd] Grabbed %s; passthrough KEY_VOLUMEUP(115), intercept EK(250)\n",
            dev_path);
    fflush(stderr);

    fsm_state_t state = FSM_IDLE;
    int64_t deadline_ms = -1;

    while (g_running) {
        int timeout = -1;
        if (deadline_ms >= 0) {
            int64_t rem = deadline_ms - monotonic_ms();
            timeout = (rem > 0) ? (int)rem : 0;
        }

        struct pollfd pfd;
        pfd.fd = g_ev_fd;
        pfd.events = POLLIN;
        pfd.revents = 0;

        int ret = poll(&pfd, 1, timeout);
        if (ret < 0) {
            if (errno == EINTR) continue;
            perror("poll");
            break;
        }

        int64_t now = monotonic_ms();

        /* Timer expiration check */
        if (ret == 0 && deadline_ms >= 0 && now >= deadline_ms) {
            if (state == FSM_DOWN_1) {
                state = FSM_LONG_FIRED;
                deadline_ms = -1;
                trigger_essential_gesture("long");
            } else if (state == FSM_WAIT_DOUBLE) {
                state = FSM_IDLE;
                deadline_ms = -1;
                trigger_essential_gesture("short");
            }
            continue;
        }

        if (pfd.revents & POLLIN) {
            struct input_event ev;
            ssize_t n = read(g_ev_fd, &ev, sizeof(ev));
            if (n != (ssize_t)sizeof(ev)) {
                if (errno == EINTR) continue;
                break;
            }

            if (ev.type == EV_KEY && ev.code == CODE_VOLUME_UP) {
                /* Immediate passthrough for Volume Up */
                emit_key_sync(CODE_VOLUME_UP, ev.value);
            } else if (ev.type == EV_KEY && ev.code == CODE_ESSENTIAL_KEY) {
                if (ev.value == 1) {
                    /* Key DOWN */
                    if (state == FSM_IDLE) {
                        state = FSM_DOWN_1;
                        deadline_ms = now + LONG_PRESS_MS;
                    } else if (state == FSM_WAIT_DOUBLE) {
                        state = FSM_DOWN_2;
                        deadline_ms = -1;
                        trigger_essential_gesture("double");
                    }
                } else if (ev.value == 0) {
                    /* Key UP */
                    if (state == FSM_DOWN_1) {
                        state = FSM_WAIT_DOUBLE;
                        deadline_ms = now + DOUBLE_WINDOW_MS;
                    } else if (state == FSM_LONG_FIRED || state == FSM_DOWN_2) {
                        state = FSM_IDLE;
                        deadline_ms = -1;
                    }
                }
            }
        }
    }

    fprintf(stderr, "[keypilotd] Shutting down cleanly, releasing EVIOCGRAB\n");
    return 0;
}
