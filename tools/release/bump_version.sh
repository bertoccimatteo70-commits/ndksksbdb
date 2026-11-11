#!/usr/bin/env bash
#
# Incrementa versionCode (piu 1) e versionName (patch piu 1) in version.properties.
# Gira in CI prima della build, cosi ogni push su main produce una release con una
# versione nuova senza toccare app/build.gradle a mano.
#
#   ./tools/release/bump_version.sh          # 1.0.6 (7) -> 1.0.7 (8)
#
# Per un salto di minor o major si modifica version.properties a mano e si fa push.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FILE="$ROOT/version.properties"
if [ ! -f "$FILE" ]; then
    echo "ERRORE: manca $FILE" >&2
    exit 1
fi

CODE="$(sed -n 's/^versionCode=\([0-9]\{1,\}\)$/\1/p' "$FILE" | head -n 1)"
NAME="$(sed -n 's/^versionName=\(.*\)$/\1/p' "$FILE" | head -n 1)"
if [ -z "$CODE" ] || [ -z "$NAME" ]; then
    echo "ERRORE: $FILE deve contenere versionCode=<numero> e versionName=<X.Y.Z>" >&2
    exit 1
fi

IFS=. read -r MAJOR MINOR PATCH <<< "$NAME"
case "${MAJOR:-}${MINOR:-}${PATCH:-}" in
    '' | *[!0-9]*)
        echo "ERRORE: versionName '$NAME' non e del tipo X.Y.Z (es. 1.0.7)" >&2
        exit 1
        ;;
esac

NEW_CODE=$((CODE + 1))
NEW_NAME="$MAJOR.$MINOR.$((PATCH + 1))"

printf 'versionCode=%s\nversionName=%s\n' "$NEW_CODE" "$NEW_NAME" > "$FILE"
echo "Versione: $NAME ($CODE) -> $NEW_NAME ($NEW_CODE)"

if [ -n "${GITHUB_ENV:-}" ]; then
    {
        echo "VERSION_NAME=$NEW_NAME"
        echo "VERSION_CODE=$NEW_CODE"
    } >> "$GITHUB_ENV"
fi
