#!/bin/sh
# Starts the map server proxy. Usage: tools/map-server-proxy/proxy.sh [port] [target]
set -e
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$java" "$(dirname "$0")/MapServerProxy.java" "${1:-8099}" "${2:-https://osrsmapreforged.online}"
