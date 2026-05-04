// Mobs pane: type/dim/count table + clear-area dialog. Mirrors MobsPane.

window.mobsView = (() => {
    const tbody = document.querySelector('#mobsTable tbody');
    const totalLbl = document.getElementById('mobCount');
    const clearBtn = document.getElementById('btnClearArea');

    function render(payload) {
        const groups = (payload && payload.groups) || (payload && payload.mobs) || [];
        let total = 0;
        tbody.innerHTML = '';
        for (const g of groups) {
            const cnt = g.count || g.size || 0;
            total += cnt;
            const tr = document.createElement('tr');
            for (const c of [g.type || '?', g.dim || '', cnt, g.hostile ? 'yes' : '']) {
                const td = document.createElement('td');
                td.textContent = c;
                tr.appendChild(td);
            }
            const actions = document.createElement('td');
            if (g.entityIds && g.entityIds.length) {
                const b = document.createElement('button');
                b.className = 'btn small danger';
                b.textContent = `Kill all (${g.entityIds.length})`;
                b.addEventListener('click', async () => {
                    if (!confirm(`Kill ${g.entityIds.length} ${g.type}?`)) return;
                    for (const id of g.entityIds) {
                        try { await api.killEntity(id); }
                        catch (e) { /* keep going */ }
                    }
                });
                actions.appendChild(b);
            }
            tr.appendChild(actions);
            tbody.appendChild(tr);
        }
        totalLbl.textContent = total === 0 ? 'No mobs reported.' : `${total} entities reported.`;
    }

    async function clearArea() {
        const dim = prompt('Dimension:', 'minecraft:overworld');
        if (dim == null) return;
        const x1 = Number(prompt('Corner 1 X:', '-100'));
        const z1 = Number(prompt('Corner 1 Z:', '-100'));
        const x2 = Number(prompt('Corner 2 X:', '100'));
        const z2 = Number(prompt('Corner 2 Z:', '100'));
        if ([x1,z1,x2,z2].some(v => !isFinite(v))) {
            alert('Coordinates must be numeric.');
            return;
        }
        const hostileOnly = confirm('Hostile only? Cancel = ALL mobs in area.');
        try {
            await api.clearMobs(dim, x1, z1, x2, z2, hostileOnly);
        } catch (e) {
            alert('Clear failed: ' + e.message);
        }
    }

    function init() {
        wsBus.on('mobs_topic', render);
        clearBtn.addEventListener('click', clearArea);
    }

    return { init };
})();
