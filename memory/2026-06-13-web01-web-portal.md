# 2026-06-13, Web-01: browser web portal for remote control over Tailscale

## What changed
A new `webportal` Gradle module gives the same control surface as the
JavaFX panel through a browser, intended to be reached remotely over a
Tailscale tailnet. It runs on the same Mac as the bridge, connects to the
bridge over localhost TCP using the panel's exact IPC wire format, and
fans bridge topics out to browsers over WebSocket. No mod, server jar, or
panel code was modified; this is purely additive.

- `settings.gradle`: added `include 'webportal'`.
- `webportal/build.gradle`: `application` + `java` plugins, main class
  `io.fathereye.webportal.WebPortalMain`, JDK 17 toolchain, version pinned
  to `panelVersion` (0.3.3-mac.1). Dependencies: jackson-databind,
  jackson-dataformat-cbor, slf4j-api, logback-classic. Deliberately no
  JavaFX and no JNA.
- `webportal/src/main/java/io/fathereye/webportal/ipc/` (10 files):
  copies of the panel IPC classes (PipeClient, PipeReader, PipeCodecs,
  PipeEnvelope, PipeFrame, Transport, TcpTransport, TopicDispatcher,
  MarkerDiscovery, PlatformPaths). The Windows named-pipe transport was
  not copied (it is the only JNA consumer); `chooseTransport` keeps only
  the TCP path. Copied rather than depended-on so the portal never pulls
  JavaFX/JNA from the panel module.
- `Auth.java`: single long operator password stored only as a salted
  PBKDF2WithHmacSHA256 hash (210k iterations, 256-bit key, 16-byte salt)
  at `~/Library/Application Support/FatherEye/webportal/auth.json`
  (chmod 600). First run reads `FATHEREYE_WEB_PASSWORD` (>= 16 chars) or
  generates a 24-byte base64url password printed once to the console.
  256-bit opaque session tokens, 12-hour sliding expiry, constant-time
  hash compare, per-source lockout (8 failures -> 15-minute lockout).
  `--set-password=` writes a new hash and exits.
- `WebSocketConnection.java`: self-contained RFC 6455 server endpoint
  (SHA-1/Base64 accept-key, frame encode/decode, masking, close), no
  external WebSocket dependency.
- `HttpServerCore.java`: minimal HTTP/1.1 server on a raw `ServerSocket`
  so the `/ws` route can hijack the socket for the WebSocket upgrade.
  Adds `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer` to every response.
- `BridgeConnection.java`: mirrors the panel connect/handshake/subscribe
  sequence (tps_topic, console_log, players_topic, mobs_topic,
  mods_impact_topic, chunks_topic), caches the latest snapshot per topic
  plus the Welcome, auto-reconnects every 5 s, and forwards RPC requests.
- `WebPortalMain.java`: wiring + routing. Routes: `/` (login or redirect
  to `/app`), `/login` (POST, form-encoded `password`), `/logout`,
  `/app`, `/app.js`, `/app.css`, `/ws`. Session cookie is
  `fe_session` (HttpOnly, SameSite=Strict, not Secure so plain HTTP over
  the tailnet works). Browser RPC shape
  `{"type":"rpc","reqId":N,"op":...,"args":{...}}` is forwarded to the
  bridge and the response returned tagged with the same reqId. New
  WS clients get the cached Welcome + every cached topic snapshot
  immediately. Flags: `--port=` (default 8765), `--host=` (default
  0.0.0.0 for tailnet reachability; 127.0.0.1 to restrict to loopback),
  `--set-password=`.
- `WebPortalAssets.java`: the entire browser UI (HTML/CSS/JS) as inline
  Java text blocks, replicating the panel panes: Stats (TPS/MSPT/heap +
  charts), Console, Players (kick/ban/op/whitelist/tp), Mobs, Mods
  impact, interactive Map (chunk tiles, pan/zoom, player markers), World
  (weather/time), Arcanum admin, and server stop/restart.

## Why
Maintainer asked for browser control that appears and functions identical
to the panel, reachable remotely via Tailscale, behind a very long
password. Reusing the panel's IPC wire format means the portal speaks to
the existing bridge unchanged; building the HTTP/WebSocket stack from the
JDK alone keeps the dependency surface tiny and avoids a servlet
container.

## Build + smoke test (this session)
- `./gradlew :webportal:compileJava` and `:webportal:installDist`: BUILD
  SUCCESSFUL (one benign unchecked-operations note in WebPortalMain).
- Ran `webportal/build/install/webportal/bin/webportal --port=8765`:
  boots, generates+prints a password, binds 0.0.0.0:8765.
- HTTP checks (curl): `/` serves login unauthenticated; `/app` 303 ->
  `/` unauthenticated; `/app.js` 401 unauthenticated; security headers
  present on every response. `/login` with form-encoded correct password
  returns 303 -> `/app` and sets the HttpOnly fe_session cookie; wrong
  password returns 401. With the cookie, `/app` (6345 B), `/app.js`
  (17862 B), `/app.css` (4982 B) all serve 200.
- WebSocket: `/ws` upgrade with the canonical RFC 6455 test key returned
  `101 Switching Protocols` and `Sec-WebSocket-Accept:
  s3pPLMBiTxaQ9kYGzzhZRbK+xOo=` (matches the spec vector), then streamed
  a `{"type":"status","status":"disconnected"}` text frame (bridge not
  running during the test, as expected).

## How to run it
1. Start the Father Eye panel/server so the bridge is up on this Mac
   (the portal connects to the bridge's localhost TCP marker).
2. Optionally set your own password:
   `FATHEREYE_WEB_PASSWORD='<>=16 char passphrase>' ./gradlew :webportal:run`
   or run once and copy the generated password from the console.
3. `./gradlew :webportal:run --args="--port=8765"` (or run the installed
   `webportal/build/install/webportal/bin/webportal`).
4. Tailscale: install Tailscale on this Mac and the remote device, sign
   both into the same tailnet, then open
   `http://<this-mac-tailscale-name>:8765` from the remote device and log
   in with the password.

## Notes / decisions
- Cookie is intentionally not marked Secure: Tailscale provides transport
  encryption (WireGuard), so plain HTTP over the tailnet needs no TLS
  cert. To restrict to loopback only, run with `--host=127.0.0.1`.
- Version stays 0.3.3-mac.1; this is additive (new module), no existing
  artifact changed, bridge handshake pin untouched.
- Changes are uncommitted; maintainer commits on explicit request only.
