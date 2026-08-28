#!/usr/bin/env bash
# Relaunch the gateway JVM against the compose infra (the start-live-stack.sh
# pattern — plain nohup + pidfile; used when only the gateway needs a bounce,
# e.g. after rebuilding the jar with fresh SPA bundles).
set -u
cd "$(dirname "$0")/../.."
export NOVAFORGE_POSTGRES_PORT=5434
mkdir -p /tmp/novaforge/logs /tmp/novaforge/pids

pidfile="/tmp/novaforge/pids/gateway.pid"
if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
  echo "gateway already running (pid $(cat "$pidfile"))"
  exit 0
fi
nohup java -Xms32m -Xmx384m -jar services/gateway/target/novaforge-gateway-0.1.0-SNAPSHOT.jar \
  > /tmp/novaforge/logs/gateway.out 2>&1 &
echo $! > "$pidfile"
echo "gateway started pid $(cat "$pidfile")"
