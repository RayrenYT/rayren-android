# Dependencies

```mermaid
flowchart TD
  UI[app/ui Compose] --> VM[AppController / StateFlow]
  VM --> D[domain: profiles, policy, parser, selection]
  VM --> V[app/vpn RayrenVpnService]
  V --> I[TransportEngine contract]
  I --> X[XrayAdapter]
  I --> F[OpenFluxAdapter]
  I --> O[OlcRtcAdapter]
  X --> A[Combined gomobile AAR]
  F --> A
  O --> A
  V --> H[HEV JNI]
  VM --> S[Keystore vault + HTTPS subscription loader]
  S --> D
```

Domain has no Android/native imports. UI never calls gomobile. Native libraries never create Android services. `docs/research` contains public provenance only; `.research` is an ignored local source/build workspace. No production credential belongs in repository, examples, test fixtures or APK.
