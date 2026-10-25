# Сборка для проверки · 17.10.2026

## Результат

`Rayren-0.1.0-alpha-arm64-v8a.apk`, applicationId `com.rayren.android`, versionCode 1. Android 8.0+ (minSdk 26), ARM64. compileSdk/targetSdk 36. Debug-подпись; APK Signature Scheme v2. Контрольные суммы окончательных файлов находятся рядом в `artifacts/SHA256SUMS.txt`.

Основная сборка прошла `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug`, `:domain:test`. Lint не содержит ошибок; предупреждения относятся к доступным более новым библиотекам, ориентации QR-экрана и отсутствующему x86/ChromeOS варианту. Версии не менялись на непроверенные во время финальной сборки.

## Выполненная проверка

- 48 JVM-тестов: 21 parser/validation/redaction, 13 routing/DNS, 9 selection/failover, 5 Ed25519.
- 13 конфигураций, созданных Kotlin policy compiler, загружены настоящим Xray 26.10.10 и созданы как core instances: VLESS/REALITY, VLESS TCP, Trojan, SS, Hysteria2, DNS/IPv6/rules, OpenFlux/olcRTC adapters.
- Локальная интеграция: HTTPS с проверкой сертификата → authenticated SOCKS → настоящий VLESS client → настоящий VLESS server → контролируемый HTTPS endpoint. Получен ожидаемый HTTP 204.
- В той же интеграции проверены отказ на неправильный SOCKS-пароль, перехват TCP DNS к произвольному адресу с ответом контролируемого DNS и невозможность нового соединения после остановки клиентского ядра.
- 4 native DoT-теста: DNS по TLS через authenticated SOCKS; отказ на недоверенный сертификат; отказ на неправильный пароль; валидация адреса и закрытие listener при остановке.
- Native AAR и HEV SO собраны из исходников для arm64-v8a; отсутствуют запускаемые внешние VPN-приложения и mock transport в APK.
- Базы GeoIP/GeoSite закреплены на release `202610152206`; SHA256 сверены с опубликованными checksum-файлами. Точные значения: `research/geo-assets.json`.
- Финальный APK проверяется `apksigner verify`, `zipalign -c -P 16 4` и чтением ELF PT_LOAD alignment; результат упаковки сохраняется в `artifacts/verification.txt`.

## Границы результата

Пользователь попросил APK для самостоятельной проверки. Установка/запуск Android UI, выдача VPN permission, настоящий TUN, работа Keystore на OEM, реальные Cups/Telemost/Jitsi, Wi-Fi↔LTE, экран выключен, captive portal, Always-on/lockdown, DNS/IPv6 packet capture, расход батареи и пропускная способность **не проверены на телефоне**. Instrumentation APK собран; его тесты не запускались.

Локальный native тест проверяет routing/protocol path, но не Android TUN и не внешние production-серверы. DoT проверен на контролируемом TLS-резолвере; сертификаты и доступность выбранного пользователем публичного резолвера требуют проверки в конкретной сети. Все providers без тестового сервера остаются неподтверждёнными для эксплуатации; olcRTC дополнительно закрыт переключателем experimental.

APK не доказывает выполнение всех фаз исходного ТЗ. Дальнейшая приёмка перечислена в `REQUIREMENTS_STATUS.md` и `DEVICE_TEST_CHECKLIST.md`.

## Воспроизводимость

AGP 8.10.1, Kotlin 2.1.21, JDK 21, Gradle 8.14.3, Android SDK 36, NDK 28.2.13676358. Android SDK 37 не предоставлялся доступным локальным sdkmanager, поэтому сборка честно таргетирует API 36. Native locks и исходники приложены. Публикация, изменение production-конфигурации и генерация release key не выполнялись.
