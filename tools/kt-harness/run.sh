#!/bin/bash
# Проверка чистой логики без Android: компилирует файлы из pure.txt и тесты из tests.txt и запускает их своим раннером.
# Нужен JDK и каталог Gradle с kotlin-compiler-embeddable (по умолчанию /opt/gradle-8.14.3/lib; можно задать GRADLE_LIB).
# Запуск из любого места:  bash tools/kt-harness/run.sh
H="$(cd "$(dirname "$0")" && pwd)"
P="$H/../../app/src"
L="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
W="${WORK:-/tmp/phoenix-kt}"
rm -rf "$W"; mkdir -p "$W/src" "$W/out" "$W/data" "$W/dump"
for f in $(cat "$H/pure.txt"); do cp "$P/main/java/com/phoenix/app/$f" "$W/src/"; done
for f in $(cat "$H/tests.txt"); do cp "$P/test/java/com/phoenix/app/$f" "$W/src/"; done
cp "$H/Stubs.kt" "$H/JunitStubs.kt" "$W/src/"
python3 - "$H" "$W" <<'PY'
import sys
h,w=sys.argv[1],sys.argv[2]
tests=[l.strip()[:-3] for l in open(h+'/tests.txt') if l.strip()]+['XudpKeyTest','BypassSitesTest','SiblingCheckTest','Ipv6ConfigTest','SlowMonitorTest']
body='''import com.phoenix.app.*
fun main() {
    var failed = 0; var total = 0
    for (obj in listOf<Any>(%s)) {
        for (m in obj.javaClass.declaredMethods.sortedBy { it.name }) {
            if (m.parameterCount != 0 || m.annotations.none { it.annotationClass.simpleName == "Test" }) continue
            total++
            try { m.invoke(obj); println("PASS ${obj.javaClass.simpleName}.${m.name}") }
            catch (e: java.lang.reflect.InvocationTargetException) { failed++; println("FAIL ${obj.javaClass.simpleName}.${m.name}: ${e.targetException}") }
        }
    }
    println(if (failed == 0) "ALL OK ($total)" else "$failed FAILED of $total")
}
''' % ', '.join(t+'()' for t in tests)
open(w+'/src/Runner.kt','w').write(body)
PY
CP=$(ls $L/kotlin-compiler-embeddable-2.0.21.jar $L/kotlin-stdlib-2.0.21.jar $L/kotlin-script-runtime-2.0.21.jar $L/kotlin-reflect-2.0.21.jar $L/kotlin-daemon-embeddable-2.0.21.jar $L/annotations-24.0.1.jar $L/kotlinx-coroutines-core-jvm-1.6.4.jar $L/trove4j*.jar $L/intellij-deps-trove4j*.jar 2>/dev/null | tr '\n' ':')
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -cp "$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar" -d "$W/out" "$W"/src/*.kt 2>&1 | grep -v "^warning" | head -40
java -Dphoenix.dir="$W/data" -Dphoenix.dump="$W/dump" -cp "$W/out:$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar" RunnerKt 2>&1 | grep -v "^PASS\|JAVA_TOOL"
