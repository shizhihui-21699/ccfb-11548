#!/usr/bin/env bash
#
# Fails if a build declares any permission that could exfiltrate data.
#
# Run after `./gradlew :app:assembleDebug`. This is a CI gate, not documentation:
# the privacy claim in docs/DESIGN.md is only true while this passes.
#
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"

AAPT2="$SDK/build-tools/35.0.0/aapt2"
if [[ ! -x "$AAPT2" ]]; then
  AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
fi
if [[ ! -x "$AAPT2" ]]; then
  echo "aapt2 not found; set ANDROID_HOME" >&2
  exit 2
fi
if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK (run ./gradlew :app:assembleDebug)" >&2
  exit 2
fi

echo "Checking permissions in $APK"
perms="$("$AAPT2" dump permissions "$APK")"
echo "$perms"
echo

# Anything on this list means the "cannot leave the device" guarantee is broken.
FORBIDDEN=(
  android.permission.INTERNET
  android.permission.ACCESS_NETWORK_STATE
  android.permission.CHANGE_NETWORK_STATE
  android.permission.READ_EXTERNAL_STORAGE
  android.permission.WRITE_EXTERNAL_STORAGE
  android.permission.MANAGE_EXTERNAL_STORAGE
  android.permission.READ_MEDIA_IMAGES
  android.permission.READ_MEDIA_VIDEO
  android.permission.ACCESS_FINE_LOCATION
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.RECORD_AUDIO
  android.permission.BLUETOOTH
  android.permission.CALL_PHONE
)

failed=0
for p in "${FORBIDDEN[@]}"; do
  if grep -q "name='$p'" <<<"$perms"; then
    echo "FAIL: forbidden permission declared: $p" >&2
    failed=1
  fi
done

# The app must declare CAMERA, or it is not doing its job.
if ! grep -q "name='android.permission.CAMERA'" <<<"$perms"; then
  echo "FAIL: CAMERA permission missing; the shield cannot work" >&2
  failed=1
fi

# Catch accidental network clients shipped by a dependency.
count_net="$(grep -c -E "name='android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)'" <<<"$perms" || true)"
if [[ "$count_net" -ne 0 ]]; then
  echo "FAIL: network permissions present ($count_net)" >&2
  failed=1
fi

if [[ "$failed" -ne 0 ]]; then
  echo >&2
  echo "Privacy guarantee violated. See app/src/main/AndroidManifest.xml tools:node=\"remove\" entries." >&2
  exit 1
fi

echo "OK: CAMERA only; no network, storage, location, microphone or bluetooth permissions."
