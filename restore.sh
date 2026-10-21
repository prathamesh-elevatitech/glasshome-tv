#!/bin/zsh
# Undo everything: Google TV home screen, setup wizard, system animations, and any apps
# removed by debloat.sh.
cd "${0:a:h}"
source ./tv.sh
adb -s $TV shell settings put global transition_animation_scale 1
adb -s $TV shell pm enable com.google.android.tungsten.setupwraith
adb -s $TV shell pm enable com.google.android.apps.tv.launcherx
adb -s $TV shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
[[ -f removed-packages.txt ]] && for p in $(sort -u removed-packages.txt); do
  printf "%-45s " $p
  adb -s $TV shell cmd package install-existing $p
done
