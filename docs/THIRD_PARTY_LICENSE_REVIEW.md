# Лицензии

16.10.2026. Пользователь явно выбрал открытый GPLv3 Rayren до включения стороннего кода. Это инженерный учёт проверенных LICENSE; транзитивный dependency inventory сохраняется вместе со сборкой.

| Project / repository | LICENSE | Study | Link / modify | Source obligations | Usage |
|---|---|---|---|---|---|
| [Veil](https://github.com/venterum/veil/blob/master/LICENSE) | GPL-3.0 | Yes | Yes under GPL | Corresponding combined source when conveying | Native build reference, attributed selective reuse |
| [Ghostlane](https://github.com/ghostlane-project/ghostlane/blob/main/LICENSE) | MIT | Yes | Yes, retain notices | No general source obligation from MIT | Architecture reference |
| [OpenFlux](https://github.com/p1neappleXpress/OpenFlux/blob/main/LICENSE) | GPL-3.0 | Yes | Yes under GPL | Corresponding combined source | Embedded engine |
| [OpenFluxAndroid](https://github.com/p1neappleXpress/OpenFluxAndroid/blob/main/LICENSE) | GPL-3.0 | Yes | Yes under GPL | Combined derivative source | Reference only |
| [olcRTC Ghostlane fork](https://github.com/ghostlane-project/olcrtc/blob/proofkit/LICENSE) | Apache-2.0 | Yes | Yes with NOTICE | Preserve notices and changes; patent terms | Candidate native engine |
| openlibrecommunity/olcrtc older checkout | WTFPL v2 in local file | Yes | Yes | Keep provenance; do not assign fork license to older code | Inspect exact pinned revision separately |
| [Xray-core](https://github.com/XTLS/Xray-core/blob/main/LICENSE) | MPL-2.0 | Yes | Yes | Modified covered files; MPL notices | Core/router |
| [libXray](https://github.com/XTLS/libXray/blob/main/LICENSE) | MIT | Yes | Yes | Copyright/permission notice | Compared alternative |
| [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite/blob/master/LICENSE) | LGPL-3.0 | Yes | Yes | Library source/relink obligations, compatible GPL combined distribution | Chosen binding |
| [sing-box](https://github.com/SagerNet/sing-box/blob/dev/LICENSE) | GPL-3.0-or-later; additional name/association text | Yes | Review exact text before distribution | GPL; name restriction needs separate review | Not included in selected packet path |
| [v2rayNG](https://github.com/2dust/v2rayNG/blob/master/LICENSE) | GPL-3.0 | Yes | Yes | Combined derivative source | Reference only |
| [HEV](https://github.com/heiher/hev-socks5-tunnel/blob/main/LICENSE) | MIT | Yes | Yes | Retain notice; bundled lwIP/YAML notices | Native TUN adapter |

Rayren must include LICENSE/NOTICE and the exact source/build instructions for binaries it distributes. Merely linking to upstream HEAD is not a substitute for matching corresponding source. A debug APK is not a licence exemption. No commercial/proprietary carve-out is assumed. No publishing or release signing keys are created implicitly.

GPL obligations informed the user decision; see [GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.html). Trademark/name obligations remain distinct from code licensing. Do not distribute the application under Veil/OpenFlux/Ghostlane branding.
