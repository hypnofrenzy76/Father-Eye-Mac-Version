// Players pane: live-updated table with kick / ban / op / whitelist /
// teleport buttons. Mirrors PlayersPane in the desktop panel.

window.playersView = (() => {
    const tbody = document.querySelector('#playersTable tbody');
    const countLbl = document.getElementById('playerCount');

    function render(payload) {
        const list = (payload && payload.players) || [];
        countLbl.textContent = list.length === 0
            ? 'No players online.'
            : `${list.length} player${list.length === 1 ? '' : 's'} online.`;
        tbody.innerHTML = '';
        for (const p of list) {
            const tr = document.createElement('tr');
            const cells = [
                p.name || '?', p.dim || '', fmt(p.x), fmt(p.y), fmt(p.z),
                p.pingMs != null ? `${p.pingMs} ms` : ''
            ];
            for (const c of cells) {
                const td = document.createElement('td');
                td.textContent = c;
                tr.appendChild(td);
            }
            const actions = document.createElement('td');
            actions.className = 'row-actions';
            actions.appendChild(btn('Kick', () => kick(p)));
            actions.appendChild(btn('Ban',  () => ban(p), 'danger'));
            actions.appendChild(btn('Op',     () => opPlayer(p, true)));
            actions.appendChild(btn('Deop',   () => opPlayer(p, false)));
            actions.appendChild(btn('+WL',    () => whitelist(p, true)));
            actions.appendChild(btn('-WL',    () => whitelist(p, false)));
            actions.appendChild(btn('Teleport…', () => teleport(p)));
            tr.appendChild(actions);
            tbody.appendChild(tr);
        }
    }

    function fmt(v) {
        if (typeof v !== 'number' || !isFinite(v)) return '';
        return v.toFixed(1);
    }

    function btn(label, fn, kind) {
        const b = document.createElement('button');
        b.className = 'btn small' + (kind ? ' ' + kind : '');
        b.textContent = label;
        b.addEventListener('click', () => {
            b.disabled = true;
            Promise.resolve(fn())
                .catch(e => alert(`${label} failed: ${e.message}`))
                .finally(() => { b.disabled = false; });
        });
        return b;
    }

    async function kick(p) {
        const reason = prompt(`Kick ${p.name}? Reason (optional):`, '') || '';
        await api.kick(p.name, reason);
    }
    async function ban(p) {
        const reason = prompt(`BAN ${p.name}? Reason (optional):`, '') || '';
        await api.ban(p.name, reason);
    }
    async function opPlayer(p, on) { await api.op(p.name, on); }
    async function whitelist(p, add) { await api.whitelist(p.name, add); }
    async function teleport(p) {
        const xs = prompt(`Teleport ${p.name} to X:`, p.x ? Math.round(p.x) : '0');
        if (xs == null) return;
        const ys = prompt('Y:', p.y ? Math.round(p.y) : '64');
        if (ys == null) return;
        const zs = prompt('Z:', p.z ? Math.round(p.z) : '0');
        if (zs == null) return;
        const dim = prompt('Dimension (blank = same):', p.dim || '') || '';
        await api.teleport(p.name, Number(xs), Number(ys), Number(zs), dim || undefined);
    }

    function init() {
        wsBus.on('players_topic', render);
        // Seed from REST in case WebSocket is mid-reconnect.
        api.get('/api/players').then(render).catch(() => {});
    }

    return { init };
})();
