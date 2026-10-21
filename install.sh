#!/bin/zsh
# Installs the launcher and grants what it needs. Run after every build.
# Usage: TV=<tv-ip> ./install.sh
set -e
cd "${0:a:h}"
source ./tv.sh
PKG=com.pratham.tvhome
SVC=$PKG/$PKG.KeyService
a() { adb -s $TV shell "$@"; }
adb -s $TV install -r --no-incremental out/home.apk
# TCL's boot manager blocks background services without this (harmless elsewhere).
a appops set $PKG APP_AUTO_START allow 2>/dev/null || true
# Lets the home screen switch its remote-button service back on by itself.
a pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
# App Switcher needs usage access; Now Playing needs to be a notification listener.
a appops set $PKG GET_USAGE_STATS allow
a cmd notification allow_listener $PKG/$PKG.MediaListener
# Top Shelf reads Continue Watching / recommendations from the TV's database.
a pm grant $PKG android.permission.READ_TV_LISTINGS
# Wi-Fi network name in Control Center.
a pm grant $PKG android.permission.ACCESS_FINE_LOCATION
# The launcher draws its own open/close animation; the system one would play on top of it.
a settings put global transition_animation_scale 0
# Aerials screensaver.
a settings put secure screensaver_components $PKG/$PKG.AerialDream
# Remote buttons (gear, app keys, double-press Home) via an accessibility service.
a settings put secure enabled_accessibility_services '""'
sleep 1
a settings put secure enabled_accessibility_services $SVC
a settings put secure accessibility_enabled 1
echo "Installed. To make it the home screen, run: TV=$TV ./make-default.sh"
