# Исследование transport engines

Проверено 16.10.2026: публичные GitHub API, исходники, build scripts и LICENSE. Машиночитаемые snapshots с SHA/release находятся в `research/`. Release и HEAD не обязательно одно и то же: native build фиксирует собственный набор SHA.

| Проект | Проверенная версия/HEAD | Архитектура и API | Оценка |
|---|---|---|---|
| Xray-core | release API v26.3.27; HEAD 0f3d884786f9 | Go routing/DNS/protocol core; JSON LoadJSONConfig, core.New/Start/Close | Основной router/protocol engine; версии submodule важнее строки release |
| AndroidLibXrayLite | v26.10.10; 1f3e2c5df1c2 | LGPL3 gomobile CoreController.startLoop(config,tunFd), stopLoop, QueryAllOutboundTrafficStats, MeasureOutboundDelay; callback Startup/Shutdown/OnEmitStatus | Выбран Android binding; one runtime с остальными |
| libXray | v26.10.10; 4e1f7843e6cd | MIT wrapper; android_wrapper.go содержит RegisterDialerController, RegisterListenerController, SetDNS | Подходящий вариант, но API нестабилен; текущая интеграция Veil использует AndroidLibXrayLite |
| sing-box | v1.14.3; dfb80349bf57 | TUN, DNS, router; platform interface передаёт Android fd, includes/excludes на уровне клиента | Хорошая альтернатива, не добавлять как четвёртый runtime без причины; LICENSE GPL3-or-later + ограничение на имя |
| Veil | v0.8.0; a5af92169126 | Kotlin Android, HEV→Xray→OpenFlux/olcRTC SOCKS; compile-libv2ray.sh объединяет Go modules; два VpnService для разных режимов | Использовать native assembly pattern, не переносить все Android services/UI |
| Ghostlane | v1.0.466; 6c04518f95f3 | KMP Compose, Android OlcboxVpnService, separate platform implementations; olcRTC mobile Runtime и несколько cores | Полезные lifecycle идеи, сложнее Android-only MVP; не выбран full fork |
| OpenFlux | v0.13.0; edcf77a1848a | Go registry, Session, classic fallback; mobile.StartProxy/StartSessionProxy/StopProxy/ProxyIsConnected; shared singleton | Embedded library без собственного VpnService подтверждена кодом; SOCKS5 через gVisor; handshake != HTTP |
| OpenFluxAndroid | v3.7.0; 9abef23d45a3 | Compose/KMP, CoreService держит TUN; AndroidConnectionService управляет core; excludes own UID | Использовать как reference; packet-by-packet Kotlin path не нужен Rayren |
| olcRTC (Ghostlane fork) | 7ac128ac8bc5 | mobile.New(), Runtime.SetProvider/SetRoom/SetKey/SetSocksPort/SetSocksCredentials/SetProtector/Start/WaitReady/Stop; SetUDP | Embedded SOCKS под общим TUN возможен. Telemost/Jitsi зависят от сервера и protocol revision; experimental |
| HEV | 2.19.0; 873541b81f00 | C/lwIP native TUN→SOCKS; JNI start/stop/isRunning/stats; SOCKS credentials | Выбран для пакетного пути; FD принадлежит Android service |

## Непосредственно проверенные исходники

- [Veil compile-libv2ray.sh](https://github.com/venterum/veil/blob/a5af92169126b8d9b79c674c37f2bc127b339601/compile-libv2ray.sh): одна AAR, выравнивание ELF 16 KB, набор Pion replace для совместимости transport/v4 и v5.
- [Veil CoreVpnService](https://github.com/venterum/veil/blob/a5af92169126b8d9b79c674c37f2bc127b339601/veil/app/src/main/java/com/v2ray/ang/service/CoreVpnService.kt): TUN, per-app, protect.
- [OpenFlux mobile/proxy.go](https://github.com/p1neappleXpress/OpenFlux/blob/edcf77a1848a72370cfba9f346d3b897b61e7e7d/mobile/proxy.go): SOCKS listener, credentials, StartSessionProxy, separate readiness, stop.
- [OpenFlux netbind](https://github.com/p1neappleXpress/OpenFlux/blob/edcf77a1848a72370cfba9f346d3b897b61e7e7d/netbind/netbind.go): Android reference relies on own-app exclusion. Do not assume a universal protect hook exists.
- [olcRTC mobile Runtime](https://github.com/ghostlane-project/olcrtc/blob/7ac128ac8bc59bd008f116f072c0a2b14c70395f/mobile/runtime.go): generation-based lifecycle and readiness timeouts.
- [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite/blob/1f3e2c5df1c248ed42630cee2bf747643d303ecc/libv2ray_main.go): lifecycle and config loading.

## OpenFlux embedding choices

Direct gomobile library + loopback SOCKS chosen: one APK, same lifecycle, authenticated loopback possible, existing remote exit. JNI packet API is possible but adds Kotlin copies and a second policy path. Native subprocess works only with packaged executable and explicit cleanup, costs process/RAM; not needed. IPC is for a future crash-isolated engine process, not another installed VPN app.

OpenFlux packet plane is IPv4 in inspected source. IPv6 must be rejected/blocked for this transport, not silently routed outside VPN. Session/striping is provider-internal and must not be confused with Rayren AUTO. Client singleton forbids parallel OpenFlux probes; serialize them. Some providers require CAPTCHA/token/session setup; a profile without required interaction cannot be claimed supported by a generic one-button UI.

## Base comparison and recommendation

| Criterion | Veil full fork | Ghostlane full fork | Own shell + native reuse |
|---|---|---|---|
| Time to imported VPN | Fast | Medium | Medium |
| OpenFlux | Already integrated | Requires integration | Use proven bridge |
| Xray/routing | Mature existing code | Multiple cores | Native Xray policy compiler |
| olcRTC | Integrated, server pair test needed | Most developed fork | Explicit adapter, experimental |
| UI/lifecycle debt | Many legacy managers/services | KMP/platform machinery | Small explicit state machine |
| iOS/desktop | Low reuse | Highest | Portable domain/native, UI future work |
| Security adaptation | Audit logs/storage/raw configs | Audit trust model | Strict typed boundary from start |

Recommendation: own Rayren shell and domain, reuse native mechanisms from Veil selectively. In inspected Veil, OpenFluxManager logs part of URL and continues after readiness timeout; OlcrtcManager debug includes room/client/key prefix. These policies must not be carried into Rayren. Copying a whole app and renaming it would not meet the security/lifecycle requirements.

UNRESOLVED: physical-device battery, 16 KB runtime on phone, exact deployed olcRTC protocol compatibility, real Cups availability, complete NAT64/IPv6 behavior. Need matching test profiles, two Android devices/OEMs and external packet capture; source review alone cannot resolve these.
