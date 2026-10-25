# Third-party notices

Rayren is distributed under GPL-3.0. Copyright 2026 Rayren contributors. See LICENSE. Native assembly was adapted from the approach in [Veil](https://github.com/venterum/veil/tree/a5af92169126b8d9b79c674c37f2bc127b339601); the Android UI, vault, typed policy/import logic and service controller were written for Rayren.

Exact embedded source revisions:

- AndroidLibXrayLite `0a02333081243df017453ccb432ad3cafcfd69e3`, LGPL-3.0.
- OpenFlux `1adc1fe1f4ac8180952779d4d580ee82a34abe98`, GPL-3.0.
- Original openlibrecommunity/olcrtc `f3ad8fb7c0d0fa981423f83269526fa94077c23d`, WTFPL v2. This is not the Apache-licensed Ghostlane fork.
- HEV socks5 tunnel `d9dca26c7ad0e494492244f0309e80ee583e739e`, MIT, including pinned lwIP, YAML and task-system sources/notices.
- Xray-core `v1.260327.1-0.20261010092107-701af60772cd`, MPL-2.0, supplied through the Go dependency source tree.

Native source files were not modified except the build-time HEV JNI package macro and expansion of Git symlinks into file copies for Windows builds. Rayren's new native wrapper suppresses upstream logging and exposes a narrow typed API. Licenses of original files are retained; no upstream authorship is claimed.

`native/vendor` in the source archive contains the exact Go library sources and their original notices. `native/upstreams` contains the pinned source projects, including their build descriptions. `native/go.mod`, `native/go.sum`, `vendor/modules.txt` and `docs/research/native-modules.json` identify module versions. OpenFlux and AndroidLibXrayLite can be rebuilt and relinked by replacing `app/libs/rayrencore.aar`; APK signing uses a development key, with no user-modification lock.

Android/JVM components include Kotlin/kotlinx.coroutines (JetBrains), AndroidX/Compose/WorkManager (Android Open Source Project), Gson (Google), OkHttp/Okio (Square), ZXing (ZXing authors), ZXing Android Embedded (Journey Mobile), JSpecify and Bouncy Castle. Their notices are retained in packaged libraries and `app/src/main/assets/licenses`; Apache-2.0 and the Bouncy Castle permission text are supplied there.

Routing data: [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat/releases/tag/202610152206), repository GPL-3.0. GeoIP contains GeoLite2 data created by [MaxMind](https://www.maxmind.com), available under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). GeoSite uses [v2fly/domain-list-community](https://github.com/v2fly/domain-list-community) MIT data and sources identified in the [publisher README](https://github.com/Loyalsoldier/v2ray-rules-dat). No added blocking categories are enabled by default. Artifact URLs and checksum evidence are in `docs/research/geo-assets.json`.

The research-only Ghostlane, libXray and sing-box checkouts are not linked into the APK. Researching a repository does not imply its code is distributed in Rayren.
