# Phone Proxy

An Android app that runs an on-device **SOCKS5 + HTTP/HTTPS proxy**, so a PC
tethered to the phone (USB modem or Wi-Fi hotspot) can reach the internet
**through the VPN that is active on the phone**.

## Why this is needed

On Android, traffic from a tethered device (your PC) **bypasses the phone's
VPN** by default — the system VPN only tunnels the traffic of apps running on
the phone, not tethered clients. So plain tethering sends your PC straight out
over the mobile network, ignoring the VPN.

Phone Proxy works around this: it is an ordinary app, so its own network sockets
**are** routed through the VPN. The PC sends its traffic to the proxy, and the
proxy forwards it out through the tunnel:

```
PC (SOCKS5/HTTP client) --USB/Wi-Fi tether--> [Phone Proxy app] --VPN tunnel--> Internet
```

## Usage

1. Enable your VPN on the phone.
2. Tether the phone to the PC (USB tethering or Wi-Fi hotspot).
3. Open Phone Proxy, choose a port (default `8080`), optionally enable
   username/password auth, and tap **Start proxy**.
4. The app shows the address(es) to use, e.g. `192.168.42.129:8080` for USB
   tethering or `192.168.43.1:8080` for a Wi-Fi hotspot.
5. Configure the PC to use that address as a **SOCKS5** proxy (recommended) or
   an **HTTP/HTTPS** proxy. The same port serves both — the protocol is detected
   automatically from each connection.

### Example PC configuration

SOCKS5 with curl:

```
curl --socks5-hostname 192.168.42.129:8080 https://ifconfig.me
```

HTTP/HTTPS with curl:

```
curl --proxy http://192.168.42.129:8080 https://ifconfig.me
```

(`--socks5-hostname` keeps DNS resolution on the phone so DNS also goes through
the VPN.)

## How it works

- **Single port, both protocols.** The first byte of each connection selects the
  handler: `0x05` -> SOCKS5, anything else -> HTTP. See
  `app/src/main/java/com/phoneproxy/app/proxy/ProxyServer.kt`.
- **SOCKS5** (`Socks5Handler.kt`): RFC 1928 `CONNECT`, with optional RFC 1929
  username/password auth. Hostnames are resolved on the device so DNS also
  travels through the VPN.
- **HTTP/HTTPS** (`HttpHandler.kt`): `CONNECT` tunneling for TLS plus absolute-URI
  forwarding for plain HTTP, with optional `Proxy-Authorization: Basic` auth.
- **Foreground service** (`ProxyService.kt`) keeps the proxy alive with an
  ongoing notification and a Stop action.
- No root required.

## Build

Requires the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Security notes

- Bind the proxy to a trusted tether network only. Anyone who can reach the
  listening address can use the proxy; enable username/password auth if the
  hotspot is shared.
- For SOCKS5, prefer remote DNS on the client (e.g. `--socks5-hostname`) to avoid
  DNS leaking outside the VPN.
