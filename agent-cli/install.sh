#!/usr/bin/env bash
# Set up the Father Eye Claude Agent on macOS High Sierra 10.13.6.
#
# Prerequisite: Python 3.9 from https://www.python.org/downloads/release/python-3913/
# (3.9.13 is the last Python.org installer that ships universal2 binaries
# compatible with 10.13. /usr/bin/python3 on stock 10.13 is 2.7 — won't work.)
#
# Usage:
#   cd agent-cli
#   ./install.sh
#   source .venv/bin/activate
#   export ANTHROPIC_API_KEY=sk-ant-...
#   python3 claude_agent.py

set -e

cd "$(dirname "$0")"

PY=""
for candidate in python3.12 python3.11 python3.10 python3.9 python3; do
    if command -v "$candidate" >/dev/null 2>&1; then
        PY="$candidate"
        break
    fi
done

if [ -z "$PY" ]; then
    echo "ERROR: no python3 found on PATH."
    echo "Install Python 3.9.13 from python.org:"
    echo "  https://www.python.org/downloads/release/python-3913/"
    exit 1
fi

VER=$("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])')
MAJOR=$("$PY" -c 'import sys; print(sys.version_info[0])')
MINOR=$("$PY" -c 'import sys; print(sys.version_info[1])')

if [ "$MAJOR" -lt 3 ] || { [ "$MAJOR" -eq 3 ] && [ "$MINOR" -lt 9 ]; }; then
    echo "ERROR: $PY is Python $VER; need 3.9 or newer."
    echo "Install Python 3.9.13 from python.org:"
    echo "  https://www.python.org/downloads/release/python-3913/"
    exit 1
fi

echo "Using $PY ($VER)"

if [ ! -d .venv ]; then
    echo "Creating virtualenv at .venv ..."
    "$PY" -m venv .venv
fi

# shellcheck disable=SC1091
. .venv/bin/activate

echo "Upgrading pip ..."
python -m pip install --upgrade pip >/dev/null

echo "Installing requirements ..."
pip install -r requirements.txt

echo
echo "Done. To run:"
echo "  source agent-cli/.venv/bin/activate"
echo "  export ANTHROPIC_API_KEY=sk-ant-..."
echo "  python3 agent-cli/claude_agent.py"
