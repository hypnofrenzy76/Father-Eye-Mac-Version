// Tiny fetch wrapper. Every call goes same-origin with credentials so
// the session cookie flows automatically. 401 => redirect to login.

window.api = (() => {
    async function call(method, path, body) {
        const opts = {
            method,
            credentials: 'same-origin',
            headers: {}
        };
        if (body !== undefined) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(body);
        }
        const resp = await fetch(path, opts);
        if (resp.status === 401) {
            window.location.href = '/static/login.html';
            throw new Error('unauthenticated');
        }
        const text = await resp.text();
        let json = null;
        try { json = text ? JSON.parse(text) : null; } catch (e) { /* not JSON */ }
        if (!resp.ok) {
            const msg = (json && json.error) ? json.error : `HTTP ${resp.status}`;
            const err = new Error(msg);
            err.status = resp.status;
            err.body = json;
            throw err;
        }
        return json;
    }

    return {
        get:    (path)        => call('GET',    path),
        post:   (path, body)  => call('POST',   path, body || {}),
        put:    (path, body)  => call('PUT',    path, body || {}),
        del:    (path)        => call('DELETE', path),

        // Convenience surface
        me:           ()                  => call('GET',  '/api/auth/me'),
        logout:       ()                  => call('POST', '/api/auth/logout'),

        serverState:  ()                  => call('GET',  '/api/server/state'),
        startServer:  ()                  => call('POST', '/api/server/start'),
        stopServer:   ()                  => call('POST', '/api/server/stop'),
        restartServer:()                  => call('POST', '/api/server/restart'),
        sendCommand:  (cmd)               => call('POST', '/api/server/command', { command: cmd }),

        kick:         (name, reason)      => call('POST', `/api/players/${encodeURIComponent(name)}/kick`,      { reason: reason || '' }),
        ban:          (name, reason)      => call('POST', `/api/players/${encodeURIComponent(name)}/ban`,       { reason: reason || '' }),
        op:           (name, on)          => call('POST', `/api/players/${encodeURIComponent(name)}/op`,        { op: !!on }),
        whitelist:    (name, add)         => call('POST', `/api/players/${encodeURIComponent(name)}/whitelist`, { add: !!add }),
        teleport:     (name, x, y, z, dim)=> call('POST', `/api/players/${encodeURIComponent(name)}/teleport`,  { x, y, z, dim }),

        weather:      (w, dur)            => call('POST', '/api/world/weather', dur ? { weather: w, durationTicks: dur } : { weather: w }),
        time:         (t)                 => call('POST', '/api/world/time', { time: t }),

        clearMobs:    (dim, x1, z1, x2, z2, hostileOnly) =>
                                             call('POST', '/api/mobs/clear', { dim, x1, z1, x2, z2, hostileOnly: !!hostileOnly }),
        killEntity:   (id)                => call('POST', `/api/entities/${encodeURIComponent(id)}/kill`),

        backupNow:    ()                  => call('POST', '/api/backup'),

        getConfig:    ()                  => call('GET',  '/api/config'),
        putConfig:    (cfg)               => call('PUT',  '/api/config', cfg),
        updateCreds:  (username, current, next) =>
                                             call('PUT',  '/api/config/credentials',
                                                  { username, currentPassword: current, newPassword: next }),
    };
})();
