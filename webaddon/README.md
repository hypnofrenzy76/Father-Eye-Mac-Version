# Father Eye Web Addon

Optional HTTPS panel for the Father Eye Mac fork. Lets you sign in
from a browser and remotely control the same Forge server the desktop
JavaFX panel manages, with full feature parity (start/stop/restart,
console, players, mobs, mods, stats, map, config).

The addon runs **inside the JavaFX panel's JVM** — so it is reachable
only while the desktop panel is open on the Mac. If you want headless
24/7 access, leave the panel running and the Mac awake (System
Settings → Battery → Prevent your Mac from sleeping).

## Enabling

Edit `~/Library/Application Support/FatherEye/config.json` and set:

```json
"webAddon": {
    "enabled": true,
    "port": 8443,
    "bindAddress": "0.0.0.0"
}
```

Restart the panel. On first start the addon:

1. Generates a self-signed TLS keystore at
   `~/Library/Application Support/FatherEye/webaddon-tls/`
   (using the bundled JDK's `keytool` — no external tools needed).
2. Generates a one-time admin password and prints it to the panel
   log + the in-app console:

   ```
   First-run web addon credentials generated:
       username: admin
       password: <random>
   ```

3. Starts listening on `https://<bind>:<port>`.

Sign in with those credentials, then change them via the web UI's
**Config** tab. The new password takes effect immediately for new
sign-ins.

## Recommended deployment: Tailscale

Plain port-forwarding to the open internet exposes the panel to the
world. Use [Tailscale](https://tailscale.com/) instead — it is free
for personal use and gives you:

- A private overlay network. Only devices you authorise can connect.
- Stable IPs (e.g. `100.64.0.10`) that don't change when your home
  ISP rotates your public IP.
- No port-forward on your router.
- Built-in WireGuard encryption on top of the addon's own TLS.

### Steps

1. Install Tailscale on the Mac. Sign in.
2. Install Tailscale on every device you want to log in from
   (laptop, phone, etc.). Sign in to the same tailnet.
3. Look up your Mac's tailnet IP with:

   ```
   tailscale ip -4
   ```

   This returns something like `100.64.0.10`.

4. From any other tailnet device, open
   `https://100.64.0.10:8443` and sign in.

5. (Optional) Use Tailscale's MagicDNS so you can reach the Mac by
   hostname (`https://your-mac.tailnet-name.ts.net:8443`).

6. (Optional) Tailscale can issue a real Let's Encrypt cert for
   your tailnet hostname via `tailscale cert <hostname>`. Drop the
   resulting `cert.pem` + `key.pem` into
   `~/Library/Application Support/FatherEye/webaddon-tls/` and the
   addon will use them on next start (the self-signed keystore is
   only used when neither PEM is present).

## Other deployments

- **LAN only.** Set `bindAddress = "192.168.x.y"` (your Mac's LAN
  IP) and access from another device on the same Wi-Fi at
  `https://192.168.x.y:8443`. No internet exposure at all.
- **Plain port-forward.** Set `bindAddress = "0.0.0.0"`, forward
  port 8443 on your router to your Mac's LAN IP, then reach
  `https://<your-public-ip>:8443`. Acceptable for a hobby panel
  with strong credentials, but Tailscale is materially safer.
- **Behind a reverse proxy (Caddy / Cloudflare Tunnel / nginx).**
  Bind the addon to `127.0.0.1` and point the proxy at it. The
  proxy can terminate TLS with a real cert and the addon then
  serves plain HTTP on loopback. Note: the addon's TLS is on by
  default; if you want plain HTTP behind a proxy, you currently
  need to drop the proxy's cert into `webaddon-tls/` so the addon
  serves the same cert (or run two listeners — not yet supported).

## Security model

- **Single user.** One username + password, configured in-app. No
  signup, no guest, no multi-user. You are the only operator.
- **BCrypt cost 12** for the password hash. Plaintext never touches
  disk and is wiped from memory after hashing.
- **Session cookie** — opaque 32-byte random token, `HttpOnly` +
  `Secure` + `SameSite=Strict`. In-memory only; panel restart
  invalidates every session.
- **Brute-force throttle.** Five failures per IP per 15 minutes →
  one-hour lockout. Tunable in `config.json` under `webAddon`.
- **Security headers** — HSTS, CSP (self-only), X-Frame-Options
  DENY, X-Content-Type-Options nosniff, no-referrer.
- **TLS 1.2 / 1.3 only.** Older protocols are disabled.

## Disabling

Two options:
- **Soft:** set `"enabled": false` in `config.json` and restart the
  panel.
- **Hard:** delete the `fathereye-webaddon-*.jar` from the panel's
  install lib directory. ServiceLoader will not find it; the addon
  will not load. Useful if you want to be 100% sure no listener is
  bound regardless of config drift.

## REST API surface

All endpoints require a valid session cookie (`fe_sess`).

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/auth/login` | `{username, password}` |
| `POST` | `/api/auth/logout` | |
| `GET`  | `/api/auth/me` | |
| `GET`  | `/api/server/state` | |
| `POST` | `/api/server/start` | |
| `POST` | `/api/server/stop` | |
| `POST` | `/api/server/restart` | |
| `POST` | `/api/server/command` | `{command}` |
| `GET`  | `/api/players` | last cached snapshot |
| `POST` | `/api/players/{name}/kick` | `{reason}` |
| `POST` | `/api/players/{name}/ban` | `{reason}` |
| `POST` | `/api/players/{name}/op` | `{op}` |
| `POST` | `/api/players/{name}/whitelist` | `{add}` |
| `POST` | `/api/players/{name}/teleport` | `{x,y,z,dim?}` |
| `POST` | `/api/world/weather` | `{weather, durationTicks?}` |
| `POST` | `/api/world/time` | `{time}` |
| `POST` | `/api/mobs/clear` | `{x1,z1,x2,z2,dim?,hostileOnly?}` |
| `POST` | `/api/entities/{id}/kill` | |
| `POST` | `/api/backup` | |
| `GET`  | `/api/config` | |
| `PUT`  | `/api/config` | partial merge |
| `PUT`  | `/api/config/credentials` | `{username,currentPassword,newPassword}` |
| `GET`  | `/api/map/dimensions` | |
| `GET`  | `/api/map/tile?dim=&x=&z=` | |

`GET /ws` opens the WebSocket topic stream — see
`webaddon/src/main/java/io/fathereye/webaddon/ws/TopicBridge.java`
for the wire protocol.
