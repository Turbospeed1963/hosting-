import json
import os
import re
import hashlib
import hmac
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
from functools import wraps
from pathlib import Path

from flask import Flask, jsonify, request

BASE = Path(__file__).parent.resolve()
DATA = BASE / "bots"
DATA.mkdir(exist_ok=True)
PORT = int(os.environ.get("PORT", "5000"))

# API key: from env API_KEY, otherwise generated once and saved in api_key.txt
KEY_FILE = BASE / "api_key.txt"
API_KEY = os.environ.get("API_KEY")
if not API_KEY:
    if KEY_FILE.exists():
        API_KEY = KEY_FILE.read_text().strip()
    else:
        API_KEY = secrets.token_urlsafe(24)
        KEY_FILE.write_text(API_KEY)
        os.chmod(KEY_FILE, 0o600)

app = Flask(__name__)
procs = {}  # bot id -> subprocess.Popen


def auth(f):
    @wraps(f)
    def wrapper(*a, **kw):
        got = request.headers.get("Authorization", "")
        if not hmac.compare_digest(got.encode(), f"Bearer {API_KEY}".encode()):
            return jsonify(error="unauthorized"), 401
        return f(*a, **kw)
    return wrapper


def bot_dir(bid):
    if not re.fullmatch(r"[0-9a-f]{32}", bid):
        return None
    d = DATA / bid
    return d if d.is_dir() else None


def read_meta(d):
    return json.loads((d / "meta.json").read_text())


def write_meta(d, meta):
    (d / "meta.json").write_text(json.dumps(meta))


def is_running(bid):
    p = procs.get(bid)
    return p is not None and p.poll() is None


def kill_orphan(d):
    """After a server restart the old bot process may still be alive; stop it
    so two copies never poll Telegram at once."""
    pf = d / "pid.txt"
    try:
        pid = int(pf.read_text().strip())
        cmd = Path(f"/proc/{pid}/cmdline").read_bytes()
        if b"bot.py" in cmd:
            os.kill(pid, 15)
            time.sleep(1)
    except Exception:
        pass
    try:
        pf.unlink()
    except FileNotFoundError:
        pass


def start_bot(bid, d):
    if is_running(bid):
        return
    kill_orphan(d)
    meta = read_meta(d)
    env = os.environ.copy()
    env.pop("API_KEY", None)  # don't hand the server key to bot code
    env["BOT_TOKEN"] = meta["token"]
    logf = d / "log.txt"
    if logf.exists() and logf.stat().st_size > 1_000_000:
        logf.write_bytes(logf.read_bytes()[-200_000:])
    log = open(logf, "ab")
    log.write(b"\n--- starting ---\n")
    log.flush()
    procs[bid] = subprocess.Popen(
        [sys.executable, "-u", "bot.py"],
        cwd=d, env=env, stdout=log, stderr=subprocess.STDOUT,
    )
    log.close()  # the child keeps its own copy
    (d / "pid.txt").write_text(str(procs[bid].pid))


def stop_bot(bid):
    d = bot_dir(bid)
    if d:
        kill_orphan(d)
    p = procs.pop(bid, None)
    if p and p.poll() is None:
        p.terminate()
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            p.kill()


@app.get("/api/ping")
@auth
def ping():
    return jsonify(ok=True)


@app.get("/api/bots")
@auth
def list_bots():
    out = []
    for d in sorted(DATA.iterdir()):
        if (d / "meta.json").exists():
            m = read_meta(d)
            out.append({"id": d.name, "name": m["name"], "running": is_running(d.name)})
    return jsonify(out)


@app.post("/api/bots")
@auth
def create_bot():
    body = request.get_json(silent=True) or {}
    bid = uuid.uuid4().hex
    d = DATA / bid
    d.mkdir()
    write_meta(d, {"name": body.get("name") or "My bot", "token": body.get("token", "")})
    (d / "bot.py").write_text("")
    return jsonify(id=bid)


@app.get("/api/bots/<bid>")
@auth
def get_bot(bid):
    d = bot_dir(bid)
    if not d:
        return jsonify(error="not found"), 404
    m = read_meta(d)
    return jsonify(name=m["name"], token=m["token"], code=(d / "bot.py").read_text())


@app.put("/api/bots/<bid>")
@auth
def update_bot(bid):
    d = bot_dir(bid)
    if not d:
        return jsonify(error="not found"), 404
    body = request.get_json(silent=True) or {}
    m = read_meta(d)
    if "name" in body:
        m["name"] = body["name"]
    if "token" in body:
        m["token"] = body["token"]
    write_meta(d, m)
    if "code" in body:
        (d / "bot.py").write_text(body["code"])
    return jsonify(ok=True)


@app.delete("/api/bots/<bid>")
@auth
def delete_bot(bid):
    d = bot_dir(bid)
    if not d:
        return jsonify(error="not found"), 404
    stop_bot(bid)
    kill_orphan(d)
    shutil.rmtree(d, ignore_errors=True)
    return jsonify(ok=True)


@app.post("/api/bots/<bid>/<action>")
@auth
def control(bid, action):
    d = bot_dir(bid)
    if not d:
        return jsonify(error="not found"), 404
    if action == "start":
        start_bot(bid, d)
    elif action == "stop":
        stop_bot(bid)
    elif action == "restart":
        stop_bot(bid)
        start_bot(bid, d)
    else:
        return jsonify(error="bad action"), 400
    return jsonify(ok=True, running=is_running(bid))


@app.get("/api/bots/<bid>/logs")
@auth
def logs(bid):
    d = bot_dir(bid)
    if not d:
        return jsonify(error="not found"), 404
    f = d / "log.txt"
    text = f.read_bytes()[-20000:].decode("utf-8", "replace") if f.exists() else ""
    return jsonify(running=is_running(bid), log=text)


def topic():
    return "bh-" + hashlib.sha256(API_KEY.encode()).hexdigest()[:32]


def publish_url(url):
    """Publish the current tunnel link so the app can find it by itself."""
    try:
        req = urllib.request.Request(f"https://ntfy.sh/{topic()}", data=url.encode(), method="POST")
        urllib.request.urlopen(req, timeout=10).read()
    except Exception as e:
        print("Could not publish link:", e, flush=True)


def start_tunnel():
    exe = shutil.which("cloudflared")
    if not exe:
        print("cloudflared not found. Install it first.")
        return False
    p = subprocess.Popen(
        [exe, "tunnel", "--url", f"http://127.0.0.1:{PORT}", "--no-autoupdate"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )

    def republish(url):
        while p.poll() is None:
            publish_url(url)
            time.sleep(1800)

    def watch():
        announced = False
        for line in p.stdout:
            m = re.search(r"https://[\w-]+\.trycloudflare\.com", line)
            if m and not announced:
                announced = True
                print("\n=== Enter this key in the app (only once) ===")
                print(API_KEY)
                print("==============================================\n", flush=True)
                threading.Thread(target=republish, args=(m.group(0),), daemon=True).start()

    threading.Thread(target=watch, daemon=True).start()
    return True


if __name__ == "__main__":
    tunnel = os.environ.get("NO_TUNNEL") != "1" and start_tunnel()
    host = "127.0.0.1" if tunnel else "0.0.0.0"
    if not tunnel:
        print(f"\nAPI key: {API_KEY}\nListening on port {PORT}\n")
    app.run(host=host, port=PORT, threaded=True)
