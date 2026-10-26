#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE="$ROOT/m0-spikes/classloader"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/host-classes" "$WORK/plugin-v1" "$WORK/plugin-v2" \
    "$WORK/plugin-v1-classes" "$WORK/plugin-v2-classes" \
    "$WORK/helper-v1-classes" "$WORK/helper-v2-classes"

javac --release 21 -d "$WORK/host-classes" \
    "$SOURCE/src/spike/api/Provider.java" \
    "$SOURCE/src/spike/host/ChildFirstPluginClassLoader.java" \
    "$SOURCE/src/spike/host/SpikeHost.java"

for VERSION in v1 v2; do
    if [[ "$VERSION" == "v1" ]]; then
        PLUGIN="$WORK/plugin-v1"
        PLUGIN_CLASSES="$WORK/plugin-v1-classes"
        HELPER_CLASSES="$WORK/helper-v1-classes"
    else
        PLUGIN="$WORK/plugin-v2"
        PLUGIN_CLASSES="$WORK/plugin-v2-classes"
        HELPER_CLASSES="$WORK/helper-v2-classes"
    fi

    mkdir -p "$PLUGIN/META-INF/services" "$PLUGIN_CLASSES"
    javac --release 21 -d "$HELPER_CLASSES" \
        "$SOURCE/helper-$VERSION/spike/privateapi/Helper.java"
    jar --create --file "$WORK/helper-$VERSION.jar" -C "$HELPER_CLASSES" .
    javac --release 21 -cp "$WORK/host-classes:$WORK/helper-$VERSION.jar" \
        -d "$PLUGIN_CLASSES" "$SOURCE/plugin-src/spike/impl/SampleProvider.java"
    printf '%s\n' 'spike.impl.SampleProvider' > "$PLUGIN/META-INF/services/spike.api.Provider"
    mkdir -p "$PLUGIN/spike/impl"
    cp "$PLUGIN_CLASSES/spike/impl/SampleProvider.class" "$PLUGIN/spike/impl/"
    (cd "$PLUGIN" && jar --extract --file "$WORK/helper-$VERSION.jar" spike/privateapi/Helper.class)
    (cd "$PLUGIN" && jar --create --file "$WORK/$VERSION.jar" .)
done

java -cp "$WORK/host-classes" spike.host.SpikeHost "$WORK/v1.jar" "$WORK/v2.jar"
