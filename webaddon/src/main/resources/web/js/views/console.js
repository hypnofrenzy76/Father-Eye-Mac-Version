// Console pane: live log stream + command input. Mirrors the desktop
// panel's ConsolePane (Consolas monospace, level-coloured lines, panel
// synthetic lines tinted green).

window.consoleView = (() => {
    const MAX_LINES = 2000;
    const root = document.getElementById('consoleLog');
    const form = document.getElementById('consoleForm');
    const input = document.getElementById('consoleInput');

    function append(payload) {
        const ts = payload.tsMs ? new Date(payload.tsMs) : new Date();
        const time = ts.toTimeString().slice(0, 8);
        const level = (payload.level || 'INFO').toUpperCase();
        const logger = payload.logger || 'server';
        const msg = payload.msg || '';
        const div = document.createElement('div');
        div.className = 'ln';
        if (logger === 'FatherEye') div.classList.add('panel');
        else if (level === 'WARN') div.classList.add('warn');
        else if (level === 'ERROR' || level === 'FATAL') div.classList.add('error');
        const tsSpan = document.createElement('span');
        tsSpan.className = 'ts';
        tsSpan.textContent = time;
        const text = document.createTextNode(`${level.padEnd(5)} [${logger}] ${msg}`);
        div.appendChild(tsSpan);
        div.appendChild(text);
        const wasNearBottom = root.scrollHeight - root.scrollTop - root.clientHeight < 80;
        root.appendChild(div);
        while (root.childElementCount > MAX_LINES) {
            root.removeChild(root.firstChild);
        }
        if (wasNearBottom) root.scrollTop = root.scrollHeight;
    }

    function init() {
        wsBus.on('console_log', append);
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const cmd = input.value.trim();
            if (!cmd) return;
            input.value = '';
            try {
                await api.sendCommand(cmd);
            } catch (err) {
                append({ tsMs: Date.now(), level: 'ERROR', logger: 'FatherEye',
                        msg: 'Command failed: ' + err.message });
            }
        });
    }

    return { init };
})();
