// Stats pane: KPI cards + TPS canvas chart + health score. Mirrors
// StatsPane.

window.statsView = (() => {
    const SAMPLE_LIMIT = 300;
    const samples = []; // [{tsMs, tps, mspt, heapPct}]

    const kTps = document.getElementById('kpiTps');
    const kMspt = document.getElementById('kpiMspt');
    const kHeap = document.getElementById('kpiHeap');
    const kPlayers = document.getElementById('kpiPlayers');
    const canvas = document.getElementById('tpsChart');
    const healthScore = document.getElementById('healthScore');
    const healthDesc = document.getElementById('healthDescriptor');

    function tpsColor(t) {
        if (t >= 19.5) return '#7ecf6f';
        if (t >= 18.0) return '#a8d36a';
        if (t >= 15.0) return '#e0c060';
        if (t >= 10.0) return '#e0a060';
        return '#e07060';
    }

    function setKpi(el, value, fmt, color) {
        el.textContent = (value == null || isNaN(value)) ? '--' : fmt(value);
        if (color) el.style.color = color;
    }

    function renderKpis() {
        const last = samples[samples.length - 1];
        if (!last) return;
        setKpi(kTps,  last.tps,    v => v.toFixed(2), tpsColor(last.tps));
        setKpi(kMspt, last.mspt,   v => v.toFixed(1) + ' ms');
        setKpi(kHeap, last.heapPct,v => v.toFixed(0) + '%');
        setKpi(kPlayers, last.players, v => Math.round(v).toString());
    }

    function score() {
        // Average over last 30 samples to smooth.
        if (samples.length === 0) return null;
        const tail = samples.slice(-30);
        const avgTps = tail.reduce((s, r) => s + r.tps, 0) / tail.length;
        // Map TPS 0..20 -> 0..20 health score directly. (Mirrors the
        // panel's StatsPane health rating, simplified.)
        return Math.max(0, Math.min(20, avgTps));
    }

    function renderHealth() {
        const s = score();
        if (s == null) {
            healthScore.textContent = '--';
            healthDesc.textContent = 'awaiting data';
            return;
        }
        let color = '#cfcfcf', desc = '';
        if (s >= 19.0)      { desc = 'Excellent'; color = '#7ecf6f'; }
        else if (s >= 16.0) { desc = 'Good';      color = '#a8d36a'; }
        else if (s >= 12.0) { desc = 'Fair';      color = '#e0c060'; }
        else if (s >= 8.0)  { desc = 'Poor';      color = '#e0a060'; }
        else                { desc = 'Critical';  color = '#e07060'; }
        healthScore.textContent = s.toFixed(1);
        healthScore.style.color = color;
        healthDesc.textContent = desc;
        healthDesc.style.color = color;
    }

    function fitCanvas() {
        const dpr = window.devicePixelRatio || 1;
        const r = canvas.getBoundingClientRect();
        canvas.width = Math.max(1, Math.floor(r.width * dpr));
        canvas.height = Math.max(1, Math.floor(r.height * dpr));
        const ctx = canvas.getContext('2d');
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    function drawChart() {
        if (samples.length === 0) return;
        fitCanvas();
        const ctx = canvas.getContext('2d');
        const w = canvas.clientWidth;
        const h = canvas.clientHeight;
        ctx.clearRect(0, 0, w, h);

        // Grid
        ctx.strokeStyle = '#3a3a3a';
        ctx.lineWidth = 1;
        ctx.font = '11px Menlo, Consolas, monospace';
        ctx.fillStyle = '#888';
        const padL = 36, padR = 8, padT = 8, padB = 18;
        const innerW = w - padL - padR;
        const innerH = h - padT - padB;
        const yMax = 22;
        for (const tps of [5, 10, 15, 20]) {
            const y = padT + innerH * (1 - tps / yMax);
            ctx.beginPath();
            ctx.moveTo(padL, y);
            ctx.lineTo(w - padR, y);
            ctx.stroke();
            ctx.fillText(String(tps), 6, y + 4);
        }

        // Line
        const N = Math.min(samples.length, SAMPLE_LIMIT);
        const start = samples.length - N;
        ctx.beginPath();
        for (let i = 0; i < N; i++) {
            const s = samples[start + i];
            const x = padL + innerW * (i / Math.max(1, N - 1));
            const y = padT + innerH * (1 - Math.max(0, Math.min(yMax, s.tps)) / yMax);
            if (i === 0) ctx.moveTo(x, y);
            else ctx.lineTo(x, y);
        }
        const last = samples[samples.length - 1];
        ctx.strokeStyle = tpsColor(last.tps);
        ctx.lineWidth = 2;
        ctx.stroke();

        ctx.fillStyle = '#888';
        ctx.fillText('TPS (last ' + N + ' samples)', padL, h - 4);
    }

    function onTps(payload) {
        if (!payload) return;
        samples.push({
            tsMs:    payload.tsMs || Date.now(),
            tps:     payload.tps != null ? payload.tps : payload.value || 0,
            mspt:    payload.mspt || 0,
            heapPct: (payload.heapPct != null) ? payload.heapPct
                    : (payload.heap && payload.heap.maxBytes
                       ? (payload.heap.usedBytes / payload.heap.maxBytes) * 100 : null),
            players: payload.playerCount || 0
        });
        while (samples.length > SAMPLE_LIMIT) samples.shift();
        renderKpis();
        renderHealth();
        if (document.querySelector('[data-pane=stats]').classList.contains('active')) {
            drawChart();
        }
    }

    function onPlayers(payload) {
        const n = payload && payload.players ? payload.players.length : 0;
        if (samples.length) samples[samples.length - 1].players = n;
        kPlayers.textContent = String(n);
    }

    function init() {
        wsBus.on('tps_topic', onTps);
        wsBus.on('players_topic', onPlayers);
        window.addEventListener('resize', () => drawChart());
        document.addEventListener('paneActivated', (e) => {
            if (e.detail === 'stats') drawChart();
        });
    }

    return { init };
})();
