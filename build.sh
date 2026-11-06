#!/bin/sh
# Сборка Doodle Jump в APK без Gradle:
# aapt2 (Termux, aarch64) + javac + d8 + apksigner (Java).
set -e

PROJ=$(cd "$(dirname "$0")" && pwd)
BUILD=${1:-/tmp/djbuild}

BT=/tmp/tx
JAR=/tmp/plat/android-35/android.jar
LIBS=$BT/libs
AAPT2=$BT/aapt2bin
D8=$BT/d8/data/data/com.termux/files/usr/share/java/d8.jar
APKSIGNER=$BT/apksigner/data/data/com.termux/files/usr/share/java/apksigner.jar
KS=$PROJ/doodle.jks
KSPASS=doodlejump

aapt2() {
    LD_LIBRARY_PATH=$LIBS /system/bin/linker64 "$AAPT2" "$@"
}

rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/classes" "$BUILD/dex"

echo "== aapt2 compile"
aapt2 compile --dir "$PROJ/res" -o "$BUILD/res/compiled.zip"

echo "== aapt2 link"
aapt2 link -o "$BUILD/base.apk" \
    -I "$JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --min-sdk-version 21 \
    --target-sdk-version 34 \
    "$BUILD/res/compiled.zip"

echo "== javac"
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
    -classpath "$JAR" -d "$BUILD/classes" \
    $(find "$PROJ/src" -name '*.java')

echo "== d8"
java -cp "$D8" com.android.tools.r8.D8 --release --min-api 21 \
    --lib "$JAR" --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class')

echo "== упаковка"
python3 "$PROJ/tools/apkpack.py" "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk"

if [ ! -f "$KS" ]; then
    echo "== создаю ключ подписи"
    keytool -genkeypair -keystore "$KS" -alias doodle -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass "$KSPASS" -keypass "$KSPASS" \
        -dname "CN=Doodle Jump, O=Narezany, C=RU" >/dev/null 2>&1
fi

echo "== apksigner"
java -jar "$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KSPASS" \
    --ks-key-alias doodle --key-pass "pass:$KSPASS" \
    --min-sdk-version 21 --v1-signing-enabled true --v2-signing-enabled true \
    --out "$BUILD/DoodleJump.apk" "$BUILD/unsigned.apk"

java -jar "$APKSIGNER" verify --verbose "$BUILD/DoodleJump.apk" | head -6

echo "готово: $BUILD/DoodleJump.apk"
