#!/usr/bin/env bash
# Builds Rolodex for the LightOS emulator, installs it on the Light_Phone emulator, and opens it.
# Targets the emulator by serial so a Light Phone plugged in over USB is never touched.
# --testdata also copies tool/testdata/vcf into the tool's import folder.
set -e
cd "$(dirname "$0")/../.."

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5554
TOOL=com.jmsbrain.rolodex
TOML=tool/lighttool.toml

if ! "$ADB" devices | grep -q "^$SERIAL"; then
    echo "Light_Phone emulator isn't running. Start it with tool/scripts/emulator-start.sh"
    exit 1
fi

if [ -z "$JAVA_HOME" ] && [ -d "/c/Program Files/Android/Android Studio/jbr" ]; then
    export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
fi
export ANDROID_HOME="$SDK"

# Point at the LightOS emulator for this build only; lighttool.toml always goes back to com.lightos.
BACKUP="$(mktemp)"
cp "$TOML" "$BACKUP"
trap 'cp "$BACKUP" "$TOML"; rm -f "$BACKUP"' EXIT
sed -i 's/^serverPackage = "com.lightos"$/serverPackage = "com.thelightphone.sdk.emulator"/' "$TOML"

ANDROID_SERIAL=$SERIAL ./gradlew :tool:installDebug -DlightSdk.allowAltServerPackage=true

if [ "$1" = "--testdata" ]; then
    for f in tool/testdata/vcf/*.vcf; do
        MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" shell "run-as $TOOL sh -c 'mkdir -p files/shared/import && cat > files/shared/import/$(basename "$f")'" < "$f"
    done
    echo "Test .vcf files copied to Rolodex's import folder."
fi

"$ADB" -s "$SERIAL" shell monkey -p "$TOOL" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
echo "Rolodex installed and opened on the Light_Phone emulator."
