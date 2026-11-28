# Выпуск Phoenix

Перед каждым выпуском — обязательный список `docs/RELEASE_CHECKLIST.md` и скрипт `bash tools/release-check.sh` (перед публикацией — с `--final`).

## Один раз: постоянный ключ подписи
1. Сверить отпечаток опубликованного APK и ключа на машине:
   ```
   apksigner verify --print-certs phoenix-3.9.64-debug.apk
   keytool -list -v -keystore ~/.android/debug.keystore -storepass android
   ```
   Если SHA-256 совпали — этот ключ и станет постоянным (обновление поверх сохранит данные).
   Если нет — один раз понадобится переустановка (данные переносятся через «Экспорт и импорт»).
2. Скопировать ключ вне репозитория и сменить пароль:
   ```
   mkdir -p ~/keys && cp ~/.android/debug.keystore ~/keys/phoenix-release.jks
   keytool -storepasswd -keystore ~/keys/phoenix-release.jks
   keytool -keypasswd -alias androiddebugkey -keystore ~/keys/phoenix-release.jks   # если откажет (PKCS12) — достаточно первой
   ```
   Копию файла — в менеджер паролей и на отдельный носитель. Потеряете ключ — обновление поверх станет невозможным.
3. Локально: файл `keystore.properties` в корне проекта (он в `.gitignore`):
   ```
   storeFile=/home/roma/keys/phoenix-release.jks
   storePassword=...
   keyAlias=androiddebugkey
   keyPassword=...
   ```
   Проверка: `./gradlew :app:assembleFullRelease` → `app/build/outputs/apk/full/release/`.
4. Для автовыпуска: секреты репозитория (Settings → Secrets → Actions):
   `PHOENIX_KEYSTORE_B64` (`base64 -w0 ~/keys/phoenix-release.jks`), `PHOENIX_STORE_PASSWORD`, `PHOENIX_KEY_PASSWORD`, `PHOENIX_KEY_ALIAS`.
5. Заполнить `tools/core.lock`: `TAG` релиза ядра в 2dust/AndroidLibXrayLite и `SHA256` (`sha256sum app/libs/libv2ray.aar`).

## Быстрый выпуск вручную (debug-подпись)
Пока `tools/core.lock` и секреты CI не заполнены, workflow `release` на теге остановится на проверках (красный ✗ — это ожидаемо, релиз он не создаст).
```
./gradlew :app:assembleFullDebug
cp app/build/outputs/apk/full/debug/app-full-debug.apk phoenix-3.9.68-debug.apk
sha256sum phoenix-3.9.68-debug.apk
git add -A && git commit -m "Phoenix 3.9.68" && git push
gh release create v3.9.68 phoenix-3.9.68-debug.apk --title "Phoenix 3.9.68" --notes-file RELEASE_NOTES_3.9.68.md
```
Подпись должна совпасть с установленной сборкой (debug-ключ этой машины), иначе обновление поверх не встанет.
**В релизе full ровно один APK.** Установленные 3.9.65–3.9.68 берут ПЕРВЫЙ APK из релиза, поэтому Lite выкладывается только отдельным предрелизом (его API как latest не отдаёт, самообновление full его не видит):
```
gh release create v<версия>-lite phoenix-lite-<версия>-debug.apk --prerelease --title "Phoenix Lite <версия>" --notes "…"
```
Имя файла Lite обязательно с «lite», имя файла full — без: по этому приложение (начиная с 3.9.68) ищет в релизе свой файл.

В описание релиза в каждом выпуске добавляйте строку `Подпись: <SHA256 сертификата>` (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256`; можно вставлять как печатает keytool, с двоеточиями): по ней приложение заранее поймёт, что поверх не встанет, и предложит копию и переустановку.
Перед выпуском сверьте отпечаток ключа машины с прошлым релизом: `apksigner verify --print-certs <apk из прошлого релиза> | grep SHA-256`.
Храните копию `~/.android/debug.keystore` вне проекта (переезд системы = новый ключ = обновление поверх не встанет).

## Как проверить самообновление
1. Собрать предыдущую версию: `./gradlew :app:assembleFullDebug -PphoenixVersionName=<прошлая> -PphoenixVersionCode=<код − 1>` и установить на телефон.
2. Выпустить новую версию (см. выше).
3. На телефоне: «Ещё → Обновления» (или дождаться уведомления) → «Скачать и установить». Проверить: контрольная сумма сошлась, установка поверх, настройки и цели на месте, версия новая.

## Каждый выпуск
1. Поднять `versionCode` и `versionName` в `app/build.gradle.kts`, написать `RELEASE_NOTES_<версия>.md`, обновить CHANGELOG.
2. `git commit`, затем `git tag v<версия> && git push && git push --tags`.
3. CI соберёт подписанный APK, проверит тег, заметки и ядро, посчитает SHA-256 и создаст GitHub Release (APK, `SHA256SUMS.txt`, `mapping-<версия>.txt`, отпечаток подписи).

## Проверка release на телефоне (сжатие кода включено)
Обновление поверх 3.9.64 с сохранением настроек; подключение; смена и «умная» смена сервера; цель с вариантом А; движок hev вкл./выкл.;
перепроверка и замер скорости; ночной сбор по будильнику и после перезагрузки; плитка, виджет, кнопки уведомления;
диагностика и сохранение журнала; самообновление на следующую версию.
