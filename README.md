# home-android

Android-приложение системы [Home](https://github.com/ananalog/home) для настройки устройств по Bluetooth:
Wi-Fi, DHCP/статический IP, адрес сервера (или поиск сервера в сети), имя, настройки устройства,
перезагрузка, сброс настроек, откат к заводской прошивке, обновление прошивки из файла.
Проект — [`home/doc/04-android.md`](https://github.com/ananalog/home/blob/main/doc/04-android.md).

## Структура

```
external/home-protocol/   сабмодуль протокола (Kotlin-кодеки подключаются как composite build)
core/                     чистый Kotlin: DeviceClient (запросы по BLE, фрагментация, код, OTA),
                          разбор образа прошивки, IP-утилиты; тесты на эмуляторе устройства
app/                      Android: BLE (GATT), поиск устройств, поиск сервера (mDNS), экраны на Compose
```

## Сборка

Нужны JDK 21 и Android SDK (`ANDROID_HOME` или `local.properties` с `sdk.dir`).

```
git clone --recursive https://github.com/ananalog/home-android && cd home-android
./gradlew :core:test            # логика протокола — работает и без Android SDK
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

Без Android SDK модуль `app` просто не подключается (см. `settings.gradle.kts`).

Подпись релиза: переменные `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
(в CI — секреты `KEYSTORE_BASE64` и остальные).

## Как пользоваться

1. «Устройства рядом» — список устройств Home по BLE (метка «не настроено» у новых).
2. Подключение: введите 4-значный код с экрана устройства; у устройств без экрана — нажмите на них кнопку.
3. Wi-Fi → «Выбрать сеть» (сети сканирует само устройство) → пароль. Устройство перезагрузится и
   подключится; если за 2 минуты сервер недоступен — вернутся прежние сетевые настройки.
4. IP-адрес: DHCP или статический (IP, префикс, шлюз, DNS).
5. Сервер: пусто — устройство найдёт сервер само; «Найти в сети» ищет сервер по mDNS с телефона.
6. После этого устройство появится в Telegram Mini App как новое — примите его там.
