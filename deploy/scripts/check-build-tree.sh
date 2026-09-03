#!/usr/bin/env bash
# The build-tree gate (2026-09-03 review): two structural checks the compiler
# and Flyway only enforce too late — or never:
#
#   1. Stale compiled resources: every non-class file under target/classes (and
#      target/test-classes) must still exist under src/main/resources
#      (src/test/resources) at the SAME relative path. A renamed or deleted
#      resource leaves its old compiled copy behind on incremental builds;
#      when the resource is a Flyway migration, the duplicate version aborts
#      every later test run ("Found more than one migration with version N") —
#      exactly the defect this gate was born from: an uncommitted V5 draft sat
#      in target/classes beside the committed V5 and 41 workflow tests errored
#      on a tree whose sources were clean.
#
#   2. Migration version uniqueness: within every db/migration directory, the
#      V<n> prefix must be unique. Flyway rejects duplicates at boot; this
#      fails them at the gate, with every offending file named.
#
# Bite-proven both ways (the twenty-fifth pass's rule: a gate check without a
# bite-proof is a print statement): a stale resource planted in target/classes
# and a duplicate-version migration each fail this gate.
set -uo pipefail
cd "$(dirname "$0")/../.."   # repo root

status=0
fail() { echo "BUILD-TREE FAIL: $*" >&2; status=1; }

# --- 1) stale compiled resources (path subset check) ---
for target_dir in $(find services platform deploy -type d -name target \
                      -not -path "*/node_modules/*" 2>/dev/null | sort -u); do
  module_dir="${target_dir%/target}"
  for kind in classes test-classes; do
    compiled="$target_dir/$kind"
    [ -d "$compiled" ] || continue
    case "$kind" in
      classes)      src="$module_dir/src/main/resources" ;;
      test-classes) src="$module_dir/src/test/resources" ;;
    esac
    [ -d "$src" ] || continue
    # shellcheck disable=SC2164
    while IFS= read -r rel; do
      [ -n "$rel" ] || continue
      if [ ! -e "$src/$rel" ]; then
        fail "stale compiled resource: $compiled/$rel has no $src counterpart" \
             "(renamed or deleted in src? run a clean build)"
      fi
    done < <( cd "$compiled" && find . -type f ! -name "*.class" ! -path "./META-INF/*" )
  done
done

# --- 2) migration version uniqueness ---
for mdir in $(find services platform deploy -type d -path "*db/migration" \
                -not -path "*/node_modules/*" 2>/dev/null | sort -u); do
  dupes=$(ls "$mdir" 2>/dev/null | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort | uniq -d)
  for v in $dupes; do
    fail "duplicate migration version V$v in $mdir:" \
         "$(ls "$mdir" | grep "^V${v}__" | tr '\n' ' ')"
  done
done

if [ "$status" -eq 0 ]; then
  echo "build-tree gate CLEAN (no stale compiled resources, no duplicate migration versions)"
else
  echo "build-tree gate FAILED — see BUILD-TREE FAIL lines above" >&2
fi
exit "$status"
