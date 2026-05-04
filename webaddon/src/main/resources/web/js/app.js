// Main shell — tab routing, top-bar state badges, server start/stop
// buttons, logout. Wires up every view module.

(() => {
    const tabs = document.querySelectorAll('.tab');
    const panes = document.querySelectorAll('.pane');
    const btnStart = document.getElementById('btnStart');
    const btnStop = document.getElementById('btnStop');
    const btnRestart = document.getElementById('btnRestart');
    const serverState = document.getElementById('serverState');
    const heartbeat = document.getElementById('heartbeat');
    const uptime = document.getElementById('uptime');
    const status = document.getElementById('status');
    const userTag = document.getElementById('userTag');
    const logout = document.getElementById('logoutBtn');
    const connState = document.getElementById('connState');

    let lastTpsMs = 0;
    let serverStartedMs = 0;

    function selectTab(name) {
        for (const t of tabs) t.classList.toggle('active', t.dataset.tab === name);
        for (const p of panes) p.classList.toggle('active', p.dataset.pane === name);
        document.dispatchEvent(new CustomEvent('paneActivated', { detail: name }));
    }
    tabs.forEach(t => t.addEventListener('click', () => selectTab(t.dataset.tab)));

    function setStateBadge(stateName, connected) {
        let kind = '';
        if (stateName === 'RUNNING')      kind = 'ok';
        else if (stateName === 'STARTING' || stateName === 'STOPPING') kind = 'warn';
        else if (stateName === 'CRASHED') kind = 'crit';
        else                              kind = 'muted';
        serverState.className = 'badge ' + kind;
        serverState.textContent = `Server: ${stateName}` + (connected ? ' (bridge)' : '');
        btnStart.disabled = (stateName === 'RUNNING' || stateName === 'STARTING');
        btnStop.disabled = (stateName === 'STOPPED' || stateName === 'CRASHED');
        btnRestart.disabled = (stateName === 'STOPPED' || stateName === 'CRASHED');
    }

    async function refreshState() {
        try {
            const s = await api.serverState();
            setStateBadge(s.state || 'STOPPED', !!s.bridgeConnected);
        } catch (e) {
            setStateBadge('STOPPED', false);
        }
    }

    btnStart.addEventListener('click', async () => {
        btnStart.disabled = true;
        try { await api.startServer(); }
        catch (e) { alert('Start failed: ' + e.message); }
        refreshState();
    });
    btnStop.addEventListener('click', async () => {
        if (!confirm('Stop the server?')) return;
        btnStop.disabled = true;
        try { await api.stopServer(); }
        catch (e) { alert('Stop failed: ' + e.message); }
        refreshState();
    });
    btnRestart.addEventListener('click', async () => {
        if (!confirm('Restart the server?')) return;
        btnRestart.disabled = true;
        try { await api.restartServer(); }
        catch (e) { alert('Restart failed: ' + e.message); }
        refreshState();
    });

    logout.addEventListener('click', async () => {
        try { await api.logout(); }
        finally { window.location.href = '/static/login.html'; }
    });

    // Heartbeat / uptime tickers
    function tick() {
        if (lastTpsMs > 0) {
            const ageS = Math.floor((Date.now() - lastTpsMs) / 1000);
            heartbeat.textContent = 'Heartbeat: ' + ageS + 's';
            heartbeat.className = 'badge ' + (ageS < 5 ? 'ok' : ageS < 30 ? 'warn' : 'crit');
        } else {
            heartbeat.textContent = 'Heartbeat: --';
            heartbeat.className = 'badge muted';
        }
        if (serverStartedMs > 0) {
            const secs = Math.max(0, Math.floor((Date.now() - serverStartedMs) / 1000));
            const h = Math.floor(secs / 3600);
            const m = Math.floor((secs % 3600) / 60);
            const s = secs % 60;
            let label = 'Uptime: ';
            if (h > 0) label += h + 'h ';
            if (h > 0 || m > 0) label += m + 'm ';
            label += s + 's';
            uptime.textContent = label;
            uptime.className = 'badge';
        } else {
            uptime.textContent = 'Uptime: --';
            uptime.className = 'badge muted';
        }
    }
    setInterval(tick, 1000);

    // Topic taps
    wsBus.on('tps_topic', () => { lastTpsMs = Date.now(); });
    wsBus.onState((text, kind) => {
        connState.textContent = 'Web socket: ' + text;
        connState.className = 'badge ' + (kind || 'muted');
    });

    // Status bar updates: surface anything the panel pushes via /api/server/state polling.
    setInterval(refreshState, 5000);

    // Boot
    api.me().then(info => {
        if (!info || !info.authenticated) {
            window.location.href = '/static/login.html';
            return;
        }
        userTag.textContent = info.username || '';
        consoleView.init();
        playersView.init();
        mobsView.init();
        modsView.init();
        statsView.init();
        mapView.init();
        configView.init();
        refreshState();
        configView.load();
        status.textContent = 'Ready.';
    }).catch(() => {
        window.location.href = '/static/login.html';
    });
})();
