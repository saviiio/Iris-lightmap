#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
check_dir=$(mktemp -d)
trap 'rm -rf "$check_dir"' EXIT
javac -d "$check_dir" common/src/main/java/net/irisshaders/iris/lighting/LightmapVolumeData.java tests/LightmapVolumeCheck.java
java -ea -cp "$check_dir" LightmapVolumeCheck
