#!/usr/bin/env bash
set -e
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec java -Xmx1536m -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
