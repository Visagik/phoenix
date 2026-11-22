# Выпуск Phoenix

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
   Проверка: `./gradlew assembleRelease` → `app/build/outputs/apk/release/`.
4. Для автовыпуска: секреты репозитория (Settings → Secrets → Actions):
   `PHOENIX_KEYSTORE_B64` (`base64 -w0 ~/keys/phoenix-release.jks`), `PHOENIX_STORE_PASSWORD`, `PHOENIX_KEY_PASSWORD`, `PHOENIX_KEY_ALIAS`.
5. Заполнить `tools/core.lock`: `TAG` релиза ядра в 2dust/AndroidLibXrayLite и `SHA256` (`sha256sum app/libs/libv2ray.aar`).

## Быстрый выпуск вручную (debug-подпись, как 3.9.64)
Пока `tools/core.lock` и секреты CI не заполнены, workflow `release` на теге остановится на проверках (красный ✗ — это ожидаемо, релиз он не создаст).
```
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk phoenix-3.9.67-debug.apk
sha256sum phoenix-3.9.67-debug.apk
git add -A && git commit -m "Phoenix 3.9.67" && git push
gh release create v3.9.67 phoenix-3.9.67-debug.apk --title "Phoenix 3.9.67" --notes-file RELEASE_NOTES_3.9.67.md
```
Подпись должна совпасть с установленной сборкой (debug-ключ этой машины), иначе обновление поверх не встанет.
В описание релиза добавьте строку `Подпись: <SHA256 сертификата>` (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256`): по ней приложение заранее поймёт, что поверх не встанет, и предложит копию и переустановку.

## Как проверить самообновление
1. Собрать «старую» версию: `./gradlew assembleDebug -PphoenixVersionName=3.9.65 -PphoenixVersionCode=79` и установить на телефон.
2. Выпустить 3.9.66 (код 80, см. выше).
3. На телефоне: «Ещё → Обновления» (или дождаться уведомления) → «Скачать и установить». Проверить: контрольная сумма сошлась, установка поверх, настройки и цели на месте, версия 3.9.66.

## Каждый выпуск
1. Поднять `versionCode` и `versionName` в `app/build.gradle.kts`, написать `RELEASE_NOTES_<версия>.md`, обновить CHANGELOG.
2. `git commit`, затем `git tag v<версия> && git push && git push --tags`.
3. CI соберёт подписанный APK, проверит тег, заметки и ядро, посчитает SHA-256 и создаст GitHub Release (APK, `SHA256SUMS.txt`, `mapping-<версия>.txt`, отпечаток подписи).

## Проверка release на телефоне (сжатие кода включено)
Обновление поверх 3.9.64 с сохранением настроек; подключение; смена и «умная» смена сервера; цель с вариантом А; движок hev вкл./выкл.;
перепроверка и замер скорости; ночной сбор по будильнику и после перезагрузки; плитка, виджет, кнопки уведомления;
диагностика и сохранение журнала; самообновление на следующую версию.
