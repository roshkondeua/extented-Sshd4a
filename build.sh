#!/bin/bash

# 2026-05-15: first experiment with an automated build script, use with care

if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME" ]; then
    echo "Error: ANDROID_HOME is not set or the directory does not exist."
    echo "Please set it first (e.g., export ANDROID_HOME=\$HOME/android-sdk)"
    exit 1
fi

if [ ! -d "app" ]; then
    echo "Error: 'app' directory not found."
    echo "Please execute the build from within the project root directory."
    exit 1
fi

# Tell Gradle where the sdk is
if [ ! -f local.properties ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
fi

# For some reason I cannot make this permanent in the repository...
# make sure these are executable
chmod +x gradlew
chmod +x app/src/main/cpp/prebuild-dropbear.sh
chmod +x app/src/main/cpp/prebuild-rsyn.sh
chmod +x app/src/main/cpp/dropbear/src/ifndef_wrapper.sh

# ############################################################################
# CMake setup
# ############################################################################
# Extract the cmake version needed
export CMAKE_VER=$(awk -F'"' '/^cmakeVersion[[:space:]]*=/ {print $2}' gradle/libs.versions.toml)
if [ -z "$CMAKE_VER" ]; then
    echo "Error: Could not parse cmakeVersion from libs.versions.toml"
    exit 1
fi

# cmake must be installed manually by sdkmanager
# TODO: Gradle seems to refuse installing it if there is a global cmake command?
# Will do nothing if it's already there.
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "cmake;$CMAKE_VER"

# Add to the path
export PATH=$PATH:$ANDROID_HOME/cmake/$CMAKE_VER/bin

# ############################################################################
# Sshd4a build
# ############################################################################

# run the prebuild scripts
app/src/main/cpp/prebuild-rsyn.sh
app/src/main/cpp/prebuild-dropbear.sh

# and finally build
./gradlew assembleRelease