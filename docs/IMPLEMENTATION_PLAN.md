# Последовательность реализации

Архитектурное ревью 16.10.2026: GO. Каждый следующий этап сохраняет собираемый APK; результаты и ограничения записываются отдельно. Оценка объёма: production-клиент этого класса требует нескольких недель разработки и тестирования, особенно на OEM и реальных сетях. Сборка APK сама по себе не завершает все фазы.

| Phase | Scope / modules | Dependencies | Tests / acceptance | Rollback / risk |
|---|---|---|---|---|
| 1 Xray MVP | app/vpn, transport, domain/import, UI home; REALITY, encrypted profiles, foreground, DNS, connect/disconnect, stats | HEV + combined native core | compile/unit/native config; actual HTTPS over tunnel on device | Keep last APK/profile; native and Android lifecycle failures |
| 2 routing/per-app | typed policy compiler, app picker, LAN/RU/custom, IPv6 block/dual | Phase1 | rule order, CIDR validation, include-empty, device direct/proxy/IPv6 tests | Restore prior policy; policy change may recreate TUN |
| 3 DNS | tunneled DoH, custom DNS, split; verified DoT adapter | Phase2 | NXDOMAIN, bootstrap, TLS failure, no cleartext fallback | Atomic policy rollback; Android Private DNS interactions |
| 4 Smart Balance | health model, median/EWMA, score, bounded probes | Phase1 | injected clock/failure/cooldown tests; real canary | Manual profile available; measuring provider has cost |
| 5 OpenFlux | embedded upstream SOCKS, provider settings/URI, same DNS/router | Native dependency compatibility | real Cups handshake + HTTP + UDP as supported | Xray remains selectable; Session/server-version mismatch |
| 6 AUTO | serialized controller, backoff, hysteresis, held TUN | Phases4/5 | Xray OK; Xray fail→Cups; both fail→experimental RTC; no direct fallback | Manual mode; stale callbacks/race handling |
| 7 olcRTC | embedded Runtime, Telemost/Jitsi, experimental gate | Matching client/server wire revision | real bidirectional traffic and reconnect on phone | Disable experimental engine; provider reliability |
| 8 unified manifest | schema, backward import, conditional refresh, signed envelope trust model | Secret vault + parser | invalid update preserves prior, ETag/304, versions/expiry/replay | Prior encrypted config; no server endpoint modification |
| 9 hardening | QR/share/deep link, diagnostics, OEM, screen-off/network/captive tests, distribution | All prior gates | Android instrumentation, physical capture, battery/CPU/throughput, APK16KB | Prior signed release; no production claim before gate |

При невозможности runtime проверки обозначать UNVERIFIED, а не подменять успешным mock. Test doubles разрешены для unit state machine; они никогда не заменяют transport в выпускаемом APK. Build scripts и dependency lock должны позволять восстановить native library из исходников.
