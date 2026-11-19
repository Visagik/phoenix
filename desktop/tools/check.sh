#!/bin/bash
# Проверка десктопной логики без Gradle и без Compose: общие файлы + файлы desktop (кроме окна) + тесты desktop компилируются kotlinc'ом
# и запускаются тем же раннером, что и tools/kt-harness. Окно (Main.kt, Ui*.kt) здесь только проверяется на синтаксис.
D="$(cd "$(dirname "$0")/.." && pwd)"
R="$D/.."
L="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
W="${WORK:-/tmp/phoenix-desktop-kt}"
rm -rf "$W"; mkdir -p "$W/src" "$W/out" "$W/data" "$W/ui"
for f in $(cat "$R/tools/kt-harness/pure.txt"); do cp "$R/app/src/main/java/com/phoenix/app/$f" "$W/src/"; done
cp "$D/src/main/kotlin/android/content/Context.kt" "$W/src/"
for f in "$D"/src/main/kotlin/com/phoenix/desktop/*.kt; do
  case "$(basename "$f")" in Main.kt|Ui*.kt) cp "$f" "$W/ui/";; *) cp "$f" "$W/src/";; esac
done
cp "$D"/src/test/kotlin/com/phoenix/desktop/*.kt "$W/src/" 2>/dev/null
cp "$R/tools/kt-harness/JunitStubs.kt" "$W/src/"
python3 - "$W" <<'PY'
import sys,re,glob,os
w=sys.argv[1]
names=[]
for f in sorted(glob.glob(w+'/src/*Test.kt')):
    src=open(f).read()
    if '@Test' in src:
        for m in re.finditer(r'^class (Desktop\w+)',src,re.M): names.append(m.group(1))
body='''import com.phoenix.desktop.*
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
''' % ', '.join(n+'()' for n in names)
open(w+'/src/Runner.kt','w').write(body)
PY
CP=$(ls $L/kotlin-compiler-embeddable-2.0.21.jar $L/kotlin-stdlib-2.0.21.jar $L/kotlin-script-runtime-2.0.21.jar $L/kotlin-reflect-2.0.21.jar $L/kotlin-daemon-embeddable-2.0.21.jar $L/annotations-24.0.1.jar $L/kotlinx-coroutines-core-jvm-1.6.4.jar $L/trove4j*.jar $L/intellij-deps-trove4j*.jar 2>/dev/null | tr '\n' ':')
RT="$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -cp "$RT" -d "$W/out" "$W"/src/*.kt 2>&1 | grep -v "^warning\|JAVA_TOOL" | head -40
# окно: только синтаксис (ошибки «unresolved reference» на Compose ожидаемы)
if ls "$W"/ui/*.kt >/dev/null 2>&1; then
  java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -cp "$RT:$W/out" -d "$W/uiout" "$W"/ui/*.kt 2>&1 | grep "error:" > "$W/ui-errors.txt"
  echo "окно: ошибок всего $(wc -l < "$W/ui-errors.txt"), синтаксических $(grep -ci 'syntax error\|Expecting' "$W/ui-errors.txt")"
fi
java -Dphoenix.dir="$W/data" -cp "$W/out:$RT" RunnerKt 2>&1 | grep -v "^PASS\|JAVA_TOOL"
