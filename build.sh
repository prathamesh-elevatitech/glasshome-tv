#!/bin/zsh
# Builds app/ into out/home.apk with the plain Android SDK tools (no Gradle needed).
# Needs: JDK 11+, Android SDK with platforms;android-34 and build-tools;35.0.0
# (ANDROID_HOME, or Homebrew's android-commandlinetools).
set -e
cd "${0:a:h}"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-34/android.jar
[[ -x $BT/aapt2 && -f $JAR ]] || { echo "Install the SDK packages: sdkmanager 'platforms;android-34' 'build-tools;35.0.0'" >&2; exit 1; }

rm -rf out && mkdir -p out/res out/gen out/classes out/dex
$BT/aapt2 compile --dir app/res -o out/res/res.zip
$BT/aapt2 link -o out/unsigned.apk -I $JAR --manifest app/AndroidManifest.xml \
  -A app/assets --java out/gen out/res/res.zip
javac -source 8 -target 8 -nowarn -Xlint:-options -cp $JAR -d out/classes \
  $(find app/src out/gen -name '*.java')
$BT/d8 --release --min-api 28 --lib $JAR --output out/dex $(find out/classes -name '*.class')
cp out/unsigned.apk out/aligned-src.apk
(cd out/dex && zip -q ../aligned-src.apk classes.dex)
$BT/zipalign -f -p 4 out/aligned-src.apk out/aligned.apk

# A local signing key is created on first build; keep it to be able to update the app later.
[[ -f home.keystore ]] || keytool -genkeypair -keystore home.keystore -alias home \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass tvhome -keypass tvhome \
  -dname "CN=TV Home" >/dev/null 2>&1
$BT/apksigner sign --ks home.keystore --ks-pass pass:tvhome --out out/home.apk out/aligned.apk
ls -la out/home.apk
