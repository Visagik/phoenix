#!/bin/bash
# Грубая проверка синтаксиса ВСЕГО кода (включая Android): ошибок «unresolved reference» на Android-классы будет много —
# важна строка «syntax: 0». Настоящая сборка — только Gradle/Android Studio.
H="$(cd "$(dirname "$0")" && pwd)"
L="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
W="${WORK:-/tmp/phoenix-syn}"
rm -rf "$W"; mkdir -p "$W/src" "$W/out"
cp "$H"/../../app/src/main/java/com/phoenix/app/*.kt "$W/src/"; cp "$H/Stubs.kt" "$W/src/"
CP=$(ls $L/kotlin-compiler-embeddable-2.0.21.jar $L/kotlin-stdlib-2.0.21.jar $L/kotlin-script-runtime-2.0.21.jar $L/kotlin-reflect-2.0.21.jar $L/kotlin-daemon-embeddable-2.0.21.jar $L/annotations-24.0.1.jar $L/kotlinx-coroutines-core-jvm-1.6.4.jar $L/trove4j*.jar 2>/dev/null | tr '\n' ':')
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -cp "$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar" -d "$W/out" "$W"/src/*.kt 2>&1 | grep "error:" > "$W/errors.txt"
echo "total errors: $(wc -l < "$W/errors.txt"); syntax: $(grep -ci 'syntax error\|Expecting' "$W/errors.txt")"
