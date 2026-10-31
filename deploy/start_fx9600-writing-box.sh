#!/bin/sh
# Foreground lifecycle candidate. Manager background/return contract requires firmware SAT.
set -eu
app_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$app_dir/fx9600-writing-box"
