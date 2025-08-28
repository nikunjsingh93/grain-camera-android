#!/bin/bash

# Set Java 17 environment
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"

# Verify Java version
echo "Using Java version:"
java -version

# Run the build
./gradlew clean build

