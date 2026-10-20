#!/data/data/com.termux/files/usr/bin/bash
# start_gui.sh - Launch XFCE4 Desktop Environment in PRoot Distro
# Based on LinuxDroidMaster: https://github.com/LinuxDroidMaster/Termux-Desktops

DISTRO=${1:-debian}

# Kill open X11 processes
kill -9 $(pgrep -f "termux.x11") 2>/dev/null

# Kill any stale VirGL server
pkill -f "virgl_test_server" 2>/dev/null
sleep 1

# PulseAudio on a unix socket in Termux tmp (proot --shared-tmp sees it as
# /tmp/pulse-native). No TCP: anonymous 127.0.0.1:4713 is open to every app.
_pulse_sock="${TMPDIR:-$PREFIX/tmp}/pulse-native"
unset PULSE_SERVER
pulseaudio --start --load="module-native-protocol-unix socket=$_pulse_sock auth-anonymous=1" --exit-idle-time=-1
# Already running (e.g. an older TCP start): drop TCP, add the socket.
for _m in $(pactl list short modules 2>/dev/null | awk '$2 == "module-native-protocol-tcp" {print $1}'); do
    pactl unload-module "$_m" 2>/dev/null
done
[ -S "$_pulse_sock" ] || pactl load-module module-native-protocol-unix socket="$_pulse_sock" auth-anonymous=1 >/dev/null 2>&1

# Start VirGL server (required for GPU acceleration inside PRoot)
echo "FluxLinux: Starting VirGL server..."
virgl_test_server_android &
VIRGL_PID=$!
sleep 2

# Verify VirGL socket appeared (--shared-tmp exposes Termux $TMPDIR as /tmp inside proot)
if [ -S "${TMPDIR}/.virgl_test" ]; then
    echo "FluxLinux: VirGL socket ready at /tmp/.virgl_test"
else
    echo "FluxLinux: [WARN] VirGL socket not found — GPU acceleration may not work"
fi

# Prepare termux-x11 session
export XDG_RUNTIME_DIR=${TMPDIR}
termux-x11 :0 >/dev/null &

# Wait a bit until termux-x11 gets started.
sleep 3

# Launch Termux X11 main activity
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity > /dev/null 2>&1
sleep 1

# Login in PRoot Environment
if [ "$DISTRO" == "termux" ]; then
    export PULSE_SERVER="unix:$_pulse_sock"
    env DISPLAY=:0 startxfce4
else
    proot-distro login $DISTRO --shared-tmp -- /bin/bash -c '
      export DISPLAY=:0
      export PULSE_SERVER=unix:/tmp/pulse-native
      export XDG_RUNTIME_DIR=/tmp
      export VTEST_SOCKET_NAME=/tmp/.virgl_test
      su - flux -c "
        export DISPLAY=:0
        export PULSE_SERVER=unix:/tmp/pulse-native
        export XDG_RUNTIME_DIR=/tmp
        export VTEST_SOCKET_NAME=/tmp/.virgl_test
        # Disable compositor to fix black screen with Turnip GPU driver
        xfconf-query -c xfwm4 -p /general/use_compositing -s false 2>/dev/null
        dbus-launch --exit-with-session startxfce4
      "
    '
fi

exit 0
