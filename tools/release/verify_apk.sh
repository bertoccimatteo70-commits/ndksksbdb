#!/usr/bin/env bash
#
# Blocca il rilascio se l'APK non e firmato con la chiave di distribuzione PTA.
#
#   ./tools/release/verify_apk.sh app/build/outputs/apk/release/app-release.apk
#   ./tools/release/verify_apk.sh PTA-1.0.5.apk --key-only
#
# Controlla, nell'ordine: certificato SHA-256 contro keystore/signing-fingerprint.txt,
# firma APK v2, ABI richieste, e (senza --key-only) versionCode/versionName uguali a
# app/build.gradle. Esce con 1 al primo controllo fallito, cosi la pipeline si ferma
# prima di pubblicare.
#
# Nota: nessun comando passa da `grep -q` in pipe con `set -o pipefail`, perche grep
# chiude la pipe in anticipo e il produttore muore con SIGPIPE (falso negativo).
set -euo pipefail

APK=""
KEY_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --key-only) KEY_ONLY=1 ;;
        -*) echo "Opzione sconosciuta: $arg" >&2; exit 2 ;;
        *) APK="$arg" ;;
    esac
done
if [ -z "$APK" ]; then
    echo "Uso: $0 <apk> [--key-only]" >&2
    exit 2
fi
if [ ! -f "$APK" ]; then
    echo "ERRORE: APK non trovato: $APK" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FINGERPRINT_FILE="$ROOT/keystore/signing-fingerprint.txt"
if [ ! -f "$FINGERPRINT_FILE" ]; then
    echo "ERRORE: manca l'impronta attesa in $FINGERPRINT_FILE" >&2
    exit 1
fi
EXPECTED="$(tr -d '[:space:]:-' < "$FINGERPRINT_FILE" | tr '[:upper:]' '[:lower:]')"
if [ -z "$EXPECTED" ]; then
    echo "ERRORE: $FINGERPRINT_FILE e vuoto" >&2
    exit 1
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
    BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -n 1)"
elif command -v apksigner > /dev/null 2>&1; then
    BUILD_TOOLS="$(dirname "$(command -v apksigner)")"
else
    echo "ERRORE: apksigner non trovato (imposta ANDROID_HOME o ANDROID_SDK_ROOT)" >&2
    exit 1
fi

CERTS="$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" 2> /dev/null \
    | tr '[:upper:]' '[:lower:]')"
ACTUAL="$(printf '%s\n' "$CERTS" | sed -n 's/.*certificate sha-256 digest: //p' | tr -d '[:space:]')"
ACTUAL="${ACTUAL%%$'\n'*}"
if [ -z "$ACTUAL" ]; then
    echo "ERRORE: nessun firmatario trovato in $APK (APK non firmato?)" >&2
    exit 1
fi

if [ "$ACTUAL" != "$EXPECTED" ]; then
    cat >&2 <<EOF
ERRORE: l'APK e firmato con una chiave diversa da quella di distribuzione.
  atteso: $EXPECTED
  trovato: $ACTUAL
Un APK con questa firma non puo aggiornare PTA sul telefono: Android risponde
INSTALL_FAILED_UPDATE_INCOMPATIBLE. Rilascia la build con
keystore/pta-release.jks (release) e non con la chiave debug.
EOF
    exit 1
fi

VERIFY_OUTPUT="$("$BUILD_TOOLS/apksigner" verify --verbose "$APK" 2> /dev/null)"
case "$VERIFY_OUTPUT" in
    *"Verified using v2 scheme (APK Signature Scheme v2): true"*) ;;
    *)
        echo "ERRORE: firma APK v2 assente; Android 7+ non installa l'APK." >&2
        exit 1
        ;;
esac

BADGING="$("$BUILD_TOOLS/aapt" dump badging "$APK" 2> /dev/null)"
VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([0-9]\{1,\}\)'.*/\1/p" | head -n 1)"
VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
NATIVE_CODE="$(printf '%s\n' "$BADGING" | sed -n 's/^native-code: //p' | head -n 1)"
APK_ENTRIES="$(unzip -l "$APK")"

for abi in arm64-v8a armeabi-v7a; do
    case "$NATIVE_CODE" in
        *"$abi"*) ;;
        *)
            echo "ERRORE: manca l'ABI $abi nell'APK ($NATIVE_CODE)." >&2
            exit 1
            ;;
    esac
    case "$APK_ENTRIES" in
        *"lib/$abi/libgdx.so"*) ;;
        *)
            echo "ERRORE: manca lib/$abi/libgdx.so nell'APK." >&2
            exit 1
            ;;
    esac
done

echo "Firma:       OK ($ACTUAL)"
echo "Schema v2:   OK"
echo "ABI:         $NATIVE_CODE"

if [ "$KEY_ONLY" -eq 1 ]; then
    echo "Versione:    $VERSION_NAME ($VERSION_CODE), non confrontata con il sorgente"
    exit 0
fi

GRADLE_VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]\{1,\}\([0-9]\{1,\}\).*/\1/p' \
    "$ROOT/app/build.gradle" | head -n 1)"
GRADLE_VERSION_NAME="$(sed -n "s/^[[:space:]]*versionName[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" \
    "$ROOT/app/build.gradle" | head -n 1)"
if [ "$VERSION_CODE" != "$GRADLE_VERSION_CODE" ] || [ "$VERSION_NAME" != "$GRADLE_VERSION_NAME" ]; then
    echo "ERRORE: l'APK e $VERSION_NAME ($VERSION_CODE) ma app/build.gradle dice $GRADLE_VERSION_NAME ($GRADLE_VERSION_CODE)." >&2
    exit 1
fi
echo "Versione:    $VERSION_NAME ($VERSION_CODE), allineata a app/build.gradle"

if [ -n "${GITHUB_ENV:-}" ]; then
    {
        echo "VERSION_NAME=$VERSION_NAME"
        echo "VERSION_CODE=$VERSION_CODE"
    } >> "$GITHUB_ENV"
fi
