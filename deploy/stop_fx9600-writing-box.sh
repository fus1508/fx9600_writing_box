#!/bin/sh
set -eu
umask 077
app_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
config=${FX9600_CONFIG:-/apps/writing-box-state/station.properties}
if [ -n "${JAVA_HOME:-}" ]; then java="$JAVA_HOME/bin/java"; else java=java; fi
if [ -n "${FX9600_STATE_DIR:-}" ]; then
    exec "$java" -cp "$app_dir/fx9600-writing-box.jar:$app_dir/Symbol.RFID.API3.jar" org.fus.Main --stop-state "$FX9600_STATE_DIR"
fi
case "$config" in /*) ;; *) echo 'CONFIG_PATH_MUST_BE_ABSOLUTE' >&2; exit 20 ;; esac
exec "$java" -cp "$app_dir/fx9600-writing-box.jar:$app_dir/Symbol.RFID.API3.jar" org.fus.Main --stop "$config"
