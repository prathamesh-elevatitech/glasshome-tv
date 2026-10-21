#!/bin/zsh
# Makes this the TV's home screen by switching off the Google TV launcher (and the setup
# wizard, which otherwise grabs the Home button). Nothing is deleted; ./restore.sh undoes it.
set -e
cd "${0:a:h}"
source ./tv.sh
adb -s $TV shell pm disable-user --user 0 com.google.android.apps.tv.launcherx || true
adb -s $TV shell pm disable-user --user 0 com.google.android.tungsten.setupwraith || true
adb -s $TV shell cmd package set-home-activity com.pratham.tvhome/.MainActivity
adb -s $TV shell input keyevent HOME
