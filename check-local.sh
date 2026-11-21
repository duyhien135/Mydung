#!/bin/bash
# Kiểm tra cục bộ GĐ6: biên dịch file server thuần + stub Android bằng kotlinc,
# chạy toàn bộ unit test bằng JUnit. File cần Android SDK/libadb/Compose
# (ServerManager, TouchPrefs, MainActivity, màn UI, OverlayService) KHÔNG biên
# dịch được ở đây — chỉ rà soát bằng tay, CI là vòng kiểm tra cuối.
set -e
export JAVA_HOME=/home/hatch/workspace/toolcheck/jdk-17.0.21+9
export PATH=$JAVA_HOME/bin:$PATH
KOTLINC=/home/hatch/workspace/toolcheck/kotlinc/bin/kotlinc
TC=/home/hatch/workspace/toolcheck
REPO=/home/hatch/workspace/keymapper/repo
OUT=/tmp/mydung-stage6-check
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/test-classes"

SRC="$REPO/app/src/main/java/com/example/mydung/server"
STUBS="$TC/stubs"

echo "== Biên dịch code nguồn (trừ ServerManager, TouchPrefs) =="
"$KOTLINC" -d "$OUT/classes" \
  "$SRC/Config.kt" "$SRC/LinuxKey.kt" "$SRC/InputEvent.kt" "$SRC/FrameAssembler.kt" \
  "$SRC/InputBus.kt" "$SRC/InputProtocol.kt" "$SRC/Grabber.kt" "$SRC/DeviceScanner.kt" \
  "$SRC/InputHub.kt" "$SRC/InputClient.kt" "$SRC/Server.kt" \
  "$SRC/TouchEngine.kt" "$SRC/TouchMapper.kt" "$SRC/TouchProtocol.kt" \
  "$SRC/Injector.kt" "$SRC/TouchHub.kt" \
  "$SRC/Json.kt" "$SRC/Profile.kt" "$SRC/ProfileStore.kt" \
  "$SRC/TouchInjector.kt" "$SRC/Evdev.kt" "$SRC/RawEvdevInjector.kt" \
  "$SRC/Shell.kt" "$SRC/AutoSwitch.kt" "$SRC/ForegroundWatcher.kt" \
  "$STUBS/androidx/annotation/Keep.kt" \
  "$STUBS/android/os/Process.kt" "$STUBS/android/os/IBinder.kt" "$STUBS/android/os/SystemClock.kt" \
  "$STUBS/android/view/InputEvent.kt" "$STUBS/android/view/InputDevice.kt" "$STUBS/android/view/MotionEvent.kt" \
  "$STUBS/android/app/ActivityManager.kt" \
  2>&1 | grep -v "^warning:" || true

echo "== Biên dịch test =="
TEST="$REPO/app/src/test/java/com/example/mydung"
"$KOTLINC" -cp "$OUT/classes:$TC/junit.jar:$TC/hamcrest.jar" -d "$OUT/test-classes" \
  "$TEST/ExampleUnitTest.kt" \
  "$TEST/server/InputEventParserTest.kt" "$TEST/server/FrameAssemblerTest.kt" \
  "$TEST/server/TouchEngineTest.kt" "$TEST/server/TouchMapperTest.kt" "$TEST/server/TouchProtocolTest.kt" \
  "$TEST/server/JsonTest.kt" "$TEST/server/ProfileCodecTest.kt" "$TEST/server/ProfileStoreTest.kt" \
  "$TEST/server/EvdevTest.kt" "$TEST/server/ForegroundTest.kt" \
  "$TEST/server/AutoSwitchTest.kt" "$TEST/server/ShellTest.kt" \
  "$TEST/server/ConfigTest.kt" \
  2>&1 | grep -v "^warning:" || true

echo "== Chạy unit test =="
java -cp "$OUT/classes:$OUT/test-classes:$TC/junit.jar:$TC/hamcrest.jar:$TC/kotlinc/lib/kotlin-stdlib.jar" \
  org.junit.runner.JUnitCore \
  com.example.mydung.ExampleUnitTest \
  com.example.mydung.server.InputEventParserTest \
  com.example.mydung.server.DevicesParserTest \
  com.example.mydung.server.FrameAssemblerTest \
  com.example.mydung.server.InputProtocolTest \
  com.example.mydung.server.GrabberTest \
  com.example.mydung.server.TouchEngineTest \
  com.example.mydung.server.TouchMapperTest \
  com.example.mydung.server.TouchProtocolTest \
  com.example.mydung.server.JsonTest \
  com.example.mydung.server.ProfileCodecTest \
  com.example.mydung.server.ProfileStoreTest \
  com.example.mydung.server.EvdevTest \
  com.example.mydung.server.ForegroundTest \
  com.example.mydung.server.AutoSwitchTest \
  com.example.mydung.server.ShellTest \
  com.example.mydung.server.ConfigTest
