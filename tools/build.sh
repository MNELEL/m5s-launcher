#!/bin/bash
# Build M5S Launcher. Needs: kotlinc 1.9.22, JDK, zipalign, apksigner, and in $U: android.jar, kotlin-stdlib-1_9_22.jar, r8lib.jar
set -e; U=${U:-.}; K=${K:-kotlinc}; cd "$(dirname "$0")"; mkdir -p classes dex out
$K -jvm-target 1.8 -no-stdlib -no-reflect -cp "$U/android.jar:$U/kotlin-stdlib-1_9_22.jar" -d classes src/il/m5s/launcher/*.kt
tools/aapt2 compile --dir res -o out/res.zip
tools/aapt2 link -o out/base.apk -I $U/android.jar --manifest AndroidManifest.xml --min-sdk-version 24 --target-sdk-version 28 out/res.zip
java -cp $U/r8lib.jar com.android.tools.r8.D8 --lib $U/android.jar --min-api 24 --output dex $(find classes -name '*.class') $U/kotlin-stdlib-1_9_22.jar
cp out/base.apk out/unsigned.apk; (cd dex && zip -q ../out/unsigned.apk classes*.dex)
zipalign -f 4 out/unsigned.apk out/aligned.apk
apksigner sign --ks tools/m5s.jks --ks-pass pass:m5slaunch --key-pass pass:m5slaunch --ks-key-alias m5s --out out/M5S-Launcher.apk out/aligned.apk
