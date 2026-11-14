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

## Каждый выпуск
1. Поднять `versionCode` и `versionName` в `app/build.gradle.kts`, написать `RELEASE_NOTES_<версия>.md`, обновить CHANGELOG.
2. `git commit`, затем `git tag v<версия> && git push && git push --tags`.
3. CI соберёт подписанный APK, проверит тег, заметки и ядро, посчитает SHA-256 и создаст GitHub Release (APK, `SHA256SUMS.txt`, `mapping-<версия>.txt`, отпечаток подписи).

## Проверка release на телефоне (сжатие кода включено)
Обновление поверх 3.9.64 с сохранением настроек; подключение; смена и «умная» смена сервера; цель с вариантом А; движок hev вкл./выкл.;
перепроверка и замер скорости; ночной сбор по будильнику и после перезагрузки; плитка, виджет, кнопки уведомления;
диагностика и сохранение журнала; самообновление на следующую версию.
