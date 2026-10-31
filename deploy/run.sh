#!/bin/sh
# Compatibility launcher; persistent state is configured outside this release.
set -eu
app_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$app_dir/start_fx9600-writing-box.sh"
