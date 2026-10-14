#!/usr/bin/env bash
set -euo pipefail
export PATH="/usr/bin:/bin:$PATH"
script_root=$(cd -- "$(dirname -- "$0")" && pwd)
source_root=$(cd -- "$script_root/.." && pwd)
build_root="$source_root/build/static-toolchain"
export TEMP="$build_root/tmp"
export TMP="$TEMP"
mkdir -p "$TEMP"
cd "$build_root/sources/labs-openjdk"
bash configure \
  --with-conf-name=enterpriseai-static \
  --with-boot-jdk="$source_root/build/tools/graalvm-community-openjdk-25.0.2+10.1" \
  --with-toolchain-version=2026 \
  --with-native-debug-symbols=none \
  --disable-warnings-as-errors \
  --with-jvm-variants=server \
  --with-jobs=4
