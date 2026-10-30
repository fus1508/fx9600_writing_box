#!/bin/sh
# Run from the installed directory so the recovery marker has a stable path.
set -eu
app_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$app_dir"
exec java -cp "fx9600-writing-box.jar:Symbol.RFID.API3.jar" org.fus.Main station.properties
