#!/bin/bash
# Puts the PC's Wi-Fi interface into a low-latency state for streaming.
#
# Two things on the receiving laptop cause periodic 100-300 ms stalls:
#  1. 802.11 power save (the AP buffers frames while the card sleeps).
#  2. wpa_supplicant's background scan: NetworkManager sets
#     bgscan="simple:30:-70:86400", i.e. a full channel scan every 30 s
#     whenever the signal is below -70 dBm. Each scan is ~1 s of dropouts.
#
# Both settings are runtime-only and revert on reconnect/reboot.
# Usage: sudo tools/wifi-lowlatency.sh [on|off] [interface]
set -e
mode=${1:-on}
dev=${2:-$(iw dev | awk '$1=="Interface"{print $2; exit}')}
[ -n "$dev" ] || { echo "no wireless interface found" >&2; exit 1; }
net=$(wpa_cli -i "$dev" list_networks 2>/dev/null | awk -F'\t' '/CURRENT/{print $1; exit}')

if [ "$mode" = on ]; then
    iw dev "$dev" set power_save off
    [ -n "$net" ] && wpa_cli -i "$dev" set_network "$net" bgscan '""' >/dev/null
    echo "$dev: power save off, background scanning disabled (until reconnect)"
else
    iw dev "$dev" set power_save on
    [ -n "$net" ] && wpa_cli -i "$dev" set_network "$net" bgscan '"simple:30:-70:86400"' >/dev/null
    echo "$dev: defaults restored"
fi
iw dev "$dev" get power_save
