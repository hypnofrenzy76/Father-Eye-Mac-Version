# Father Eye Mac Version, Auto-Memory Index

Newest first. Each entry file explains one substantive change in plain technical terms.

| Date | Entry | Summary |
|------|-------|---------|
| 2026-06-14 | [2026-06-14-web02-portal-empty-tabs-fix.md](2026-06-14-web02-portal-empty-tabs-fix.md) | Web-02: web portal tabs were all empty (only Console worked) because `BridgeConnection.handleTopic` forwarded the raw `{seq,data}` Snapshot wrapper while the browser JS reads topic fields directly; fix unwraps `data` for Snapshot/Delta before caching and fan-out, Events pass through; new end-to-end mock-bridge regression test; triple-audited |
| 2026-06-13 | [2026-06-13-web01-web-portal.md](2026-06-13-web01-web-portal.md) | Web-01: new `webportal` module gives full panel control surface in a browser over Tailscale; reuses panel IPC (TCP-only, no JNA/JavaFX), PBKDF2 long-password auth, RFC 6455 WebSocket, JDK-only HTTP server; built and smoke-tested |
| 2026-06-12 | [2026-06-12-pnl73-arcanum-player-tab.md](2026-06-12-pnl73-arcanum-player-tab.md) | Pnl-73: Arcanum Players tab fixed panel-side (UUID-map parsing, homesCount), live online status from players_topic, persistent joined-name cache; app rebuilt and reinstalled at 0.3.3-mac.1 |
| 2026-06-12 | [2026-06-12-doc04-version-pins.md](2026-06-12-doc04-version-pins.md) | Doc-04: mapcore doc pins 0.3.1-mac.1 -> 0.3.3-mac.1, mapcore 0.3.3-mac.1 published to local-maven, HANDOFF.md retained |
