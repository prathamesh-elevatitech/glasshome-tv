#!/bin/zsh
# OPTIONAL. Removes preinstalled extras from a TCL / iFFALCON Google TV for the main user only.
# Nothing is deleted from the system; ./restore.sh brings everything back. Review the list
# first and comment out anything you use. Other TV brands: write your own list.
cd "${0:a:h}"
source ./tv.sh
PKGS=(
  com.tcl.waterfall.overseas      # TCL content/ads feed
  com.tcl.messagebox              # TCL promo notifications
  com.tcl.esticker                # store-demo stickers
  com.tcl.exhibit                 # store-demo mode
  com.tcl.channelplus             # TCL free-channels promo
  com.tcl.usercenter              # TCL account
  com.tcl.logkit                  # TCL logging/telemetry
  com.tcl.interactive             # TCL interactive ads
  com.tcl.guard                   # TCL "cleaner"
  com.tcl.gamebar                 # game overlay
  com.tcl.hotelmenu               # hotel mode
  com.tcl.ocean.instructions      # e-manual
  com.tcl.repairguide             # repair guide
  com.tcl.magiconnectfree         # TCL phone-remote app bridge
  com.tcl.smartalexa              # Alexa bridge
  com.google.android.play.games   # Play Games
  com.google.android.feedback     # Google feedback
  com.google.android.apps.tv.dreamx         # Google Ambient screensaver (Aerials replaces it)
  com.google.android.syncadapters.calendar  # calendar sync
  com.android.wallpaper.livepicker
  com.android.printspooler
  # com.tcl.browser                   # TCL browser (the remote's Web button opens it)
  # com.amazon.amazonvideo.livingroom # Prime Video
)
print -l $PKGS >> removed-packages.txt
for p in $PKGS; do
  printf "%-45s " $p
  adb -s $TV shell pm uninstall -k --user 0 $p
done
