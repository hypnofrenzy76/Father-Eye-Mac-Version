// Mods pane: tick-share table with impact-coloured bars. Mirrors ModsPane.

window.modsView = (() => {
    const tbody = document.querySelector('#modsTable tbody');

    function impactColor(pct) {
        if (pct < 1.0)  return '#7ecf6f';
        if (pct < 5.0)  return '#a8d36a';
        if (pct < 15.0) return '#e0c060';
        if (pct < 35.0) return '#e0a060';
        return '#e07060';
    }

    function render(payload) {
        const list = (payload && (payload.mods || payload.entries)) || [];
        list.sort((a, b) => (b.tickShare || 0) - (a.tickShare || 0));
        tbody.innerHTML = '';
        for (const m of list) {
            const pct = (m.tickShare != null) ? m.tickShare * 100 :
                        (m.percent != null) ? m.percent : 0;
            const tr = document.createElement('tr');

            const tdName = document.createElement('td');
            tdName.textContent = m.id || m.name || '?';
            tr.appendChild(tdName);

            const tdBar = document.createElement('td');
            const bar = document.createElement('div');
            bar.style.height = '10px';
            bar.style.background = '#1a1a1a';
            bar.style.border = '1px solid #2a2a2a';
            bar.style.borderRadius = '2px';
            const fill = document.createElement('div');
            fill.style.height = '100%';
            fill.style.width = Math.min(100, pct).toFixed(1) + '%';
            fill.style.background = impactColor(pct);
            fill.style.borderRadius = '2px';
            bar.appendChild(fill);
            tdBar.appendChild(bar);
            tr.appendChild(tdBar);

            const tdPct = document.createElement('td');
            tdPct.style.color = impactColor(pct);
            tdPct.style.fontFamily = 'Menlo, Consolas, monospace';
            tdPct.textContent = pct.toFixed(2) + '%';
            tr.appendChild(tdPct);

            tbody.appendChild(tr);
        }
        if (list.length === 0) {
            const tr = document.createElement('tr');
            const td = document.createElement('td');
            td.colSpan = 3;
            td.className = 'muted';
            td.textContent = 'No mod-impact data yet.';
            tr.appendChild(td);
            tbody.appendChild(tr);
        }
    }

    function init() {
        wsBus.on('mods_impact_topic', render);
    }

    return { init };
})();
