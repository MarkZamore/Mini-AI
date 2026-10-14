#!/usr/bin/env bash
set -euo pipefail
export PATH="/usr/bin:/bin:$PATH"
export VSLANG=1033
script_root=$(cd -- "$(dirname -- "$0")" && pwd)
source_root=$(cd -- "$script_root/.." && pwd)
cd "$source_root/build/static-toolchain/sources/labs-openjdk"
make CONF=enterpriseai-static java.base-static-libs jdk.net-static-libs jdk.crypto.mscapi-static-libs JOBS=4 >build/enterpriseai-static/enterpriseai-static-archives.log 2>&1
echo "Static JDK archive build completed. Detailed log: build/enterpriseai-static/enterpriseai-static-archives.log"
