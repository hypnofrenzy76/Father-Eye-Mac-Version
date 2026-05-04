(() => {
    const form = document.getElementById('loginForm');
    const err = document.getElementById('error');
    const submit = document.getElementById('submit');

    function showError(msg) {
        err.textContent = msg;
        err.hidden = false;
    }

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        err.hidden = true;
        submit.disabled = true;
        const username = document.getElementById('username').value.trim();
        const password = document.getElementById('password').value;
        try {
            const resp = await fetch('/api/auth/login', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });
            const data = await resp.json().catch(() => ({}));
            if (resp.ok && data.ok) {
                window.location.href = '/static/index.html';
                return;
            }
            if (resp.status === 429 && data.retryAfterSeconds) {
                showError(`Too many failed attempts. Try again in ${data.retryAfterSeconds} s.`);
            } else {
                showError(data.error || 'Sign-in failed.');
            }
        } catch (e2) {
            showError('Network error: ' + e2.message);
        } finally {
            submit.disabled = false;
        }
    });
})();
