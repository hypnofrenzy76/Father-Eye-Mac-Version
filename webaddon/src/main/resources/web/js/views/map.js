// Map pane: chunk-tile renderer. Fetches /api/map/tile per visible
// chunk and paints onto a canvas. Pan with mouse drag; zoom with
// scroll wheel. Mirrors the JavaFX MapPane visually (dark
// background, vanilla-map palette via the bridge).

window.mapView = (() => {
    const canvas = document.getElementById('mapCanvas');
    const dimSel = document.getElementById('mapDim');
    const status = document.getElementById('mapStatus');
    const ctx = canvas.getContext('2d');

    // World view: which chunk is centred and at what pixel scale.
    let centerChunkX = 0, centerChunkZ = 0;
    let pixelsPerChunk = 32;     // 32 px per 16-block chunk = 2 px/block
    let dragging = false;
    let dragX = 0, dragZ = 0;

    // Tile cache: key = `${dim}|${x}|${z}` -> ImageData OR 'pending' OR 'missing'
    const tileCache = new Map();
    let dim = dimSel.value;
    let chunksTopicCoords = []; // most recent coord set from chunks_topic

    // 64-colour palette (same shape the bridge uses) — index 0 is
    // transparent. Real palette comes from bridge tiles; this is just
    // a fallback grayscale until we receive any real chunk data.
    function fitCanvas() {
        const dpr = window.devicePixelRatio || 1;
        const r = canvas.parentElement.getBoundingClientRect();
        canvas.width = Math.floor(r.width * dpr);
        canvas.height = Math.floor(r.height * dpr);
        canvas.style.width = r.width + 'px';
        canvas.style.height = r.height + 'px';
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    function visibleChunkRange() {
        const w = canvas.clientWidth;
        const h = canvas.clientHeight;
        const chunksW = Math.ceil(w / pixelsPerChunk) + 2;
        const chunksH = Math.ceil(h / pixelsPerChunk) + 2;
        return {
            x0: centerChunkX - Math.ceil(chunksW / 2),
            x1: centerChunkX + Math.ceil(chunksW / 2),
            z0: centerChunkZ - Math.ceil(chunksH / 2),
            z1: centerChunkZ + Math.ceil(chunksH / 2)
        };
    }

    async function fetchTile(cx, cz) {
        const key = `${dim}|${cx}|${cz}`;
        if (tileCache.has(key)) return;
        tileCache.set(key, 'pending');
        try {
            const j = await api.get(`/api/map/tile?dim=${encodeURIComponent(dim)}&x=${cx}&z=${cz}`);
            // Decode base64 pixels into an ImageData. Bridge response
            // shape: { width, height, pixels (base64), palette? }.
            const bytes = atob(j.pixels || '');
            const w = j.width || 16, h = j.height || 16;
            const img = ctx.createImageData(w, h);
            const palette = j.palette || null;
            for (let i = 0; i < bytes.length && i < w * h; i++) {
                const idx = bytes.charCodeAt(i) & 0xff;
                let r = idx, g = idx, b = idx, a = 255;
                if (palette && palette[idx]) {
                    const p = palette[idx];
                    r = p[0]; g = p[1]; b = p[2]; a = p.length > 3 ? p[3] : 255;
                }
                const o = i * 4;
                img.data[o]   = r;
                img.data[o+1] = g;
                img.data[o+2] = b;
                img.data[o+3] = a;
            }
            tileCache.set(key, img);
            requestRender();
        } catch (e) {
            if (e.status === 404) tileCache.set(key, 'missing');
            else tileCache.delete(key);
        }
    }

    let renderQueued = false;
    function requestRender() {
        if (renderQueued) return;
        renderQueued = true;
        requestAnimationFrame(render);
    }

    function render() {
        renderQueued = false;
        fitCanvas();
        const w = canvas.clientWidth, h = canvas.clientHeight;
        ctx.fillStyle = '#101010';
        ctx.fillRect(0, 0, w, h);

        const r = visibleChunkRange();
        for (let z = r.z0; z <= r.z1; z++) {
            for (let x = r.x0; x <= r.x1; x++) {
                const px = w / 2 + (x - centerChunkX) * pixelsPerChunk;
                const py = h / 2 + (z - centerChunkZ) * pixelsPerChunk;
                const key = `${dim}|${x}|${z}`;
                const v = tileCache.get(key);
                if (v && v !== 'pending' && v !== 'missing') {
                    ctx.imageSmoothingEnabled = false;
                    // Draw scaled.
                    const tmp = document.createElement('canvas');
                    tmp.width = v.width; tmp.height = v.height;
                    tmp.getContext('2d').putImageData(v, 0, 0);
                    ctx.drawImage(tmp, px, py, pixelsPerChunk, pixelsPerChunk);
                } else {
                    ctx.strokeStyle = 'rgba(50,50,50,0.6)';
                    ctx.strokeRect(px, py, pixelsPerChunk, pixelsPerChunk);
                }
                if (!v) fetchTile(x, z);
            }
        }

        status.textContent = `chunk (${centerChunkX}, ${centerChunkZ}) — ${pixelsPerChunk}px/chunk — ${tileCache.size} tile(s)`;
    }

    function onPlayersSnapshot(payload) {
        if (!payload || !payload.players || !payload.players.length) return;
        ctx.fillStyle = '#fff';
        for (const p of payload.players) {
            if (p.dim !== dim) continue;
            const cx = Math.floor((p.x || 0) / 16);
            const cz = Math.floor((p.z || 0) / 16);
            const w = canvas.clientWidth, h = canvas.clientHeight;
            const px = w / 2 + (cx - centerChunkX) * pixelsPerChunk + pixelsPerChunk / 2;
            const py = h / 2 + (cz - centerChunkZ) * pixelsPerChunk + pixelsPerChunk / 2;
            ctx.beginPath();
            ctx.arc(px, py, 4, 0, Math.PI * 2);
            ctx.fill();
        }
    }

    function onChunksSnapshot(payload) {
        chunksTopicCoords = (payload && payload.dimensions && payload.dimensions[dim]
            && payload.dimensions[dim].coords) || [];
    }

    function init() {
        canvas.addEventListener('mousedown', e => {
            dragging = true;
            dragX = e.clientX; dragZ = e.clientY;
        });
        window.addEventListener('mouseup', () => { dragging = false; });
        window.addEventListener('mousemove', e => {
            if (!dragging) return;
            const dx = e.clientX - dragX;
            const dz = e.clientY - dragZ;
            dragX = e.clientX; dragZ = e.clientY;
            centerChunkX -= dx / pixelsPerChunk;
            centerChunkZ -= dz / pixelsPerChunk;
            requestRender();
        });
        canvas.addEventListener('wheel', e => {
            e.preventDefault();
            const factor = e.deltaY < 0 ? 1.25 : 0.8;
            pixelsPerChunk = Math.max(4, Math.min(128, pixelsPerChunk * factor));
            requestRender();
        }, { passive: false });

        dimSel.addEventListener('change', () => {
            dim = dimSel.value;
            tileCache.clear();
            requestRender();
        });

        wsBus.on('players_topic', onPlayersSnapshot);
        wsBus.on('chunks_topic', onChunksSnapshot);

        window.addEventListener('resize', requestRender);
        document.addEventListener('paneActivated', e => {
            if (e.detail === 'map') requestRender();
        });
        requestRender();
    }

    return { init };
})();
