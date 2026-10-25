# DNS и маршрутизация

## Packet policy

Android per-app filter precedes TUN. Within TUN: (1) mandatory DNS interception and unsupported-family block, (2) explicit user rules in order, (3) optional LAN bypass, (4) selected Russia-direct rules, (5) optional GeoSite/GeoIP sets, (6) default proxy or explicit Proxy Only direct. BLOCK has no fallback. Domain rules cannot reliably classify arbitrary encrypted traffic without DNS/sniffing; no promise to identify all ECH/DoH connections.

Common Xray router provides identical DIRECT/PROXY/BLOCK semantics regardless of selected engine. DIRECT is freedom from the device, protected/exempt UID; never a request to the remote exit. GeoIP is IP geography and does not mean every Russian service; `.ru/.рф` is a domain preset and is not equivalent to all Russian resources. Rule explanation reports which compiled user/preset rule matched when available; never fabricates an inspector result.

## DNS

TUN advertises a virtual DNS address. Both TCP/UDP port 53 are routed to the core DNS outbound before general rules. Default resolver uses HTTPS with certificate validation and a numeric bootstrap. DNS outbound follows proxy; no `https+local` unless user selected direct DNS. Android/provider endpoint bootstrap is explicitly control-plane traffic and may use the physical network, because the tunnel cannot resolve its own endpoint through itself.

Design separates app DNS from transport bootstrap, resolver route from resolver protocol, and configured preference from effective policy. Strict mode overrides direct/split DNS: app DNS must use tunnel. Direct DNS requires an explicit setting and conflicts with strict mode. Split DNS uses resolver-specific domain lists and deterministic fallback rules. DNS failure remains a failure, not silent system fallback. DNS cache must be invalidated on network/config policy changes.

DoH is native Xray. The alpha implements DoT in `native/bridge/dot.go`: framed DNS on a loopback TCP listener → authenticated probe SOCKS inbound → selected transport → TLS 1.2+ with certificate/hostname verification. No plaintext or system-DNS fallback exists. Four local TLS/SOCKS tests cover success, untrusted certificate, bad credentials and teardown. Public resolver and Android operation still require device tests. DoQ/DNSCrypt are future capabilities. FakeDNS is disabled. A system resolver used through VPN is distinct from direct system DNS.

OpenFlux's upstream SOCKS implementation resolves domain targets via the local OS. Rayren therefore sets outbound targetStrategy=ForceIPv4 for unsupported-IPv6 transports; application domains are resolved by the common DNS engine before SOCKS forwarding. Resolver/provider endpoint names are a separate bootstrap exception and cached in DNS hosts. Use numeric resolver endpoints to avoid resolver-name bootstrap. Split DNS servers have distinct inbound tags, explicit PROXY/DIRECT routes and finalQuery/disabled fallback; strict mode rejects direct DNS rules.

Android Private DNS can send TLS to port 853, bypassing a port-53 interceptor but still carried by full-tunnel routing. Diagnostics show `LinkProperties.isPrivateDnsActive`/server name; the app never silently changes the device setting. Strict mode blocks direct rules for DNS ports; user-app DoH is ordinary HTTPS and cannot be globally recognised without invasive interception.

## Per-app

ALL excludes own UID only. INCLUDE adds installed selected packages except Rayren; an empty list is rejected, because an empty Builder allowlist means ALL. EXCLUDE disallows selected packages plus Rayren. Allowed/disallowed calls must not be mixed. Retain selections by package name; ignore temporarily missing packages when applying, never silently turn an empty include set into ALL. Work profiles and other users have independent VPN scopes; package enumeration cannot assert coverage of them.

## LAN / IPv6

LAN ranges: 10/8, 172.16/12, 192.168/16, IPv6 fc00::/7 and fe80::/10. LAN bypass is a routing decision through a protected local direct socket, not a second VPN. Link-local IPv6 scope/interface may require native support; do not claim general multicast discovery through a TUN.

Capture IPv4 0/0 and IPv6 ::/0. For IPv4-only transport, retain ::/0 but route it to BLOCK; suppress AAAA only as an additional usability measure, not as leak protection. Xray dual-stack is tested separately from OpenFlux IPv4. IPv6-only mode is valid only when every selected AUTO candidate can carry it; otherwise reject or filter candidates explicitly.

## Acceptance tests

External packet capture of physical interface while DNS probe, AAAA/IPv6 literal request, transport stop and AUTO switching occur. Verify only approved provider/endpoint/bootstrap/control traffic leaves directly. Test both UDP and TCP DNS, NXDOMAIN, timeout, DoH/DoT certificate failure, Private DNS strict mode, IPv6-only uplink/NAT64, custom rules, LAN, missing include packages and lockdown VPN. Functional success requires actual remote response and observed exit identity; a TCP connect or green core process is insufficient.

Sources: [Android VPN guide](https://developer.android.com/develop/connectivity/vpn), [VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder), [Xray DNS](https://xtls.github.io/config/dns.html).
