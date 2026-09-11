#!/usr/bin/env bash
# Runs `mvn verify` on every JDK in the CI matrix, so that a push does not discover a JDK-specific
# lint failure that a local run on one JDK could not see.
#
# The build compiles with -Werror. Lint categories change between JDK releases, so a comment or a
# construct that is silent on 21 can fail on 25 — and because a warning is a build failure, that is
# a red pull request for something that compiled fine on the machine it was written on.
#
# Arguments are passed through to Maven, so `scripts/preflight.sh -DskipTests` compiles on both
# without running the suite.
set -euo pipefail

MATRIX=(21 25)

cd "$(dirname "$0")/.."

find_jdk() {
  local version="$1"
  local override="JAVA${version}_HOME"
  if [[ -n "${!override:-}" ]]; then
    printf '%s' "${!override}"
    return 0
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    /usr/libexec/java_home -v "$version" 2>/dev/null && return 0
  fi
  for candidate in \
      "/usr/lib/jvm/temurin-${version}-jdk" \
      "/usr/lib/jvm/java-${version}-openjdk-amd64" \
      "$HOME/.sdkman/candidates/java/${version}"*; do
    if [[ -x "$candidate/bin/javac" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

# Deliberately a failure rather than a skip. A preflight that checks half the matrix gives you the
# confidence without the coverage, which is worse than not running it.
declare -a HOMES=()
for version in "${MATRIX[@]}"; do
  if ! home="$(find_jdk "$version")"; then
    cat >&2 <<MSG
preflight: no JDK $version found.

Set JAVA${version}_HOME, or install one without root:

  mkdir -p ~/Library/Java/JavaVirtualMachines
  curl -sSL 'https://api.adoptium.net/v3/binary/latest/${version}/ga/mac/aarch64/jdk/hotspot/normal/eclipse' \\
    | tar xz -C ~/Library/Java/JavaVirtualMachines

Adjust mac/aarch64 for your platform.
MSG
    exit 1
  fi
  HOMES+=("$home")
done

# Told once, up front, because the PostgreSQL suites silently skipping is the one way this script
# can pass while leaving the adapter untested.
if [[ -z "${TILL_TEST_DB_URL:-}" ]] && ! docker info >/dev/null 2>&1; then
  echo "preflight: no Docker and no TILL_TEST_DB_URL — the PostgreSQL suites will skip." >&2
  echo "           CI runs them, so a green preflight is not a green CI." >&2
  echo >&2
fi

for index in "${!MATRIX[@]}"; do
  version="${MATRIX[$index]}"
  home="${HOMES[$index]}"
  echo "=== JDK $version ($home) ==="
  JAVA_HOME="$home" mvn -B -ntp verify "$@"
  echo
done

echo "preflight: all of ${MATRIX[*]} passed"
