# Статус требований · alpha 0.1.1

«Реализовано» ниже означает наличие настоящего кода и соответствующих локальных проверок, а не приёмку на телефоне. Production-ready статус не присвоен.

| Требование | Реализация в APK | Неподтверждённое / ограничение |
|---|---|---|
| Один VpnService / TUN | RayrenVpnService → HEV → общий Xray router; один Go runtime | Android runtime на телефоне |
| Xray | VLESS/REALITY, Trojan, SS, Hysteria2 с Salamander, VMess; typed import; сохранение certificate pin и REALITY PQV | Реальные подключения проверяются на телефоне; остальные obfs/plugin и произвольный raw config не импортируются |
| OpenFlux | Embedded library; Cups, Yandex, boards, mailru, Telemost, direct session; legacy и v1 URI | Живые providers, CAPTCHA/interactive login; stream/PHP-mode не поддержан |
| olcRTC | Runtime Telemost/Jitsi/WB Stream, экспериментальный gate | Matching server revision; UDP заблокирован, IPv6 заблокирован |
| AUTO | Scoring, история отдельно по физическим сетям, реальная HTTPS-проверка, cooldown, failover | Реальное переключение Xray→Cups→RTC и проверка отсутствия утечки пакетным захватом |
| Выбор по пингу / Smart Balance | «По пингу»: проверка до 12 кандидатов. Smart Balance из JSON: отдельный профиль, Xray leastPing + observatory до 32 узлов | Стратегия Rayren; чужие правила не импортируются. Устройства/реальное переключение требуют проверки |
| DNS | TCP/UDP 53 interception, DoH, DoT, custom UDP, direct/system, split rules, strict policy | Android Private DNS, NXDOMAIN/public resolver/IPv6 DNS на телефоне; bootstrap control plane может использовать physical DNS |
| OpenFlux DNS | targetStrategy ForceIPv4: домены разрешаются общим DNS до передачи SOCKS-адаптеру | Чужой встроенный DoH внутри приложения не распознаётся как DNS; это HTTPS-трафик |
| Routing | DOMAIN/SUFFIX/IP/CIDR/GeoIP/GeoSite/PORT/NETWORK/PROTOCOL → DIRECT/PROXY/BLOCK; упорядочивание | Геобазы не обновляются автоматически в alpha; ECH и нестандартные протоколы ограничивают sniffing |
| Russia Direct | Домены .ru / .рф; дополнительные GeoIP/GeoSite через правила | Это не полная классификация всех российских ресурсов |
| LAN | Частные IPv4 и local IPv6 ranges через device DIRECT | Multicast discovery и link-local scope не гарантируются |
| Per-app | ALL / INCLUDE / EXCLUDE, поиск, иконки, system toggle, сохранение пакетов | Проверка на OEM; Android lockdown может блокировать исключённые приложения |
| IPv6 | BLOCK / dual / prefer IPv4 / prefer IPv6, unsupported engines блокируют ::/0 | IPv6-only и NAT64/IPv6-only uplink не принимаются как завершённые |
| Kill switch | TUN удерживается, ingress останавливается при failover, direct fallback отсутствует | После смерти процесса защиту обеспечивает Android Always-on + block without VPN |
| Network awareness | Wi-Fi/mobile/Ethernet, metered, callback, смена сети, captive state | Captive portal login и сон/OEM ограничения нужно проверить; отдельного bypass portal трафика нет |
| MTU | Auto=1280, пользовательский 1280–1500 | Нет динамического PMTU измерителя |
| Подписки | HTTP/HTTPS, URI/Base64/legacy JSON (nested и simplified Xray settings)/flat versioned manifest, QR, clipboard, share, deep link; WorkManager; ETag/Last-Modified; запрос совместимого JSON | HTTP передаёт ключи без шифрования; redirects отклоняются; профиль обновляется после parse/validation, живой canary при подключении. Все 3 предоставленных endpoints дали HTTP 200 и по 9 профилей с JSON-совместимым User-Agent; прежняя выдача периодически возвращала HTTP 500 |
| Обновление | Неудачный parse/signature/HTTP не заменяет сохранённые данные; AES-GCM AtomicFile | 304, дисковые ошибки и background constraints на устройстве |
| Подпись | Ed25519 exact bytes, pinned out-of-band key, expiry/min-client/revision checks | Неподписанные старые подписки допустимы, пока ключ проверки не закреплён |
| Хранилище | Keystore AES256-GCM, свежий nonce, исключение backup/device transfer | Instrumentation round-trip/tamper тест собран, не запускался |
| Статистика | Проверенный маршрут, внешний IP при успехе отдельного запроса, latency, session bytes/rate/time | Счётчики proxy включают диагностику; daily/monthly не реализованы; страна из metadata, не IP geolocation |
| Diagnostics | Allowlist событий, redaction, export без адресов/секретов | Нет connection inspector, UI DNS/IPv6 speed/battery benchmark |
| Дистрибуция | GPLv3, notices, matching native sources, arm64 debug APK | Не Play release; release signing, OEM acceptance и публичная эксплуатация впереди |

Автозапуск реализуется системным Android Always-on VPN. Отдельный BOOT_RECEIVER и скрытый старт по загрузке не добавлены. Нет обещания сохранить существующие TCP-сессии при смене exit.
