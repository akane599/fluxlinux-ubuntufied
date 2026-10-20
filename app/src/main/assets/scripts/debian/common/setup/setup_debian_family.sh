#!/bin/bash
# setup_debian_family.sh
# Generic Post-install configuration for Debian-based Distros (Debian, Ubuntu, Kali, etc.)

DISTRO_NAME="${1:-debian}"

echo "FluxLinux: Configuring ${DISTRO_NAME} (Debian Family)..."

# 1. Update and Install Core Packages
export DEBIAN_FRONTEND=noninteractive
apt update -y || exit 1
apt install -y sudo xfce4 xfce4-goodies dbus-x11 tigervnc-standalone-server || exit 1
# Named Pulse *client* only — do not treat xfce4 as the audio path.
DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
    libpulse0 pulseaudio-utils xfce4-pulseaudio-plugin || \
DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
    libpulse0 pulseaudio-utils || true

# Stock Mesa for first XFCE paint. hw-accel may replace this with Turnip later.
DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
    mesa-utils libgl1-mesa-dri libegl1 mesa-vulkan-drivers \
    || DEBIAN_FRONTEND=noninteractive apt install -y --no-install-recommends \
        mesa-utils libgl1-mesa-dri libegl1 \
    || true
mkdir -p /etc/fluxlinux
printf '%s\n' virgl > /etc/fluxlinux/gpu_mode
echo "FluxLinux: gpu_mode=virgl (first-paint; hw-accel may upgrade)"

# 2. Create User 'flux'
if ! id "flux" &>/dev/null; then
    useradd -m -s /bin/bash flux
    echo "flux:flux" | chpasswd
    usermod -aG sudo flux
fi

# 3. Configure Sudo
echo "flux ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/flux

# 4. Configure VNC for User
mkdir -p /home/flux/.vnc
echo "#!/bin/bash
export PULSE_SERVER=\"unix:/tmp/pulse-native unix:/mnt/host-tmp/pulse-native\"
xrdb $HOME/.Xresources
startxfce4" > /home/flux/.vnc/xstartup
chmod +x /home/flux/.vnc/xstartup
chown -R flux:flux /home/flux/.vnc

if command -v _flux_write_pulse_client >/dev/null 2>&1; then
    _flux_write_pulse_client
else
    # Same list as flux_guest_common.sh FLUX_PULSE_SERVERS: host Pulse is a unix
    # socket only (proot /tmp, chroot /mnt/host-tmp). Never TCP 127.0.0.1:4713.
    _srv="unix:/tmp/pulse-native unix:/mnt/host-tmp/pulse-native"
    mkdir -p /etc/profile.d /etc/pulse/client.conf.d
    printf 'export PULSE_SERVER="%s"\n' "$_srv" > /etc/profile.d/flux-pulse.sh
    chmod 644 /etc/profile.d/flux-pulse.sh 2>/dev/null || true
    if [ -f /etc/environment ]; then
        if grep -q '^PULSE_SERVER=' /etc/environment 2>/dev/null; then
            sed -i "s|^PULSE_SERVER=.*|PULSE_SERVER=\"$_srv\"|" /etc/environment
        else
            printf 'PULSE_SERVER="%s"\n' "$_srv" >> /etc/environment
        fi
    else
        printf 'PULSE_SERVER="%s"\n' "$_srv" > /etc/environment
    fi
    printf 'default-server = %s\nautospawn = no\n' "$_srv" \
        > /etc/pulse/client.conf.d/99-fluxlinux.conf
    if [ -f /etc/pulse/client.conf ]; then
        if grep -qE '^;?[[:space:]]*default-server' /etc/pulse/client.conf 2>/dev/null; then
            sed -i "s|^;*[[:space:]]*default-server.*|default-server = $_srv|" /etc/pulse/client.conf
        else
            printf '\ndefault-server = %s\n' "$_srv" >> /etc/pulse/client.conf
        fi
        if grep -qE '^;?[[:space:]]*autospawn' /etc/pulse/client.conf 2>/dev/null; then
            sed -i 's/^;*[[:space:]]*autospawn.*/autospawn = no/' /etc/pulse/client.conf
        else
            printf 'autospawn = no\n' >> /etc/pulse/client.conf
        fi
    fi
fi

echo "FluxLinux: ${DISTRO_NAME} Setup Complete!"
