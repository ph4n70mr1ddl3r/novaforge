#!/usr/bin/env bash
# The ops-script selftest (twenty-ninth pass): the deployment shell scripts had
# zero automated coverage, and the first defects this deep were found by hand —
# the backup sidecar's INVERTED base-freshness predicate (it refreshed only
# while fresh and let the physical base die silently the moment it aged past
# the cadence — the one artifact PITR cannot recover without) and launchers
# that printed "started pid N" for jars that did not exist or JVMs that died on
# boot. Each contract below is exercised against the REAL scripts with stubbed
# executables on PATH:
#
#   1. nightly-backup.sh — the base backup fires when the stamp is absent,
#      does NOT fire while the stamp is fresh, and fires again once it ages
#      past NOVAFORGE_BACKUP_BASE_DAYS (the inversion's both faces).
#   2. start-live-stack.sh / relaunch-gateway.sh — a missing jar fails the
#      preflight without starting anything; a JVM that dies on boot fails the
#      launch with the boot log's tail; a JVM that stays up exits 0.
#   3. promtail.yml — every service log the stack can produce is actually
#      shipped (the drift class: five of eleven services' logs reached Loki).
#
# Local-dev safety: the launcher legs back up and restore the live stack's
# pidfiles, and every java they start is a stub on PATH — the selftest never
# starts or kills a real JVM.
set -u
cd "$(dirname "$0")/../.."

status=0
fail() { echo "OPS-SELFTEST FAIL: $*" >&2; status=1; }
pass() { echo "ops-selftest: ok — $*"; }

sandbox="$(mktemp -d)"
backup_pid=""
trap 'rm -rf "$sandbox"; [ -n "$backup_pid" ] && kill "$backup_pid" 2>/dev/null; [ -n "$restored_gateway_pidfile" ] && mv "$sandbox/gateway.pid.bak" /tmp/novaforge/pids/gateway.pid 2>/dev/null' EXIT
stubs="$sandbox/stubs"
stubs_dying="$sandbox/stubs-dying"
mkdir -p "$stubs" "$stubs_dying"

# ---------------------------------------------------------- 1. backup leg ----
# the script is written against the sidecar's /backups root; rewrite the path
# prefix into the sandbox (the PREDICATE — the thing under test — is untouched)
sed "s|/backups|$sandbox/backups|g" deploy/compose/backup/nightly-backup.sh \
  > "$sandbox/backup.sh" || fail "cannot stage nightly-backup.sh"
chmod +x "$sandbox/backup.sh"
printf '#!/bin/sh\nexit 0\n' > "$stubs/pg_isready" && chmod +x "$stubs/pg_isready"
# create the target dir the real pg_basebackup would have populated; count calls
cat > "$stubs/pg_basebackup" <<EOF
#!/bin/sh
while [ \$# -gt 0 ]; do [ "\$1" = -D ] && { mkdir -p "\$2"; break; }; shift; done
echo x >> "$sandbox/basebackup.calls"
EOF
chmod +x "$stubs/pg_basebackup"
printf '#!/bin/sh\necho "-- dump"\n' > "$stubs/pg_dumpall" && chmod +x "$stubs/pg_dumpall"
: > "$sandbox/basebackup.calls"

NOVAFORGE_BACKUP_INTERVAL_SECONDS=1 NOVAFORGE_BACKUP_KEEP=20 NOVAFORGE_BACKUP_BASE_DAYS=7 \
  PATH="$stubs:$PATH" timeout 8 "$sandbox/backup.sh" > "$sandbox/backup.log" 2>&1 &
backup_pid=$!
sleep 3
first_count=$(wc -l < "$sandbox/basebackup.calls")
[ "$first_count" -ge 1 ] || fail "base backup never ran with a missing stamp"
[ "$first_count" -eq 1 ] || fail "base backup ran $first_count times while its stamp was FRESH (the inverted predicate re-backs-up every cycle)"
dump_count=$(ls "$sandbox/backups/dumps"/novaforge-*.sql.gz 2>/dev/null | wc -l)
[ "$dump_count" -ge 2 ] || fail "nightly dumps did not advance ($dump_count after ~3 cycles)"
# age the stamp past the cadence: the NEXT cycle must re-run the base backup
touch -d '8 days ago' "$sandbox/backups/base/BACKUP_STAMP" 2>/dev/null \
  || fail "no BACKUP_STAMP after the first cycle"
sleep 4
final_count=$(wc -l < "$sandbox/basebackup.calls")
kill "$backup_pid" 2>/dev/null; backup_pid=""
[ "$final_count" -ge 2 ] || fail "base backup did NOT refresh after its stamp aged past BASE_DAYS (the inverted predicate's silent-forever-skip face)"
[ "$final_count" -le 2 ] || fail "base backup ran $final_count times total — more than absent+stale (predicate not cadence-driven)"
[ "$status" -eq 0 ] && pass "backup predicate: fires on absent stamp, holds while fresh, re-fires when stale"

# ---------------------------------------------------------- 2. launchers -----
# the launcher's listener preflight copies the built auth-listener jar; make
# sure one exists so the launch-leg tests never trigger a real mvn build
ls deploy/keycloak/auth-listener/target/auth-listener-*.jar >/dev/null 2>&1 \
  || { echo "ops-selftest: building auth-listener jar (first run)..." \
       && ./mvnw -B -ntp -q -f deploy/keycloak/auth-listener/pom.xml package; }
ls deploy/keycloak/auth-listener/target/auth-listener-*.jar >/dev/null 2>&1 \
  || fail "auth-listener jar absent and the build did not produce one"

# a java stub that stays up (the booting-JVM stand-in) / one that dies instantly
printf '#!/bin/sh\nsleep 30\n' > "$stubs/java" && chmod +x "$stubs/java"
printf '#!/bin/sh\nexit 3\n'  > "$stubs_dying/java" && chmod +x "$stubs_dying/java"

# every jar path the launcher knows must exist (CI has a fresh checkout)
for jar in $(grep -oE 'services/[a-z-]+/(api/)?target/[a-z0-9.-]+\.jar' deploy/scripts/start-live-stack.sh | sort -u); do
  [ -f "$jar" ] || { mkdir -p "$(dirname "$jar")"; touch "$jar"; }
done

# live-stack safety: snapshot the pids dir state around the launcher legs
pids_dir=/tmp/novaforge/pids
mkdir -p "$pids_dir"
restored_gateway_pidfile=""
if [ -f "$pids_dir/gateway.pid" ]; then
  cp "$pids_dir/gateway.pid" "$sandbox/gateway.pid.bak"
  restored_gateway_pidfile=1
  rm -f "$pids_dir/gateway.pid"
fi

# 2a. missing jar → preflight fails, nothing starts
hidden="$sandbox/hidden"; mkdir -p "$hidden"
mv services/gateway/target/novaforge-gateway-0.1.0-SNAPSHOT.jar "$hidden/" 2>/dev/null
pids_before=$(ls "$pids_dir" 2>/dev/null | wc -l)
PATH="$stubs:$PATH" bash deploy/scripts/start-live-stack.sh > "$sandbox/launch-missing.log" 2>&1
rc=$?
pids_after=$(ls "$pids_dir" 2>/dev/null | wc -l)
[ "$rc" -ne 0 ] || fail "start-live-stack exited 0 with the gateway jar missing"
grep -q "PREFLIGHT FAIL" "$sandbox/launch-missing.log" \
  || fail "missing-jar run did not name the preflight failure: $(tail -2 "$sandbox/launch-missing.log")"
[ "$pids_after" -eq "$pids_before" ] || fail "preflight-failed run still launched services (partial stack)"
mv "$hidden/novaforge-gateway-0.1.0-SNAPSHOT.jar" services/gateway/target/ 2>/dev/null
[ "$status" -eq 0 ] && pass "launcher preflight: missing jar fails the run before anything boots"

# 2b. JVM dies on boot → launch fails loudly with the log tail
PATH="$stubs_dying:$PATH" bash deploy/scripts/relaunch-gateway.sh > "$sandbox/launch-dies.log" 2>&1
rc=$?
[ "$rc" -ne 0 ] || fail "relaunch-gateway exited 0 although the JVM died instantly"
grep -q "RELAUNCH FAIL" "$sandbox/launch-dies.log" \
  || fail "dying-JVM run did not report the launch failure: $(tail -2 "$sandbox/launch-dies.log")"
[ "$status" -eq 0 ] && pass "launcher honesty: a JVM that dies on boot fails the relaunch"

# 2c. JVM that stays up → success
PATH="$stubs:$PATH" bash deploy/scripts/relaunch-gateway.sh > "$sandbox/launch-ok.log" 2>&1
rc=$?
[ "$rc" -eq 0 ] || fail "relaunch-gateway exited $rc with a staying-alive JVM: $(tail -2 "$sandbox/launch-ok.log")"
stub_pid=$(cat "$pids_dir/gateway.pid" 2>/dev/null)
[ -n "$stub_pid" ] && kill "$stub_pid" 2>/dev/null
[ "$status" -eq 0 ] && pass "launcher honesty: a staying-alive JVM relaunches green"
rm -f "$pids_dir/gateway.pid"

# ------------------------------------------------- 3. promtail contract ------
# every spring.application.name in the repo must have its log file shipped
python3 - <<'PY' || status=1
import glob, re, sys

names = set()
for yml in glob.glob("services/*/src/main/resources/application.y*ml") + \
           glob.glob("services/data-runtime/*/src/main/resources/application.y*ml"):
    m = re.search(r"application:\s*\n\s*name:\s*(\S+)", open(yml).read())
    if m:
        names.add(m.group(1))

promtail = open("deploy/compose/observability/promtail/promtail.yml").read()
missing = sorted(n for n in names if f"{n}.log" not in promtail)
if missing:
    print(f"OPS-SELFTEST FAIL: services whose logs promtail never ships: {', '.join(missing)}")
    sys.exit(1)
print(f"ops-selftest: ok — promtail ships all {len(names)} service logs")
PY

if [ "$status" -eq 0 ]; then
  echo "ops-selftest: CLEAN"
fi
exit "$status"
