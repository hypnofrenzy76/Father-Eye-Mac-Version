#!/usr/bin/env python3
# Father Eye Claude Agent — terminal Claude with file + bash tools.
# Tested target: macOS High Sierra 10.13.6 with Python 3.9 from python.org.
#
# Usage:
#   export ANTHROPIC_API_KEY=sk-ant-...
#   python3 claude_agent.py            # manual approve for bash
#   python3 claude_agent.py --auto     # auto-approve bash (use with care)
#   python3 claude_agent.py --model claude-sonnet-4-6
#
# In the REPL: /clear /auto /model <id> /help /exit

import argparse
import os
import subprocess
import sys
from pathlib import Path

try:
    from anthropic import Anthropic
except ImportError:
    sys.stderr.write(
        "ERROR: anthropic package not installed.\n"
        "  source .venv/bin/activate\n"
        "  pip install -r requirements.txt\n"
    )
    sys.exit(1)


DEFAULT_MODEL = "claude-opus-4-7"
MAX_TOKENS = 32000
CWD = Path.cwd().resolve()


class C:
    BOLD = "\033[1m"
    DIM = "\033[2m"
    RED = "\033[31m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    CYAN = "\033[36m"
    MAGENTA = "\033[35m"
    GRAY = "\033[90m"
    RESET = "\033[0m"


SYSTEM_PROMPT = f"""You are a coding assistant running in a terminal on macOS.
Working directory: {CWD}

You have these tools:
  - read_file(path)              read a text file
  - write_file(path, content)    create or overwrite a file
  - edit_file(path, old, new)    replace exact unique text in a file
  - list_dir(path)               list directory contents
  - bash(command, description)   run a shell command in the cwd

Guidelines:
  - Read a file before editing it.
  - Prefer edit_file over write_file for existing files.
  - Use bash for git, build, test, grep, find — not for file edits.
  - Keep responses concise; the user is reading them in a terminal."""


def safe_path(p: str) -> Path:
    return Path(p).expanduser().resolve()


def tool_read_file(args):
    p = safe_path(args["path"])
    print(f"{C.GRAY}  read {p}{C.RESET}")
    try:
        return p.read_text()
    except Exception as e:
        return f"ERROR: {e}"


def tool_write_file(args):
    p = safe_path(args["path"])
    content = args["content"]
    print(f"{C.YELLOW}  write {p} ({len(content)} bytes){C.RESET}")
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
        return f"Wrote {len(content)} bytes to {p}"
    except Exception as e:
        return f"ERROR: {e}"


def tool_edit_file(args):
    p = safe_path(args["path"])
    old = args["old_string"]
    new = args["new_string"]
    try:
        text = p.read_text()
    except Exception as e:
        return f"ERROR reading {p}: {e}"
    n = text.count(old)
    if n == 0:
        return f"ERROR: old_string not found in {p}"
    if n > 1:
        return f"ERROR: old_string appears {n} times in {p}; needs to be unique"
    try:
        p.write_text(text.replace(old, new))
    except Exception as e:
        return f"ERROR writing {p}: {e}"
    print(f"{C.YELLOW}  edit {p}{C.RESET}")
    return f"Edited {p}"


def tool_list_dir(args):
    p = safe_path(args.get("path", "."))
    print(f"{C.GRAY}  ls {p}{C.RESET}")
    if not p.is_dir():
        return f"ERROR: {p} is not a directory"
    out = []
    for entry in sorted(p.iterdir()):
        out.append(entry.name + ("/" if entry.is_dir() else ""))
    return "\n".join(out) if out else "(empty)"


def tool_bash(args, auto_approve):
    command = args["command"]
    description = args.get("description", "")
    print(f"{C.YELLOW}  bash: {command}{C.RESET}")
    if description:
        print(f"{C.GRAY}    ({description}){C.RESET}")
    if not auto_approve:
        try:
            ans = input(f"{C.BOLD}    run? [y/N] {C.RESET}").strip().lower()
        except (EOFError, KeyboardInterrupt):
            ans = ""
        if ans not in ("y", "yes"):
            return "DENIED by user"
    try:
        r = subprocess.run(
            command, shell=True, capture_output=True, text=True,
            timeout=300, cwd=str(CWD),
        )
    except subprocess.TimeoutExpired:
        return "ERROR: timed out after 300s"
    except Exception as e:
        return f"ERROR: {e}"
    body = ""
    if r.stdout:
        body += "STDOUT:\n" + r.stdout
    if r.stderr:
        body += ("\n" if body else "") + "STDERR:\n" + r.stderr
    body += f"\n[exit {r.returncode}]"
    return body


TOOL_SCHEMA = [
    {
        "name": "read_file",
        "description": "Read the text contents of a file.",
        "input_schema": {
            "type": "object",
            "properties": {"path": {"type": "string", "description": "Path to the file."}},
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Create a new file or overwrite an existing one. Prefer edit_file for existing files.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "content": {"type": "string"},
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "edit_file",
        "description": "Replace exact text in a file. old_string must appear exactly once. Read the file first to confirm uniqueness.",
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "old_string": {"type": "string", "description": "Text to find. Must be unique in the file."},
                "new_string": {"type": "string", "description": "Replacement text."},
            },
            "required": ["path", "old_string", "new_string"],
        },
    },
    {
        "name": "list_dir",
        "description": "List directory contents.",
        "input_schema": {
            "type": "object",
            "properties": {"path": {"type": "string", "description": "Defaults to '.'"}},
        },
    },
    {
        "name": "bash",
        "description": "Run a shell command in the working directory. Use for git, build, test, grep, find. Not for file edits — use edit_file.",
        "input_schema": {
            "type": "object",
            "properties": {
                "command": {"type": "string"},
                "description": {"type": "string", "description": "One-line summary of what this does."},
            },
            "required": ["command"],
        },
    },
]


def execute_tool(name, args, auto_approve):
    if name == "read_file":
        return tool_read_file(args)
    if name == "write_file":
        return tool_write_file(args)
    if name == "edit_file":
        return tool_edit_file(args)
    if name == "list_dir":
        return tool_list_dir(args)
    if name == "bash":
        return tool_bash(args, auto_approve)
    return f"ERROR: unknown tool {name}"


def run_turn(client, model, history, auto_approve):
    """Drive the agent loop until end_turn. Mutates history in place."""
    while True:
        active = None  # "text" | None
        with client.messages.stream(
            model=model,
            max_tokens=MAX_TOKENS,
            system=[{
                "type": "text",
                "text": SYSTEM_PROMPT,
                "cache_control": {"type": "ephemeral"},
            }],
            tools=TOOL_SCHEMA,
            thinking={"type": "adaptive"},
            messages=history,
        ) as stream:
            for event in stream:
                t = getattr(event, "type", None)
                if t == "content_block_start":
                    if event.content_block.type == "text":
                        sys.stdout.write(f"\n{C.CYAN}")
                        sys.stdout.flush()
                        active = "text"
                    else:
                        active = None
                elif t == "content_block_delta":
                    if active == "text" and getattr(event.delta, "type", None) == "text_delta":
                        sys.stdout.write(event.delta.text)
                        sys.stdout.flush()
                elif t == "content_block_stop":
                    if active == "text":
                        sys.stdout.write(f"{C.RESET}\n")
                        sys.stdout.flush()
                    active = None
            response = stream.get_final_message()

        history.append({"role": "assistant", "content": response.content})

        if response.stop_reason == "tool_use":
            tool_uses = [b for b in response.content if b.type == "tool_use"]
            results = []
            for tu in tool_uses:
                result = execute_tool(tu.name, tu.input, auto_approve)
                results.append({
                    "type": "tool_result",
                    "tool_use_id": tu.id,
                    "content": result,
                })
            history.append({"role": "user", "content": results})
            continue

        if response.stop_reason in ("end_turn", "stop_sequence"):
            return

        print(f"\n{C.RED}(stopped: {response.stop_reason}){C.RESET}")
        return


def main():
    p = argparse.ArgumentParser(description="Father Eye Claude Agent")
    p.add_argument("--model", default=os.environ.get("CLAUDE_MODEL", DEFAULT_MODEL))
    p.add_argument("--auto", action="store_true",
                   help="auto-approve bash commands (otherwise prompts y/N)")
    args = p.parse_args()

    if not os.environ.get("ANTHROPIC_API_KEY"):
        print(f"{C.RED}ANTHROPIC_API_KEY not set.{C.RESET}")
        print(f"{C.GRAY}Get one at https://console.anthropic.com/settings/keys{C.RESET}")
        print(f"{C.GRAY}Then: export ANTHROPIC_API_KEY=sk-ant-...{C.RESET}")
        sys.exit(1)

    try:
        import readline  # noqa: F401  -- enables arrow-key history at the prompt
    except ImportError:
        pass

    client = Anthropic()
    history = []
    auto_approve = args.auto
    model = args.model

    print(f"{C.BOLD}{C.MAGENTA}Father Eye Claude Agent{C.RESET}")
    mode = "auto-approve bash" if auto_approve else "manual approve bash"
    print(f"{C.GRAY}model: {model}  |  cwd: {CWD}  |  {mode}{C.RESET}")
    print(f"{C.GRAY}/help for commands. Ctrl-D or /exit to quit.{C.RESET}")
    print()

    while True:
        try:
            line = input(f"{C.GREEN}> {C.RESET}").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break

        if not line:
            continue
        if line in ("/exit", "/quit"):
            break
        if line == "/clear":
            history = []
            print(f"{C.GRAY}(history cleared){C.RESET}")
            continue
        if line == "/auto":
            auto_approve = not auto_approve
            print(f"{C.GRAY}(auto-approve: {'on' if auto_approve else 'off'}){C.RESET}")
            continue
        if line.startswith("/model"):
            parts = line.split(maxsplit=1)
            if len(parts) == 2:
                model = parts[1].strip()
                print(f"{C.GRAY}(model: {model}){C.RESET}")
            else:
                print(f"{C.GRAY}(model: {model}){C.RESET}")
            continue
        if line == "/help":
            print(f"{C.GRAY}/clear         reset conversation history")
            print(f"/auto          toggle bash auto-approve (currently {'on' if auto_approve else 'off'})")
            print(f"/model <id>    switch model (currently {model})")
            print(f"/exit          quit{C.RESET}")
            continue

        history.append({"role": "user", "content": line})

        try:
            run_turn(client, model, history, auto_approve)
        except KeyboardInterrupt:
            print(f"\n{C.YELLOW}(interrupted){C.RESET}")
        except Exception as e:
            print(f"\n{C.RED}Error: {e}{C.RESET}")
            if history and history[-1].get("role") == "user" \
                    and isinstance(history[-1].get("content"), str):
                history.pop()


if __name__ == "__main__":
    main()
