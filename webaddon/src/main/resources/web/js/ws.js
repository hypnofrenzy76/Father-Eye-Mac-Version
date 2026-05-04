// WebSocket topic stream wrapper. Auto-reconnects with exponential
// backoff up to 30 s between attempts. Fan-out via a simple pub/sub
// keyed on topic name; views subscribe via wsBus.on(topic, handler).

window.wsBus = (() => {
    const handlers = new Map(); // topic -> Set<handler>
    const stateListeners = new Set();
    let socket = null;
    let backoff = 1000;
    let alive = false;

    function emitState(text, kind) {
        for (const h of stateListeners) {
            try { h(text, kind); } catch (e) { /* ignore */ }
        }
    }

    function dispatch(envelope) {
        const set = handlers.get(envelope.topic);
        if (!set) return;
        for (const h of set) {
            try { h(envelope.payload, envelope); }
            catch (e) { console.warn('handler error', e); }
        }
    }

    function open() {
        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const url = `${proto}//${location.host}/ws`;
        try { socket = new WebSocket(url); }
        catch (e) {
            emitState('socket error: ' + e.message, 'crit');
            scheduleReconnect();
            return;
        }
        socket.addEventListener('open', () => {
            backoff = 1000;
            alive = true;
            emitState('connected', 'ok');
        });
        socket.addEventListener('close', (ev) => {
            alive = false;
            emitState('reconnecting…', 'warn');
            if (ev.code === 1008 /* policy violation, e.g. unauth */) {
                window.location.href = '/static/login.html';
                return;
            }
            scheduleReconnect();
        });
        socket.addEventListener('error', () => {
            // 'close' will follow with the actual reason; nothing to do here.
        });
        socket.addEventListener('message', (ev) => {
            let m;
            try { m = JSON.parse(ev.data); }
            catch (e) { return; }
            if (m && (m.type === 'snapshot' || m.type === 'event')) {
                dispatch(m);
            }
        });
    }

    function scheduleReconnect() {
        backoff = Math.min(backoff * 2, 30000);
        setTimeout(open, backoff);
    }

    open();

    return {
        on(topic, handler) {
            let set = handlers.get(topic);
            if (!set) { set = new Set(); handlers.set(topic, set); }
            set.add(handler);
            return () => set.delete(handler);
        },
        onState(handler) {
            stateListeners.add(handler);
            return () => stateListeners.delete(handler);
        },
        send(obj) {
            if (alive) socket.send(JSON.stringify(obj));
        },
        isAlive() { return alive; }
    };
})();
