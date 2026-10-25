# Формат подписки Rayren

Основной формат v1 — массив `profiles`, который может включать все движки. Тип определяется по URI. JSON schema находится рядом. Поля неизвестных расширений игнорируются; новая несовместимая версия `schema_version` отклоняется целиком. Ограничение: 2 МБ, глубина JSON 32, не более 500 профилей. Значение `min_client_version` сравнивается с versionCode клиента (в alpha — 1).

```json
{
  "schema_version": 1,
  "min_client_version": 1,
  "revision": 1,
  "expires_at": "2027-01-01T00:00:00Z",
  "profiles": [
    {"name": "Finland", "region": "FI", "priority": 10, "uri": "vless://..."},
    {"name": "Cups", "uri": "openflux://v1/..."},
    {"name": "Telemost", "uri": "olcrtc://..."}
  ]
}
```

Пример демонстрирует структуру, а не рабочие ключи. URL серверного endpoint может быть `/app/<id>`; существующие endpoints приложения не изменяют.

Подписанный вариант — envelope:

```json
{
  "format": "rayren-signed-v1",
  "payload": "BASE64URL_OF_EXACT_UTF8_MANIFEST_BYTES",
  "signature": "BASE64URL_ED25519_SIGNATURE_OF_THOSE_BYTES"
}
```

Публичный 32-byte ключ Ed25519 пользователь получает отдельным доверенным способом и задаёт при импорте; поле ключа не доверяется из самого envelope. Подпись относится к исходным байтам `payload`, JSON не канонизируется. После pinning отсутствие подписи, другой ключ или меньшая `revision` отклоняются. Для 304 используется предыдущая конфигурация и её trust policy. Отсутствие `expires_at` разрешено, поэтому срок доступа должен дополнительно контролировать сервер.

Для совместимости принимаются URI списки, Base64 списки, JSON массивы Xray configs и их разрешённые proxy outbounds. Их глобальные inbounds/routing/DNS/log/path/API настройки не исполняются. В manifest также понимается legacy `version:1` и `xray.profiles`; новый генератор должен использовать flat v1 schema. Самостоятельные OpenFlux URI и старый olcRTC dialect проверяются отдельными парсерами.

Обновления используют HTTPS, ETag и Last-Modified. HTTP redirects не переходятся автоматически. Профиль применяется атомарно после проверки; реальные сетевые пробы выполняются при подключении. Неуспешное обновление сохраняет предыдущие валидные данные.
