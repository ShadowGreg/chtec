#!/usr/bin/env bash
# Сборка APK без Android Studio и Gradle — только aapt, javac, dx, zipalign, apksigner.
# Нужен android.jar (API 34): ANDROID_JAR=/путь/к/android.jar ./build_apk.sh
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR="${ANDROID_JAR:?укажите путь к android.jar (платформа API 34)}"
DX="$(command -v dalvik-exchange || command -v dx)"
OUT=build/manual
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

# Gradle берёт имя пакета из build.gradle, а aapt — из манифеста: подставляем его во временную копию.
sed 's|<manifest |<manifest package="ru.chtec" |' app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"

aapt package -f -m --auto-add-overlay \
    -M "$OUT/AndroidManifest.xml" -S app/src/main/res -I "$ANDROID_JAR" -J "$OUT/gen" \
    --min-sdk-version 24 --target-sdk-version 34 --version-code 7 --version-name 1.6 \
    -F "$OUT/app.unaligned.apk"

find app/src/main/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 \
    | grep -v -e '^Picked up' -e 'release 8' -e '^warning' -e '^[0-9]* warning' || true
test -f "$OUT/classes/ru/chtec/MainActivity.class"

"$DX" --dex --min-sdk-version=24 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && aapt add -k app.unaligned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/app.unaligned.apk" "$OUT/app.aligned.apk"

KS=chtec.keystore
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass chtec123 -keypass chtec123 -alias chtec \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Chtec" 2>&1 | grep -v '^Picked up' || true
fi
apksigner sign --ks "$KS" --ks-pass pass:chtec123 --key-pass pass:chtec123 \
    --out build/chtec.apk "$OUT/app.aligned.apk"
apksigner verify build/chtec.apk
echo "Готово: build/chtec.apk ($(du -h build/chtec.apk | cut -f1))"
