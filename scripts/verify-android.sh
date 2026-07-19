#!/bin/sh

set -eu

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_directory/.." && pwd)
android_root="$repository_root/apps/android"

status=0
"$android_root/gradlew" \
  -p "$android_root" \
  testDebugUnitTest \
  lintDebug \
  assembleDebug \
  assembleRelease || status=$?

if [ "$status" -ne 0 ]; then
  find "$android_root" \
    -type f \
    -name "lint-results-*.txt" \
    -print \
    -exec sed -n '1,240p' {} \;
fi

exit "$status"
