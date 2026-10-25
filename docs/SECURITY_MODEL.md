# Модель безопасности

## Персональный автоимпорт 0.1.7

Доверенная личная команда существующего Telegram-бота создаёт случайную capability-ссылку на 90 дней. На сервере хранятся SHA-256 секрета, Telegram ID и случайный стабильный account_id; клиентские базы открываются read-only. Основная подписка и БС выдаются только после проверки актуальной привязки, включения, тарифа/срока и персонального назначения комнаты. Ошибка чтения или основной выдачи даёт 503, не новый доступ и не пустое состояние. Успешное снятие прав отражается пустым/сокращённым ответом.

Ссылка открытия использует fragment, удаляемый скриптом страницы, и no-referrer; сам API работает по HTTPS без кэша, с исключением персонального пути из access-логов и rate limit. APK принимает account_id только от точного собственного HTTPS-маршрута, сохраняет ссылку в существующий Keystore vault и требует подтверждения импорта. Это не доказательство владения устройством: пересланная capability-ссылка даёт доступ, и интерфейс прямо предупреждает не делиться ею. Мгновенный отзыв уже выданных сетевых ключей относится к действующей серверной системе, а не к обновлению локального списка.

Пилот не включает ранее подготовленную PKCE-подобную одноразовую привязку, список устройств или бесшумный вход в Telegram. Массовые сообщения клиентам не отправляются.

## Уточнения 0.1.5

«Сайты из БС напрямую» — явное исключение из туннельного DNS: только домены включённого каталога разрешаются через DNS физической сети и направляются DIRECT. Оператор видит эти запросы и прямые соединения. Это отдельная настройка по умолчанию выключена; строгий туннельный DNS для остальных доменов сохраняется. Пользовательские правила имеют свой приоритет. Каталог не гарантирует доступность у всех операторов.

Отмена подключения прерывает нативные HTTP-проверки, освобождает TUN при явном отключении и очищает поздний результат старта провайдера. При обычном отказе маршрута TUN остаётся для удержания трафика. Физическая проверка утечек при отмене/переключении ещё нужна.

Локальный модуль integration/telegram хранит только хеши кодов и токенов; подтверждение Telegram ID допустимо исключительно из доверенного обработчика бота, выдача требует дополнительного verifier устройства. Модуль не опубликован и не подключён к APK. HTTP-ограничения, обработчик подтверждения и клиентский экран остаются обязательными до запуска.

Protected assets: subscription capability URL, UUID/passwords, OpenFlux secret, olcRTC key, traffic, routing intent. Trusted boundary: installed APK and Android OS/Keystore; remote subscription data and all deep links are untrusted inputs. Compromised/rooted OS is out of scope for software-only secret protection.

| Threat | Control | Remaining boundary |
|---|---|---|
| Stolen subscription URL | AES-GCM vault, no backup, no log/clipboard auto-copy | Server must revoke/rotate leaked capability |
| MITM | Prefer HTTPS; certificate verification; no redirects or silent HTTPS-to-HTTP fallback. Legacy HTTP is accepted only when explicitly supplied and labelled in the import UI | HTTP subscription URLs and keys are unencrypted in transit; CA/server compromise requires signing trust |
| Malicious JSON/core config | Typed allowlist, bounded payload/profiles/rules, generated native config | Untrusted raw config never passed to core |
| Deep-link injection | Exact rayren import action, URL validation, user-visible import result | Link must never auto-connect or change VPN policy silently |
| Path/process injection | No remote executable, shell, path, native asset or plugin fields | Packaged native code only |
| DNS poisoning | Tunneled authenticated DoH/DoT, no silent cleartext fallback | Endpoint bootstrap has separate declared route |
| Native log leaks | Disable verbose native logs; diagnostic allowlist and redaction | Native errors not displayed verbatim |
| Loopback proxy abuse | Loopback bind, random per-session credentials where supported | Device apps can reach unauthenticated localhost listeners; never claim isolation by bind address alone |
| Downgrade/replay | Schema/min-client/expiry/revision validation, pinned signing key once enrolled | Unsigned legacy subscriptions explicitly lower assurance |
| Failed refresh | Parse+verify before encrypted atomic replacement | Last known good remains available |
| Transport failure | Hold TUN, no direct default, serialized failover | Process death needs Android lockdown |
| Compromised exit | End-to-end application TLS remains necessary | VPN exit can see plaintext application traffic |

Compatibility update 0.1.1 enables Android cleartext networking for existing HTTP subscription endpoints. Subscription validation still rejects user-info, fragments, invalid ports and non-HTTP(S) URLs. A failed HTTPS request is never retried over HTTP; pinned manifest signatures remain required on both schemes. Diagnostic import failures contain only a stage and an allowlisted error category/status, never exception text, request URLs or response bodies. Simplified Xray settings are reconstructed through the same typed parser as nested settings; remote routing/inbounds/logging fields are not executed.

## Signed manifest proposal

Envelope `{format:"rayren-signed-v1", key_id, payload:<base64url exact UTF-8 bytes>, signature:<base64url Ed25519>}`. Verify exact payload bytes before parsing, avoiding ambiguous JSON canonicalisation. Key must be provisioned/pinned independently; a key included in the same untrusted response does not establish authenticity. Require monotonic revision per subscription and expiry; once signing is required, reject unsigned rollback. Initial legacy import can remain unsigned and be labelled as such. Never invent a trusted server key.

## Vault

AndroidKeyStore AES256/GCM key; fresh random IV each write, authenticated version header, temporary ciphertext followed by atomic replace. Keystore loss results in a clear import-again error, not silent erasure. No plaintext fallback. Android backup/data extraction disabled for vault. Temporary logs/configs do not contain credentials on shared storage; native config supplied in memory when API permits.

## Native supply chain

Pin source SHAs and module versions; retain LICENSE/NOTICE and hashes of AAR/SO/APK. One combined Go runtime prevents duplicate gomobile registration/runtime collisions. Build with 16 KB ELF alignment. Audit Pion/gVisor pins during upgrades, validate actual device load. Native crash isolation is a future hardening step, not another VpnService.

UNRESOLVED until end-to-end tests: system lockdown interactions with own UID exclusion, native stdout behavior on every error path, actual phone leak capture, independently provisioned Ed25519 key for a future server endpoint. These block claims of production security, not local implementation.
