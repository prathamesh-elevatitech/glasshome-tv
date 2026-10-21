# Shared by the scripts: which TV to talk to. Usage: TV=192.168.1.20 ./install.sh
TV="${TV:-$1}"
if [[ -z "$TV" ]]; then
  echo "Set your TV's IP address first, e.g.:  TV=192.168.1.20 ./install.sh" >&2
  exit 1
fi
[[ "$TV" == *:* ]] || TV="$TV:5555"
adb connect "$TV" >/dev/null
