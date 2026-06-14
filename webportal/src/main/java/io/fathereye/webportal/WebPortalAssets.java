package io.fathereye.webportal;

/**
 * Static browser assets (login page, app shell, CSS, JS) served by the portal.
 * Kept inline as text blocks so the module ships as a single self-contained
 * jar with no resource-loading or external file dependencies.
 *
 * <p>The app UI replicates the JavaFX panel's control surface: a toolbar with
 * server-state/uptime/heartbeat and Stop/Restart, plus tabs for Stats,
 * Console, Players, Mobs, Mods, Map, World, and (capability-gated) Arcanum.
 * Every action is an RPC forwarded to the bridge over the WebSocket, using
 * the exact op names and argument keys the panel uses.
 */
public final class WebPortalAssets {

    private WebPortalAssets() {}

    public static final String LOGIN_HTML = loginHtml("");

    public static String loginHtmlWithError() {
        return loginHtml("<p class=\"err\">Incorrect password, or too many attempts. Try again.</p>");
    }

    private static String loginHtml(String errorBlock) {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Father Eye - Sign in</title>
              <link rel="stylesheet" href="/app.css">
            </head>
            <body class="login-body">
              <form class="login-card" method="POST" action="/login">
                <h1>Father Eye</h1>
                <p class="subtitle">Remote server control</p>
                __ERROR__
                <input type="password" name="password" placeholder="Operator password"
                       autocomplete="current-password" autofocus required>
                <button type="submit">Sign in</button>
                <p class="hint">Protected console. Access over your private tailnet only.</p>
              </form>
            </body>
            </html>
            """.replace("__ERROR__", errorBlock);
    }

    public static final String APP_HTML = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Father Eye - Control</title>
          <link rel="stylesheet" href="/app.css">
        </head>
        <body>
          <header id="toolbar">
            <span class="brand">Father Eye</span>
            <span id="stateBadge" class="badge state-unknown">Server: --</span>
            <span id="uptime" class="meter">Uptime: --</span>
            <span id="heartbeat" class="meter">Heartbeat: --</span>
            <span class="spacer"></span>
            <button id="btnRestart" class="danger">Restart</button>
            <button id="btnStop" class="danger">Stop</button>
            <button id="btnLogout" onclick="location.href='/logout'">Sign out</button>
          </header>
          <div id="connBanner" class="conn-banner">Connecting to the server bridge...</div>
          <div id="alertBanner" class="alert hidden"></div>
          <nav id="tabs">
            <button data-tab="stats" class="active">Stats</button>
            <button data-tab="console">Console</button>
            <button data-tab="players">Players</button>
            <button data-tab="mobs">Mobs</button>
            <button data-tab="mods">Mods</button>
            <button data-tab="map">Map</button>
            <button data-tab="world">World</button>
            <button data-tab="backups">Backups</button>
            <button data-tab="rollback">Rollback</button>
            <button data-tab="arcanum" id="arcanumTab" class="hidden">Arcanum</button>
          </nav>
          <main>
            <!-- STATS -->
            <section id="tab-stats" class="tab active">
              <div class="grid stats-grid">
                <div class="stat"><label>TPS 20s</label><span id="s-tps20">--</span></div>
                <div class="stat"><label>TPS 1m</label><span id="s-tps1m">--</span></div>
                <div class="stat"><label>TPS 5m</label><span id="s-tps5m">--</span></div>
                <div class="stat"><label>MSPT avg</label><span id="s-msptAvg">--</span></div>
                <div class="stat"><label>MSPT p95</label><span id="s-msptP95">--</span></div>
                <div class="stat"><label>Heap</label><span id="s-heap">--</span></div>
                <div class="stat"><label>GC ms/s</label><span id="s-gc">--</span></div>
                <div class="stat"><label>CPU</label><span id="s-cpu">--</span></div>
                <div class="stat"><label>Threads</label><span id="s-threads">--</span></div>
                <div class="stat"><label>Health</label><span id="s-health">--</span></div>
              </div>
              <div class="bars">
                <div class="bar-row"><label>Heap</label><div class="bar"><i id="bar-heap"></i></div></div>
                <div class="bar-row"><label>TPS deficit</label><div class="bar"><i id="bar-tps"></i></div></div>
                <div class="bar-row"><label>MSPT load</label><div class="bar"><i id="bar-mspt"></i></div></div>
                <div class="bar-row"><label>GC pressure</label><div class="bar"><i id="bar-gc"></i></div></div>
                <div class="bar-row"><label>CPU load</label><div class="bar"><i id="bar-cpu"></i></div></div>
              </div>
              <canvas id="tpsChart" height="120"></canvas>
            </section>

            <!-- CONSOLE -->
            <section id="tab-console" class="tab">
              <div class="console-toolbar">
                <label><input type="checkbox" id="autoscroll" checked> Auto-scroll</label>
                <span id="dropped" class="meter">Dropped: 0</span>
              </div>
              <pre id="consoleLog" class="console"></pre>
              <div class="cmd-row">
                <input id="cmdInput" type="text" placeholder="Type a console command and press Enter...">
                <button id="cmdRun">Run</button>
              </div>
            </section>

            <!-- PLAYERS -->
            <section id="tab-players" class="tab">
              <p id="playerCount" class="meter">0 players online</p>
              <table id="playersTable">
                <thead><tr><th>Name</th><th>UUID</th><th>Dim</th><th>X</th><th>Y</th><th>Z</th>
                  <th>HP</th><th>Food</th><th>Ping</th><th>Mode</th><th>Actions</th></tr></thead>
                <tbody></tbody>
              </table>
            </section>

            <!-- MOBS -->
            <section id="tab-mobs" class="tab">
              <p id="mobTotal" class="meter">Total entities: 0</p>
              <table id="mobsTable">
                <thead><tr><th>Dimension</th><th>Mod</th><th>Total</th><th>Hostile</th>
                  <th>Passive</th><th>Items</th></tr></thead>
                <tbody></tbody>
              </table>
            </section>

            <!-- MODS -->
            <section id="tab-mods" class="tab">
              <p id="modsHeader" class="meter">Mod impact (proportional, approx)</p>
              <table id="modsTable">
                <thead><tr><th>Mod</th><th>Impact</th><th>Entities</th><th>Tile Entities</th></tr></thead>
                <tbody></tbody>
              </table>
            </section>

            <!-- MAP -->
            <section id="tab-map" class="tab">
              <div class="map-toolbar">
                <select id="mapDim"></select>
                <button id="mapCenter">Center (0,0)</button>
                <button id="mapFit">Fit All</button>
                <span id="mapCoords" class="meter">(--, --)</span>
                <span id="mapLoading" class="meter"></span>
              </div>
              <canvas id="mapCanvas" height="520"></canvas>
            </section>

            <!-- WORLD -->
            <section id="tab-world" class="tab">
              <div class="world-group">
                <h3>Weather</h3>
                <button data-weather="clear">Clear</button>
                <button data-weather="rain">Rain</button>
                <button data-weather="thunder">Thunder</button>
              </div>
              <div class="world-group">
                <h3>Time</h3>
                <button data-time="0">Day (0)</button>
                <button data-time="6000">Noon (6000)</button>
                <button data-time="13000">Night (13000)</button>
                <button data-time="18000">Midnight (18000)</button>
                <span class="custom-row">
                  <input id="timeValue" type="number" placeholder="ticks">
                  <button id="timeSet">Set time</button>
                </span>
              </div>
            </section>

            <!-- BACKUPS (create + browse; live per-player restore works while running) -->
            <section id="tab-backups" class="tab">
              <div class="section-head">
                <h2>Backups</h2>
                <p class="meter">Create a compressed snapshot of the world and player data on the
                  external drive. Each backup keeps world and player data as independent archives so
                  either can be rolled back on its own from the Rollback tab. To restore a single
                  player's full state into the live server, use "Live Players" below.</p>
              </div>
              <div class="backup-toolbar">
                <input id="bkLabel" type="text" placeholder="Optional label for this backup">
                <button id="bkRun" class="primary">Back up now</button>
                <button id="bkRefresh">Refresh list</button>
                <span id="bkDrive" class="meter"></span>
              </div>
              <div id="bkJob" class="bk-job hidden"></div>
              <table id="backupsTable">
                <thead><tr><th>Created</th><th>Label</th><th>World</th><th>Contents</th>
                  <th>Size</th><th>Live restore</th></tr></thead>
                <tbody></tbody>
              </table>

              <!-- Live per-player restore modal -->
              <div id="bkPlayerModal" class="bk-modal hidden">
                <div class="bk-modal-card">
                  <div class="bk-modal-head">
                    <strong id="bkPmTitle">Restore a player</strong>
                    <button id="bkPmClose" class="bk-modal-x">&times;</button>
                  </div>
                  <p class="meter">Inject one player's full saved state (inventory, ender chest, XP,
                    health, position) from this backup into the running server. Online players are
                    restored live without a disconnect; offline players have their save file written.
                    A safety snapshot of their current state is taken first.</p>
                  <p class="meter" id="bkPmNote"></p>
                  <table id="bkPlayersTable">
                    <thead><tr><th>Name</th><th>UUID</th><th>Action</th></tr></thead>
                    <tbody></tbody>
                  </table>
                </div>
              </div>
            </section>

            <!-- ROLLBACK (whole-world / whole-playerdata restore; server must be stopped) -->
            <section id="tab-rollback" class="tab">
              <div class="section-head">
                <h2>Rollback</h2>
                <p class="rb-warn">Rollback overwrites the live world and/or player data with a backup.
                  The server must be STOPPED first. A safety snapshot of the current state is taken
                  automatically before anything is overwritten, so a bad rollback can itself be undone.</p>
              </div>
              <div class="backup-toolbar">
                <button id="rbRefresh">Refresh list</button>
                <span id="rbState" class="meter"></span>
              </div>
              <div id="rbJob" class="bk-job hidden"></div>
              <table id="rollbackTable">
                <thead><tr><th>Created</th><th>Label</th><th>World</th><th>Contents</th>
                  <th>Size</th><th>Restore</th></tr></thead>
                <tbody></tbody>
              </table>
            </section>

            <!-- ARCANUM -->
            <section id="tab-arcanum" class="tab">
              <div class="arcanum-toolbar">
                <button id="arcReload">Reload from server</button>
                <button id="arcApply">Apply to server</button>
                <span class="custom-row">
                  <input id="arcBroadcast" type="text" placeholder="Broadcast message">
                  <button id="arcBroadcastBtn">Broadcast</button>
                </span>
              </div>
              <p class="meter">Players (change rank inline)</p>
              <table id="arcPlayersTable">
                <thead><tr><th>Name</th><th>UUID</th><th>Rank</th><th>Homes</th>
                  <th>Online</th><th>Set Rank</th></tr></thead>
                <tbody></tbody>
              </table>
              <h3>Config (raw JSON, edit then Apply)</h3>
              <textarea id="arcConfig" spellcheck="false"></textarea>
            </section>
          </main>
          <script src="/app.js"></script>
        </body>
        </html>
        """;

    public static final String APP_CSS = """
        :root{--bg:#15171c;--panel:#1d2026;--line:#2c313b;--fg:#dfe3ea;--muted:#8b93a3;
          --accent:#c8a02a;--good:#3fae5a;--warn:#d8a33a;--bad:#d4504a;}
        *{box-sizing:border-box}
        body{margin:0;font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);
          color:var(--fg);font-size:14px}
        .hidden{display:none!important}
        /* login */
        .login-body{display:flex;align-items:center;justify-content:center;height:100vh}
        .login-card{background:var(--panel);border:1px solid var(--line);border-radius:10px;
          padding:32px;width:340px;display:flex;flex-direction:column;gap:12px}
        .login-card h1{margin:0;font-size:24px;color:var(--accent)}
        .login-card .subtitle{margin:0 0 8px;color:var(--muted)}
        .login-card input{padding:11px;border-radius:6px;border:1px solid var(--line);
          background:#11131a;color:var(--fg);font-size:15px}
        .login-card button{padding:11px;border:0;border-radius:6px;background:var(--accent);
          color:#000;font-weight:600;cursor:pointer;font-size:15px}
        .login-card .hint{color:var(--muted);font-size:12px;margin:4px 0 0}
        .login-card .err{color:var(--bad);margin:0;font-size:13px}
        /* toolbar */
        #toolbar{display:flex;align-items:center;gap:14px;padding:10px 16px;background:var(--panel);
          border-bottom:1px solid var(--line)}
        .brand{font-weight:700;color:var(--accent);font-size:16px}
        .spacer{flex:1}
        .badge{padding:4px 10px;border-radius:5px;font-weight:600;font-size:12px}
        .state-running{background:#163d22;color:var(--good)}
        .state-stopped{background:#3d1816;color:var(--bad)}
        .state-unknown{background:#33373f;color:var(--muted)}
        .meter{color:var(--muted);font-size:13px}
        button{background:#2a2e37;color:var(--fg);border:1px solid var(--line);border-radius:5px;
          padding:6px 12px;cursor:pointer;font-size:13px}
        button:hover{background:#343945}
        button.danger{background:#3a201e;border-color:#5a302c;color:#ffb4ae}
        button.danger:hover{background:#4a2826}
        .alert{padding:10px 16px;background:#4a3a12;color:#ffe9b0;border-bottom:1px solid #6a5420;cursor:pointer}
        /* connection status banner */
        .conn-banner{padding:8px 16px;font-size:13px;font-weight:600;border-bottom:1px solid var(--line);
          display:block}
        .conn-banner.conn-connecting{background:#33373f;color:#cfd6e2}
        .conn-banner.conn-offline{background:#3d1816;color:#ffb4ae;border-bottom-color:#5a302c}
        .conn-banner.conn-ok{display:none}
        button.primary{background:var(--accent);color:#000;border-color:var(--accent);font-weight:600}
        button.primary:hover{background:#d8b03a}
        .section-head{margin-bottom:12px}
        .section-head h2{margin:0 0 4px;font-size:18px;color:var(--accent)}
        .rb-warn{margin:0;padding:10px 12px;border-radius:8px;background:#3d1816;border:1px solid #5a302c;
          color:#ffd0cb;font-size:13px}
        /* tabs */
        #tabs{display:flex;gap:2px;padding:0 12px;background:var(--panel);border-bottom:1px solid var(--line);
          overflow-x:auto}
        #tabs button{border:0;border-radius:0;border-bottom:2px solid transparent;background:transparent;
          color:var(--muted);padding:10px 16px}
        #tabs button.active{color:var(--fg);border-bottom-color:var(--accent)}
        main{padding:16px}
        .tab{display:none}
        .tab.active{display:block}
        /* stats */
        .stats-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:10px}
        .stat{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:12px}
        .stat label{display:block;color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.5px}
        .stat span{font-size:22px;font-weight:600}
        .bars{margin:16px 0;display:flex;flex-direction:column;gap:8px;max-width:560px}
        .bar-row{display:flex;align-items:center;gap:10px}
        .bar-row label{width:100px;color:var(--muted);font-size:12px}
        .bar{flex:1;height:10px;background:#11131a;border-radius:5px;overflow:hidden}
        .bar i{display:block;height:100%;width:0;background:var(--accent)}
        canvas{width:100%;background:var(--panel);border:1px solid var(--line);border-radius:8px}
        /* console */
        .console-toolbar{display:flex;gap:16px;align-items:center;margin-bottom:8px}
        .console{height:54vh;overflow:auto;background:#0e1014;border:1px solid var(--line);border-radius:8px;
          padding:10px;margin:0;font-family:SFMono-Regular,Menlo,monospace;font-size:12px;white-space:pre-wrap;
          line-height:1.45}
        .cmd-row{display:flex;gap:8px;margin-top:8px}
        .cmd-row input{flex:1;padding:9px;border-radius:6px;border:1px solid var(--line);background:#11131a;color:var(--fg)}
        .ll-WARN{color:#e7c66a}.ll-ERROR{color:#f08a82}.ll-DEBUG{color:#7d8696}
        /* tables */
        table{width:100%;border-collapse:collapse;font-size:13px}
        th,td{text-align:left;padding:7px 9px;border-bottom:1px solid var(--line)}
        th{color:var(--muted);font-weight:600;position:sticky;top:0;background:var(--panel)}
        tr:hover td{background:#20242c}
        td button{padding:3px 8px;font-size:12px;margin-right:4px}
        .impact-cell{display:flex;align-items:center;gap:8px}
        .impact-bar{width:90px;height:8px;background:#11131a;border-radius:4px;overflow:hidden}
        .impact-bar i{display:block;height:100%;background:var(--accent)}
        /* map */
        .map-toolbar,.world-group,.arcanum-toolbar{display:flex;gap:10px;align-items:center;margin-bottom:10px;flex-wrap:wrap}
        .world-group{flex-direction:row}
        .world-group h3{margin:0 12px 0 0;font-size:14px;width:70px}
        .custom-row{display:inline-flex;gap:6px;align-items:center}
        .custom-row input{padding:6px;border-radius:5px;border:1px solid var(--line);background:#11131a;color:var(--fg)}
        select{padding:6px;border-radius:5px;border:1px solid var(--line);background:#11131a;color:var(--fg)}
        #mapCanvas{height:520px;cursor:grab}
        /* arcanum */
        #arcConfig{width:100%;height:40vh;background:#0e1014;color:var(--fg);border:1px solid var(--line);
          border-radius:8px;padding:10px;font-family:SFMono-Regular,Menlo,monospace;font-size:12px}
        /* backups */
        .backup-toolbar{display:flex;gap:10px;align-items:center;margin-bottom:10px;flex-wrap:wrap}
        .backup-toolbar input{padding:7px;border-radius:5px;border:1px solid var(--line);background:#11131a;
          color:var(--fg);min-width:240px}
        .bk-job{margin:8px 0;padding:10px 12px;border-radius:8px;border:1px solid var(--line);background:var(--panel)}
        .bk-job.running{border-color:#6a5420;background:#3a2e12;color:#ffe9b0}
        .bk-job.ok{border-color:#1f5a30;background:#163d22;color:#bff0c8}
        .bk-job.fail{border-color:#5a302c;background:#3d1816;color:#ffb4ae}
        .bk-job pre{margin:8px 0 0;max-height:160px;overflow:auto;font-family:SFMono-Regular,Menlo,monospace;
          font-size:11px;white-space:pre-wrap;color:var(--muted)}
        #backupsTable td button{margin-right:4px}
        .bk-restore-world{background:#1f3d52;border-color:#2c5a78}
        .bk-restore-player{background:#3d2f12;border-color:#6a5420}
        .bk-restore-both{background:#3a201e;border-color:#5a302c;color:#ffb4ae}
        .bk-restore-live{background:#16323d;border-color:#1f6478;color:#aee7f0}
        .bk-modal{position:fixed;inset:0;background:rgba(0,0,0,.6);display:flex;align-items:center;
          justify-content:center;z-index:50}
        .bk-modal.hidden{display:none}
        .bk-modal-card{background:var(--panel);border:1px solid var(--line);border-radius:10px;
          padding:16px 18px;max-width:680px;width:92%;max-height:80vh;overflow:auto}
        .bk-modal-head{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}
        .bk-modal-x{background:transparent;border:none;color:var(--muted);font-size:22px;cursor:pointer;line-height:1}
        #bkPlayersTable{width:100%;margin-top:8px}
        #bkPlayersTable td button{margin:0}
        """;

    public static final String APP_JS = """
        'use strict';
        // ---- WebSocket plumbing ----
        let ws = null;
        let reqSeq = 1;
        const pending = new Map();      // reqId -> {resolve,reject}
        const dims = {};                // dim -> {chunks:Map(key->tile), players:[]}
        let currentDim = 'minecraft:overworld';
        let welcome = null;
        let lastHeartbeat = 0;
        let serverStartedAt = 0;

        function connect(){
          const proto = location.protocol === 'https:' ? 'wss' : 'ws';
          ws = new WebSocket(proto + '://' + location.host + '/ws');
          ws.onopen = () => setBadge('connecting');
          ws.onclose = () => { setBadge('disconnected'); setTimeout(connect, 3000); };
          ws.onerror = () => {};
          ws.onmessage = (ev) => { lastHeartbeat = Date.now(); onMessage(JSON.parse(ev.data)); };
        }

        function rpc(op, args){
          return new Promise((resolve, reject) => {
            if(!ws || ws.readyState !== 1){ reject(new Error('not connected')); return; }
            const reqId = reqSeq++;
            pending.set(reqId, {resolve, reject});
            ws.send(JSON.stringify({type:'rpc', reqId, op, args: args || {}}));
            setTimeout(() => { if(pending.has(reqId)){ pending.delete(reqId); reject(new Error('timeout')); } }, 20000);
          });
        }

        function onMessage(m){
          if(m.type === 'welcome'){ welcome = m.payload; onWelcome(m.payload); }
          else if(m.type === 'status'){ if(m.status === 'disconnected') setBadge('disconnected'); else if(m.status === 'connected') setBadge('running'); }
          else if(m.type === 'topic'){ onTopic(m.topic, m.kind, m.payload); }
          else if(m.type === 'rpcResult'){
            const p = pending.get(m.reqId);
            if(p){ pending.delete(m.reqId); m.ok ? p.resolve(m.result) : p.reject(new Error(m.error || 'rpc error')); }
          }
        }

        // ---- toolbar / state ----
        function setBadge(state){
          const b = document.getElementById('stateBadge');
          b.className = 'badge ' + (state==='running'?'state-running':state==='disconnected'?'state-stopped':'state-unknown');
          b.textContent = 'Server: ' + (state==='running'?'RUNNING':state==='disconnected'?'OFFLINE':'...');
          setConnBanner(state);
        }
        // A persistent banner so blank tabs are never a mystery: it explains
        // whether we are still connecting, connected, or the server is offline.
        function setConnBanner(state){
          const el = document.getElementById('connBanner');
          if(!el) return;
          if(state==='running'){
            el.className = 'conn-banner conn-ok';
            el.textContent = '';
          } else if(state==='disconnected'){
            el.className = 'conn-banner conn-offline';
            el.textContent = 'Server is offline. Live tabs will populate automatically once the server is running.';
          } else {
            el.className = 'conn-banner conn-connecting';
            el.textContent = 'Connecting to the server bridge...';
          }
        }
        function onWelcome(w){
          setBadge('running');
          serverStartedAt = w.startedAtEpochMs || 0;
          // dimension picker
          const sel = document.getElementById('mapDim');
          sel.innerHTML='';
          (w.dimensions && w.dimensions.length ? w.dimensions : ['minecraft:overworld']).forEach(d => {
            const o=document.createElement('option'); o.value=d; o.textContent=d; sel.appendChild(o);
          });
          currentDim = sel.value || 'minecraft:overworld';
          document.getElementById('arcanumTab').classList.toggle('hidden', !w.hasArcanum);
          knownMods = w.modIds || [];
          renderMods();
        }

        setInterval(() => {
          // uptime
          if(serverStartedAt > 0){
            let s = Math.floor((Date.now()-serverStartedAt)/1000);
            const h=Math.floor(s/3600); s%=3600; const m=Math.floor(s/60); s%=60;
            document.getElementById('uptime').textContent = `Uptime: ${h}h ${m}m ${s}s`;
          } else document.getElementById('uptime').textContent = 'Uptime: --';
          // heartbeat
          const hb = lastHeartbeat ? Math.floor((Date.now()-lastHeartbeat)/1000) : -1;
          const el = document.getElementById('heartbeat');
          el.textContent = hb < 0 ? 'Heartbeat: --' : `Heartbeat: ${hb}s`;
          el.style.color = hb < 0 ? '#8b93a3' : hb < 5 ? '#3fae5a' : hb < 30 ? '#d8a33a' : '#d4504a';
        }, 1000);

        // ---- topic routing ----
        function onTopic(topic, kind, p){
          if(!p) return;
          switch(topic){
            case 'tps_topic': renderStats(p); pushTps(p); break;
            case 'console_log': appendConsole(p); break;
            case 'players_topic': renderPlayers(p); updateMapPlayers(p); break;
            case 'mobs_topic': renderMobs(p); break;
            case 'mods_impact_topic': lastModsImpact = p; renderMods(); break;
            case 'chunks_topic': onChunks(p); break;
          }
        }

        // ---- STATS ----
        const tpsHistory = [];
        function fmt(n, d){ return (n==null||isNaN(n)) ? '--' : Number(n).toFixed(d==null?1:d); }
        function renderStats(p){
          document.getElementById('s-tps20').textContent = fmt(p.tps20s);
          document.getElementById('s-tps1m').textContent = fmt(p.tps1m);
          document.getElementById('s-tps5m').textContent = fmt(p.tps5m);
          document.getElementById('s-msptAvg').textContent = fmt(p.msptAvg) + 'ms';
          document.getElementById('s-msptP95').textContent = fmt(p.msptP95) + 'ms';
          const heapPct = p.heapMaxBytes ? (p.heapUsedBytes/p.heapMaxBytes*100) : 0;
          document.getElementById('s-heap').textContent = mb(p.heapUsedBytes)+' / '+mb(p.heapMaxBytes);
          document.getElementById('s-gc').textContent = (p.gcPauseMsLastSec||0)+'ms';
          document.getElementById('s-cpu').textContent = fmt((p.processCpuLoad||0)*100,0)+'%';
          document.getElementById('s-threads').textContent = p.liveThreadCount||'--';
          // health 0-20
          const tpsDef = Math.max(0,(20-(p.tps1m||20))/20);
          const msptLoad = Math.min(1,(p.msptAvg||0)/50);
          const heapPress = Math.min(1, heapPct/100);
          const gcPress = Math.min(1,(p.gcPauseMsLastSec||0)/200);
          const cpuLoad = Math.min(1,(p.processCpuLoad||0));
          const score = Math.round(20*(1-(tpsDef*0.4+msptLoad*0.25+heapPress*0.15+gcPress*0.1+cpuLoad*0.1)));
          const desc = score>=18?'Excellent':score>=14?'Good':score>=10?'Fair':score>=5?'Poor':'Critical';
          document.getElementById('s-health').textContent = score+'/20 '+desc;
          setBar('bar-heap', heapPct);
          setBar('bar-tps', tpsDef*100);
          setBar('bar-mspt', msptLoad*100);
          setBar('bar-gc', gcPress*100);
          setBar('bar-cpu', cpuLoad*100);
        }
        function mb(b){ return b ? Math.round(b/1048576)+'MB' : '--'; }
        function setBar(id,pct){ const e=document.getElementById(id); if(e) e.style.width=Math.max(0,Math.min(100,pct))+'%'; }
        function pushTps(p){
          tpsHistory.push(p.tps1m==null?20:p.tps1m);
          if(tpsHistory.length>180) tpsHistory.shift();
          drawTps();
        }
        function drawTps(){
          const c=document.getElementById('tpsChart'); if(!c) return;
          const w=c.width=c.clientWidth, h=c.height;
          const g=c.getContext('2d'); g.clearRect(0,0,w,h);
          g.strokeStyle='#2c313b';
          for(let y=0;y<=20;y+=5){ const yy=h-(y/20)*h; g.beginPath();g.moveTo(0,yy);g.lineTo(w,yy);g.stroke(); }
          g.strokeStyle='#c8a02a'; g.lineWidth=2; g.beginPath();
          tpsHistory.forEach((v,i)=>{ const x=(i/Math.max(1,tpsHistory.length-1))*w; const y=h-(Math.min(20,v)/20)*h;
            i?g.lineTo(x,y):g.moveTo(x,y); });
          g.stroke();
        }

        // ---- CONSOLE ----
        let dropped=0;
        function appendConsole(p){
          const log=document.getElementById('consoleLog');
          const div=document.createElement('span');
          div.className='ll-'+(p.level||'INFO');
          const t=new Date(p.tsMs||Date.now()).toLocaleTimeString();
          div.textContent='['+t+'] ['+(p.level||'INFO')+'] '+(p.msg||'')+'\\n';
          log.appendChild(div);
          while(log.childNodes.length>800){ log.removeChild(log.firstChild); dropped++; }
          document.getElementById('dropped').textContent='Dropped: '+dropped;
          if(document.getElementById('autoscroll').checked) log.scrollTop=log.scrollHeight;
        }
        document.getElementById('cmdRun').onclick = runCmd;
        document.getElementById('cmdInput').addEventListener('keydown', e=>{ if(e.key==='Enter') runCmd(); });
        function runCmd(){
          const i=document.getElementById('cmdInput'); const cmd=i.value.trim(); if(!cmd) return;
          rpc('cmd_run',{command:cmd}).catch(e=>toast('Command failed: '+e.message));
          i.value='';
        }

        // ---- PLAYERS ----
        let lastPlayers=[];
        function renderPlayers(p){
          lastPlayers = p.players || [];
          document.getElementById('playerCount').textContent = lastPlayers.length+' players online';
          const tb=document.querySelector('#playersTable tbody'); tb.innerHTML='';
          lastPlayers.forEach(pl=>{
            const tr=document.createElement('tr');
            tr.innerHTML = td(pl.name)+td(short(pl.uuid))+td(pl.dimensionId)+
              td(fmt(pl.x,0))+td(fmt(pl.y,0))+td(fmt(pl.z,0))+td(pl.health)+td(pl.food)+
              td((pl.pingMs||0)+'ms')+td(pl.gameMode||'');
            const act=document.createElement('td');
            act.appendChild(btn('Kick', ()=>askKick(pl)));
            act.appendChild(btn('Ban', ()=>askBan(pl)));
            act.appendChild(btn('Op', ()=>rpc('cmd_op',{uuid:pl.uuid,on:true}).catch(err)));
            act.appendChild(btn('Deop', ()=>rpc('cmd_op',{uuid:pl.uuid,on:false}).catch(err)));
            act.appendChild(btn('WL+', ()=>rpc('cmd_whitelist',{uuid:pl.uuid,on:true}).catch(err)));
            act.appendChild(btn('Kill', ()=>rpc('cmd_run',{command:'kill '+pl.name}).catch(err)));
            tr.appendChild(act);
            tb.appendChild(tr);
          });
        }
        function askKick(pl){ const r=prompt('Kick '+pl.name+' - reason?','Disconnected.'); if(r!==null) rpc('cmd_kick',{uuid:pl.uuid,reason:r}).catch(err); }
        function askBan(pl){ const r=prompt('Ban '+pl.name+' - reason?','Banned.'); if(r!==null) rpc('cmd_ban',{uuid:pl.uuid,reason:r}).catch(err); }

        // ---- MOBS ----
        function renderMobs(p){
          const tb=document.querySelector('#mobsTable tbody'); tb.innerHTML='';
          let total=0; const byDim=p.byDim||{};
          Object.keys(byDim).forEach(dim=>{
            const mods=byDim[dim]||{};
            Object.keys(mods).forEach(mod=>{
              const m=mods[mod]; total+=m.total||0;
              const tr=document.createElement('tr');
              tr.innerHTML=td(dim)+td(mod)+td(m.total)+td(m.hostile)+td(m.passive)+td(m.items);
              tb.appendChild(tr);
            });
          });
          document.getElementById('mobTotal').textContent='Total entities: '+total;
        }

        // ---- MODS ----
        let knownMods=[], lastModsImpact=null;
        function renderMods(){
          const tb=document.querySelector('#modsTable tbody'); if(!tb) return; tb.innerHTML='';
          const agg={}; let maxN=1;
          if(lastModsImpact && lastModsImpact.byDim){
            Object.values(lastModsImpact.byDim).forEach(mods=>{
              Object.keys(mods).forEach(mod=>{
                const m=mods[mod]; if(!agg[mod]) agg[mod]={nanos:0,ent:0,te:0};
                agg[mod].nanos+=m.attributedNanos||0; agg[mod].ent+=m.entityCount||0; agg[mod].te+=m.tileEntityCount||0;
                maxN=Math.max(maxN,agg[mod].nanos);
              });
            });
          }
          knownMods.forEach(mod=>{ if(!agg[mod]) agg[mod]={nanos:0,ent:0,te:0}; });
          const rows=Object.keys(agg).sort((a,b)=>agg[b].nanos-agg[a].nanos);
          let active=0,idle=0;
          rows.forEach(mod=>{
            const a=agg[mod]; const pct=a.nanos/maxN*100; if(a.nanos>0)active++;else idle++;
            const tr=document.createElement('tr');
            tr.innerHTML=td(mod)+
              '<td><div class="impact-cell"><div class="impact-bar"><i style="width:'+pct.toFixed(0)+'%"></i></div>'+
              (a.nanos/1e6).toFixed(2)+'ms</div></td>'+td(a.ent)+td(a.te);
            tb.appendChild(tr);
          });
          document.getElementById('modsHeader').textContent='Mod impact (proportional, approx): '+active+' active, '+idle+' idle';
        }

        // ---- MAP ----
        let mapView={ox:0,oz:0,scale:0.25};
        const mapCanvas=document.getElementById('mapCanvas');
        function ensureDim(d){ if(!dims[d]) dims[d]={chunks:new Map(),players:[]}; return dims[d]; }
        function onChunks(p){
          const byDim=p.byDim||{};
          Object.keys(byDim).forEach(dim=>{
            const dd=ensureDim(dim); const flat=byDim[dim].chunks||[];
            for(let i=0;i+1<flat.length;i+=2){ const k=flat[i]+','+flat[i+1];
              if(!dd.chunks.has(k)){ dd.chunks.set(k,{cx:flat[i],cz:flat[i+1],tile:null});
                if(dim===currentDim) fetchTile(dim,flat[i],flat[i+1]); } }
          });
          updateMapLoading(); drawMap();
        }
        function fetchTile(dim,cx,cz){
          rpc('chunk_tile',{dim,cx,cz}).then(t=>{
            const dd=ensureDim(dim); const c=dd.chunks.get(cx+','+cz);
            if(c && t && t.surfaceArgb){ c.tile=t.surfaceArgb; drawMap(); }
          }).catch(()=>{});
        }
        function updateMapPlayers(p){ ensureDim(currentDim); (p.players||[]).forEach(pl=>{
          const dd=ensureDim(pl.dimensionId); dd.players=(dd.players||[]); }); ensureDim(currentDim).players=(p.players||[]).filter(x=>x.dimensionId===currentDim); drawMap(); }
        function updateMapLoading(){
          const dd=ensureDim(currentDim); let loaded=0; dd.chunks.forEach(c=>{ if(c.tile)loaded++; });
          document.getElementById('mapLoading').textContent='Chunks '+loaded+' / '+dd.chunks.size;
        }
        function drawMap(){
          const c=mapCanvas; const w=c.width=c.clientWidth, h=c.height;
          const g=c.getContext('2d'); g.fillStyle='#0e1014'; g.fillRect(0,0,w,h);
          const dd=ensureDim(currentDim); const s=mapView.scale;
          dd.chunks.forEach(ch=>{
            const px=w/2+(ch.cx*16-mapView.ox)*s, py=h/2+(ch.cz*16-mapView.oz)*s, sz=16*s;
            if(px<-sz||py<-sz||px>w||py>h) return;
            if(ch.tile){ // draw a downscaled representative color (avg of tile)
              g.fillStyle=avgColor(ch.tile); g.fillRect(px,py,Math.ceil(sz),Math.ceil(sz));
            } else { g.fillStyle='#1a1d24'; g.fillRect(px,py,Math.ceil(sz),Math.ceil(sz)); }
          });
          (dd.players||[]).forEach(pl=>{
            const px=w/2+(pl.x-mapView.ox)*s, py=h/2+(pl.z-mapView.oz)*s;
            g.fillStyle='#c8a02a'; g.beginPath(); g.arc(px,py,4,0,7); g.fill();
            g.fillStyle='#fff'; g.font='11px sans-serif'; g.fillText(pl.name,px+6,py+3);
          });
        }
        function avgColor(arr){ let r=0,gg=0,b=0,n=arr.length; for(let i=0;i<n;i++){ const v=arr[i]; r+=(v>>16)&255; gg+=(v>>8)&255; b+=v&255; } return 'rgb('+((r/n)|0)+','+((gg/n)|0)+','+((b/n)|0)+')'; }
        document.getElementById('mapDim').onchange=e=>{ currentDim=e.target.value; ensureDim(currentDim).chunks.forEach(ch=>{ if(!ch.tile) fetchTile(currentDim,ch.cx,ch.cz); }); drawMap(); updateMapLoading(); };
        document.getElementById('mapCenter').onclick=()=>{ mapView.ox=0; mapView.oz=0; drawMap(); };
        document.getElementById('mapFit').onclick=()=>{ const dd=ensureDim(currentDim); let minx=1e9,minz=1e9,maxx=-1e9,maxz=-1e9; dd.chunks.forEach(ch=>{ minx=Math.min(minx,ch.cx*16);minz=Math.min(minz,ch.cz*16);maxx=Math.max(maxx,ch.cx*16);maxz=Math.max(maxz,ch.cz*16); }); if(minx>maxx)return; mapView.ox=(minx+maxx)/2; mapView.oz=(minz+maxz)/2; mapView.scale=Math.min(mapCanvas.clientWidth/(maxx-minx+32),mapCanvas.height/(maxz-minz+32)); drawMap(); };
        (function(){ let drag=false,lx,ly;
          mapCanvas.addEventListener('mousedown',e=>{drag=true;lx=e.clientX;ly=e.clientY;});
          window.addEventListener('mouseup',()=>drag=false);
          mapCanvas.addEventListener('mousemove',e=>{ const r=mapCanvas.getBoundingClientRect();
            const wx=mapView.ox+(e.clientX-r.left-mapCanvas.width/2)/mapView.scale;
            const wz=mapView.oz+(e.clientY-r.top-mapCanvas.height/2)/mapView.scale;
            document.getElementById('mapCoords').textContent='('+Math.round(wx)+', '+Math.round(wz)+')';
            if(drag){ mapView.ox-=(e.clientX-lx)/mapView.scale; mapView.oz-=(e.clientY-ly)/mapView.scale; lx=e.clientX;ly=e.clientY; drawMap(); } });
          mapCanvas.addEventListener('wheel',e=>{ e.preventDefault(); mapView.scale*=e.deltaY<0?1.1:0.9; mapView.scale=Math.max(0.02,Math.min(8,mapView.scale)); drawMap(); },{passive:false});
        })();

        // ---- WORLD ----
        document.querySelectorAll('[data-weather]').forEach(b=>b.onclick=()=>rpc('cmd_weather',{kind:b.dataset.weather}).then(()=>toast('Weather: '+b.dataset.weather)).catch(err));
        document.querySelectorAll('[data-time]').forEach(b=>b.onclick=()=>rpc('cmd_time',{value:parseInt(b.dataset.time)}).then(()=>toast('Time set')).catch(err));
        document.getElementById('timeSet').onclick=()=>{ const v=parseInt(document.getElementById('timeValue').value); if(!isNaN(v)) rpc('cmd_time',{value:v}).then(()=>toast('Time set')).catch(err); };

        // ---- toolbar danger ----
        document.getElementById('btnStop').onclick=()=>{ if(confirm('Stop the server?')) rpc('srv_stop',{}).then(()=>toast('Stop requested')).catch(err); };
        document.getElementById('btnRestart').onclick=()=>{ if(confirm('Restart the server?')) rpc('srv_restart',{}).then(()=>toast('Restart requested')).catch(err); };

        // ---- ARCANUM ----
        document.getElementById('arcReload').onclick=loadArcanum;
        document.getElementById('arcApply').onclick=()=>{ let cfg; try{cfg=JSON.parse(document.getElementById('arcConfig').value);}catch(e){toast('Invalid JSON');return;} rpc('arcanum_setConfig',{config:cfg}).then(()=>toast('Applied')).catch(err); };
        document.getElementById('arcBroadcastBtn').onclick=()=>{ const m=document.getElementById('arcBroadcast').value.trim(); if(m) rpc('arcanum_announce',{message:m}).then(()=>{toast('Broadcast sent');document.getElementById('arcBroadcast').value='';}).catch(err); };
        function loadArcanum(){
          rpc('arcanum_getConfig',{}).then(cfg=>{ document.getElementById('arcConfig').value=JSON.stringify(cfg,null,2); }).catch(err);
          rpc('arcanum_getPlayers',{}).then(renderArcPlayers).catch(err);
        }
        function renderArcPlayers(data){
          const tb=document.querySelector('#arcPlayersTable tbody'); tb.innerHTML='';
          const entries = Array.isArray(data) ? data : Object.keys(data||{}).map(k=>Object.assign({uuid:k},data[k]));
          const online = new Set(lastPlayers.map(p=>p.uuid));
          entries.forEach(pl=>{
            const uuid=pl.uuid; const tr=document.createElement('tr');
            tr.innerHTML=td(pl.name||'Unknown')+td(short(uuid))+td(pl.rank||'')+td(pl.homesCount!=null?pl.homesCount:(pl.homes!=null?pl.homes:0))+td(online.has(uuid)?'yes':'no');
            const cell=document.createElement('td');
            const inp=document.createElement('input'); inp.placeholder='rank'; inp.style.width='90px';
            const b=btn('Set',()=>{ if(inp.value.trim()) rpc('arcanum_setPlayerRank',{uuid,rank:inp.value.trim()}).then(()=>{toast('Rank set');loadArcanum();}).catch(err); });
            cell.appendChild(inp); cell.appendChild(b); tr.appendChild(cell); tb.appendChild(tr);
          });
        }

        // ---- BACKUPS ----
        let bkJobTimer=null, bkLastJobUpdate=0;
        function bkBytes(n){ if(n==null||n<0) return '--'; const u=['B','KB','MB','GB','TB']; let i=0,v=n;
          while(v>=1024&&i<u.length-1){v/=1024;i++;} return v.toFixed(v>=10||i===0?0:1)+u[i]; }
        function bkDate(epoch){ if(!epoch) return '--'; return new Date(epoch*1000).toLocaleString(); }
        async function bkLoad(){
          try{
            const r=await fetch('/api/backups',{headers:{'Accept':'application/json'}});
            if(!r.ok){ toast('Backup list failed ('+r.status+')'); return; }
            const data=await r.json();
            const meta=data.meta||{};
            const drive=document.getElementById('bkDrive');
            drive.textContent = (meta.mounted?'Drive OK':'DRIVE NOT MOUNTED')+' - free '+bkBytes(meta.freeBytes)+
              ' - '+(meta.externalDir||'');
            drive.style.color = meta.mounted ? '#8b93a3' : '#d4504a';
            const list=(data.backups||[]);
            const bkTb=document.querySelector('#backupsTable tbody'); bkTb.innerHTML='';
            const rbTb=document.querySelector('#rollbackTable tbody'); rbTb.innerHTML='';
            const rbState=document.getElementById('rbState');
            const running=document.getElementById('stateBadge').classList.contains('state-running');
            rbState.textContent = running
              ? 'Server is RUNNING - stop it before a whole-world or whole-playerdata rollback.'
              : 'Server is stopped - rollback is safe to run.';
            rbState.style.color = running ? '#d4504a' : '#8b93a3';
            if(!list.length){
              bkTb.innerHTML='<tr><td colspan="6" class="meter">No backups yet.</td></tr>';
              rbTb.innerHTML='<tr><td colspan="6" class="meter">No backups available to roll back to.</td></tr>';
            }
            list.forEach(bk=>{
              const contents=[]; if(bk.hasWorld)contents.push('world');
                if(bk.hasPlayerData)contents.push('players'); if(bk.hasServer)contents.push('server');
              const head=td(bkDate(bk.createdEpoch))+td(bk.label||'')+td(bk.worldName||'')+
                td(contents.join(', ')+(bk.kind==='legacy'?' (legacy)':''))+td(bkBytes(bk.totalBytes));

              // --- Backups tab row: create-side view; only live per-player restore here ---
              const bkTr=document.createElement('tr'); bkTr.innerHTML=head;
              const bkCell=document.createElement('td');
              if(bk.kind==='structured' && bk.hasPlayerData){
                const lb=btn('Live Players',()=>bkOpenPlayers(bk.id,bk.label)); lb.className='bk-restore-live'; bkCell.appendChild(lb);
              } else {
                bkCell.innerHTML='<span class="meter">'+(bk.kind==='structured'?'world only':'manual restore')+'</span>';
              }
              bkTr.appendChild(bkCell); bkTb.appendChild(bkTr);

              // --- Rollback tab row: whole-world / whole-playerdata / both restore ---
              const rbTr=document.createElement('tr'); rbTr.innerHTML=head;
              const rbCell=document.createElement('td');
              if(bk.kind==='structured'){
                if(bk.hasWorld){ const wb=btn('World',()=>bkRollback(bk.id,'world')); wb.className='bk-restore-world'; rbCell.appendChild(wb); }
                if(bk.hasPlayerData){ const pb=btn('Players',()=>bkRollback(bk.id,'playerdata')); pb.className='bk-restore-player'; rbCell.appendChild(pb); }
                if(bk.hasWorld&&bk.hasPlayerData){ const bb=btn('Both',()=>bkRollback(bk.id,'both')); bb.className='bk-restore-both'; rbCell.appendChild(bb); }
              } else {
                rbCell.innerHTML='<span class="meter">manual restore</span>';
              }
              rbTr.appendChild(rbCell); rbTb.appendChild(rbTr);
            });
          }catch(e){ toast('Backup list error: '+e.message); }
        }
        async function bkRunNow(){
          const label=document.getElementById('bkLabel').value.trim();
          try{
            const r=await fetch('/api/backup/run',{method:'POST',headers:{'Content-Type':'application/json'},
              body:JSON.stringify({label})});
            const j=await r.json().catch(()=>({}));
            if(!r.ok||!j.started){ toast('Backup not started: '+(j.error||r.status)); }
            else { toast('Backup started'); document.getElementById('bkLabel').value=''; bkStartPolling(); }
          }catch(e){ toast('Backup error: '+e.message); }
        }
        async function bkRollback(id,scope){
          const label = scope==='world'?'WORLD':scope==='playerdata'?'PLAYER DATA':'WORLD AND PLAYER DATA';
          if(!confirm('Restore '+label+' from backup '+id+'?\\n\\nThe server MUST be stopped. A safety snapshot of the current state is taken first. This overwrites the live '+label.toLowerCase()+'.')) return;
          try{
            const r=await fetch('/api/rollback',{method:'POST',headers:{'Content-Type':'application/json'},
              body:JSON.stringify({id,scope})});
            const j=await r.json().catch(()=>({}));
            if(!r.ok||!j.started){ toast('Rollback rejected: '+(j.error||r.status)); }
            else { toast('Rollback started'); bkStartPolling(); }
          }catch(e){ toast('Rollback error: '+e.message); }
        }
        // ---- live per-player restore ----
        let bkPmId=null;
        async function bkOpenPlayers(id,label){
          bkPmId=id;
          document.getElementById('bkPmTitle').textContent='Restore a player from '+id+(label?(' ('+label+')'):'');
          const note=document.getElementById('bkPmNote');
          const running=document.getElementById('stateBadge').classList.contains('state-running');
          note.textContent = running ? '' : 'Server is not running. Start it to restore live player state.';
          note.style.color = running ? '' : '#d4504a';
          const tb=document.querySelector('#bkPlayersTable tbody'); tb.innerHTML='<tr><td colspan="3" class="meter">Loading...</td></tr>';
          document.getElementById('bkPlayerModal').classList.remove('hidden');
          try{
            const r=await fetch('/api/backup/players?id='+encodeURIComponent(id),{headers:{'Accept':'application/json'}});
            const j=await r.json().catch(()=>({}));
            if(!r.ok||!j.ok){ tb.innerHTML='<tr><td colspan="3" class="meter">'+(j.error||('error '+r.status))+'</td></tr>'; return; }
            const players=j.players||[];
            if(!players.length){ tb.innerHTML='<tr><td colspan="3" class="meter">No player data in this backup.</td></tr>'; return; }
            tb.innerHTML='';
            players.forEach(p=>{
              const tr=document.createElement('tr');
              tr.innerHTML=td(p.name||'(unknown)')+td(short(p.uuid));
              const cell=document.createElement('td');
              const rb=btn('Restore Live',()=>bkRestorePlayer(p.uuid,p.name)); rb.className='bk-restore-live';
              cell.appendChild(rb); tr.appendChild(cell); tb.appendChild(tr);
            });
          }catch(e){ tb.innerHTML='<tr><td colspan="3" class="meter">'+e.message+'</td></tr>'; }
        }
        function bkClosePlayers(){ document.getElementById('bkPlayerModal').classList.add('hidden'); bkPmId=null; }
        async function bkRestorePlayer(uuid,name){
          if(!bkPmId) return;
          const who=name||uuid;
          if(!confirm('Restore '+who+'\\'s full saved state from backup '+bkPmId+'?\\n\\nThis overwrites their CURRENT inventory, XP, health, and position with the backup. If they are online it applies live. A safety snapshot is taken first.')) return;
          try{
            const r=await fetch('/api/player/restore',{method:'POST',headers:{'Content-Type':'application/json'},
              body:JSON.stringify({id:bkPmId,uuid})});
            const j=await r.json().catch(()=>({}));
            if(!r.ok||!j.ok){ toast('Restore failed: '+(j.error||r.status)); return; }
            const mode=(j.result&&j.result.mode)||'';
            toast('Restored '+who+(mode?(' ('+mode+')'):'')); 
          }catch(e){ toast('Restore error: '+e.message); }
        }
        function bkRenderJob(j){
          // The same backup/rollback job feed drives the status panel on BOTH the
          // Backups tab (#bkJob) and the Rollback tab (#rbJob) so a job started
          // from either tab is visible no matter which one the operator is viewing.
          ['bkJob','rbJob'].forEach(id=>{
            const el=document.getElementById(id);
            if(!el) return;
            if(!j || j.type==='none'){ el.classList.add('hidden'); return; }
            el.classList.remove('hidden');
            el.classList.remove('running','ok','fail');
            if(j.running) el.classList.add('running');
            else if(j.ok===true) el.classList.add('ok');
            else if(j.ok===false) el.classList.add('fail');
            const logHtml = j.log ? '<pre>'+j.log.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))+'</pre>' : '';
            el.innerHTML='<strong>'+(j.message||'')+'</strong>'+logHtml;
          });
        }
        async function bkPollJob(){
          try{
            const r=await fetch('/api/job',{headers:{'Accept':'application/json'}});
            if(!r.ok) return;
            const j=await r.json();
            bkRenderJob(j);
            if(!j.running){
              clearInterval(bkJobTimer); bkJobTimer=null;
              if(j.ok===true){ toast(j.message); bkLoad(); }
              else if(j.ok===false){ toast(j.message); }
            }
          }catch(e){}
        }
        function bkStartPolling(){
          bkPollJob();
          if(!bkJobTimer) bkJobTimer=setInterval(bkPollJob,2000);
        }
        document.getElementById('bkRun').onclick=bkRunNow;
        document.getElementById('bkRefresh').onclick=bkLoad;
        document.getElementById('rbRefresh').onclick=bkLoad;
        document.getElementById('bkPmClose').onclick=bkClosePlayers;
        document.getElementById('bkPlayerModal').addEventListener('click',e=>{
          if(e.target.id==='bkPlayerModal') bkClosePlayers();
        });

        // ---- tabs ----
        document.querySelectorAll('#tabs button').forEach(b=>b.onclick=()=>{
          document.querySelectorAll('#tabs button').forEach(x=>x.classList.remove('active'));
          document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));
          b.classList.add('active');
          document.getElementById('tab-'+b.dataset.tab).classList.add('active');
          if(b.dataset.tab==='map') drawMap();
          if(b.dataset.tab==='stats') drawTps();
          if(b.dataset.tab==='arcanum') loadArcanum();
          if(b.dataset.tab==='backups'){ bkLoad(); bkPollJob(); }
          if(b.dataset.tab==='rollback'){ bkLoad(); bkPollJob(); }
        });

        // ---- helpers ----
        function td(v){ return '<td>'+(v==null?'':String(v))+'</td>'; }
        function short(u){ return u?u.substring(0,8):''; }
        function btn(label,fn){ const b=document.createElement('button'); b.textContent=label; b.onclick=fn; return b; }
        function err(e){ toast('Error: '+(e&&e.message?e.message:e)); }
        function toast(msg){ const a=document.getElementById('alertBanner'); a.textContent=msg; a.classList.remove('hidden');
          a.onclick=()=>a.classList.add('hidden'); clearTimeout(toast._t); toast._t=setTimeout(()=>a.classList.add('hidden'),5000); }

        connect();
        """;
}
