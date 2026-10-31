#!/bin/sh
set -eu
app_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$app_dir"
for script in run.sh fx9600-writing-box start_fx9600-writing-box.sh stop_fx9600-writing-box.sh verify-release.sh; do
    [ -x "$script" ] || { echo 'RELEASE_SCRIPT_NOT_EXECUTABLE' >&2; exit 20; }
done
sha256sum -c SHA256SUMS >/dev/null || { echo 'RELEASE_CHECKSUM_FAILED' >&2; exit 20; }
grep -qx 'journal.schema=FXJNL01' release.properties
grep -qx 'runtime.schema=FXRUNTIME02' release.properties
grep -qx 'rollback.requires=FXJNL01+FXRUNTIME02' release.properties
grep -qx 'production.write=BLOCKED' release.properties
grep -qx 'firmware.sat=BLOCKED' release.properties
