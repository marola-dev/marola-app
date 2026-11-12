#!/usr/bin/env bash
# sbt-quote — print each argument as one double-quoted sbt token, so `sbt "cli/run $(...)"` keeps
# an argument holding spaces or quotes whole (sbt re-splits an input task's string on spaces).
#
#   scripts/sbt-quote.sh a "b c"     # prints: "a" "b c"
#   scripts/sbt-quote.sh --self-test
set -euo pipefail

quote() {
  local a out=()
  for a in "$@"; do
    a="${a//\\/\\\\}"
    out+=("\"${a//\"/\\\"}\"")
  done
  printf '%s' "${out[*]}"
}

self_test() {
  local got want
  got="$(quote a "b c" 'd "e"' 'f\g')"
  want='"a" "b c" "d \"e\"" "f\\g"'
  [ "$got" = "$want" ] || { echo "sbt-quote self-test: got [$got], want [$want]" >&2; return 1; }
  [ -z "$(quote)" ] || { echo "sbt-quote self-test: no arguments must print nothing" >&2; return 1; }
  echo "sbt-quote self-test: ok"
}

if [ "${1:-}" = --self-test ]; then self_test; else quote "$@"; fi
