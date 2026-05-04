// Config pane: AppConfig editor (raw JSON for v1) + web-access
// credential change form. Mirrors the desktop ConfigPane / Web Access
// tab.

window.configView = (() => {
    const ta = document.getElementById('configJson');
    const saveBtn = document.getElementById('btnSaveConfig');
    const msg = document.getElementById('configMsg');

    const credForm = document.getElementById('credForm');
    const credCurrent = document.getElementById('credCurrent');
    const credUsername = document.getElementById('credUsername');
    const credNewPassword = document.getElementById('credNewPassword');
    const credMsg = document.getElementById('credMsg');

    async function load() {
        try {
            const cfg = await api.getConfig();
            ta.value = JSON.stringify(cfg, null, 2);
            msg.textContent = 'Loaded.';
        } catch (e) {
            msg.textContent = 'Load failed: ' + e.message;
        }
    }

    async function save() {
        let parsed;
        try { parsed = JSON.parse(ta.value); }
        catch (e) {
            msg.textContent = 'Invalid JSON: ' + e.message;
            return;
        }
        saveBtn.disabled = true;
        try {
            await api.putConfig(parsed);
            msg.textContent = 'Saved. Restart the panel to fully apply.';
        } catch (e) {
            msg.textContent = 'Save failed: ' + e.message;
        } finally {
            saveBtn.disabled = false;
        }
    }

    async function updateCreds(e) {
        e.preventDefault();
        credMsg.textContent = '';
        const username = credUsername.value.trim();
        const newPw = credNewPassword.value;
        const cur = credCurrent.value;
        if (!username || newPw.length < 8) {
            credMsg.textContent = 'Username and an 8+ char password are required.';
            return;
        }
        try {
            await api.updateCreds(username, cur, newPw);
            credMsg.textContent = 'Credentials updated. New sessions will use the new password.';
            credCurrent.value = '';
            credNewPassword.value = '';
        } catch (e2) {
            credMsg.textContent = 'Update failed: ' + e2.message;
        }
    }

    function init() {
        saveBtn.addEventListener('click', save);
        credForm.addEventListener('submit', updateCreds);
        document.addEventListener('paneActivated', e => {
            if (e.detail === 'config' && !ta.value) load();
        });
    }

    return { init, load };
})();
