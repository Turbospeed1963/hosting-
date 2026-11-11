"""Bot Host server.

- Admin login key  -> /api/login gives a session token (brute-force lockout)
- One Docker container per bot (falls back to plain processes if Docker is missing)
- Packages are detected from bot.py imports (+ optional requirements.txt) and
  installed automatically before the bot starts
- CPU / RAM monitoring for the server and for every bot
"""
import ast
import hashlib
import hmac
import json
import os
import re
import secrets
import socket
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
PORT_FROM_ENV = "PORT" in os.environ


def _port_free(p):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sk:
        return sk.connect_ex(("127.0.0.1", p)) != 0


def resolve_port():
    """Use PORT if it is free. If PORT was set explicitly and is busy, stop with a clear message.
    If PORT was not set and 5000 is busy, use the next free port and say so."""
    global PORT
    if _port_free(PORT):
        return
    if PORT_FROM_ENV:
        raise SystemExit(f"Port {PORT} is already in use by another program. "
                         f"Stop that program, or set PORT to a different number.")
    old = PORT
    for cand in range(old + 1, old + 100):
        if _port_free(cand):
            PORT = cand
            print(f"Port {old} is busy, using port {PORT} instead.", flush=True)
            return
    raise SystemExit(f"No free port found near {old}.")
IMAGE = os.environ.get("BOT_IMAGE", "python:3.11-slim")
MEM_MB = int(os.environ.get("BOT_MEM_MB", "256"))      # RAM limit per bot
CPUS = os.environ.get("BOT_CPUS", "0.5")                # CPU limit per bot
SESSION_TTL = 7 * 24 * 3600

# ---------------------------------------------------------------- admin key
KEY_FILE = BASE / "admin_key.txt"
OLD_KEY_FILE = BASE / "api_key.txt"
ADMIN_KEY = os.environ.get("ADMIN_KEY") or os.environ.get("API_KEY")
KEY_FROM_ENV = bool(ADMIN_KEY)
if not ADMIN_KEY:
    if KEY_FILE.exists():
        ADMIN_KEY = KEY_FILE.read_text().strip()
    elif OLD_KEY_FILE.exists():
        ADMIN_KEY = OLD_KEY_FILE.read_text().strip()
        KEY_FILE.write_text(ADMIN_KEY)
    else:
        ADMIN_KEY = secrets.token_urlsafe(24)
        KEY_FILE.write_text(ADMIN_KEY)
    os.chmod(KEY_FILE, 0o600)

SESSIONS = {}      # sha256(token) -> expiry time
LOGIN_FAILS = {}   # ip -> (fail count, locked until)
TUNNEL_URL = None

app = Flask(__name__)
procs = {}         # bot id -> Popen (only used without Docker)
STARTING = set()


def _h(token):
    return hashlib.sha256(token.encode()).hexdigest()


def auth(f):
    @wraps(f)
    def wrapper(*a, **kw):
        got = request.headers.get("Authorization", "")
        token = got[7:] if got.startswith("Bearer ") else ""
        # Accept either a session token from /api/login, or the admin key itself
        # as a Bearer token (what older app builds send).
        if token and hmac.compare_digest(token.encode(), ADMIN_KEY.encode()):
            return f(*a, **kw)
        exp = SESSIONS.get(_h(token)) if token else None
        if not exp or exp < time.time():
            return jsonify(error="unauthorized"), 401
        return f(*a, **kw)
    return wrapper


@app.post("/api/login")
def login():
    ip = request.headers.get("CF-Connecting-IP") or request.remote_addr or "?"
    now = time.time()
    fails, until = LOGIN_FAILS.get(ip, (0, 0))
    if until > now:
        return jsonify(error="locked", retry_after=int(until - now)), 429
    body = request.get_json(silent=True) or {}
    got = str(body.get("key", "")).strip()
    if not hmac.compare_digest(got.encode(), ADMIN_KEY.encode()):
        fails += 1
        # 5 wrong keys -> locked for 5 minutes
        LOGIN_FAILS[ip] = (0, now + 300) if fails >= 5 else (fails, 0)
        return jsonify(error="wrong key"), 401
    LOGIN_FAILS.pop(ip, None)
    for k in [k for k, v in SESSIONS.items() if v < now]:
        SESSIONS.pop(k, None)
    token = secrets.token_urlsafe(32)
    SESSIONS[_h(token)] = now + SESSION_TTL
    return jsonify(token=token, expires_in=SESSION_TTL)


@app.post("/api/admin/key")
@auth
def rotate_key():
    global ADMIN_KEY
    if KEY_FROM_ENV:
        return jsonify(error="The key is set by the ADMIN_KEY environment variable."), 400
    ADMIN_KEY = secrets.token_urlsafe(24)
    KEY_FILE.write_text(ADMIN_KEY)
    os.chmod(KEY_FILE, 0o600)
    SESSIONS.clear()
    if TUNNEL_URL:
        threading.Thread(target=publish_url, args=(TUNNEL_URL,), daemon=True).start()
    return jsonify(key=ADMIN_KEY)


# ------------------------------------------------------------------ helpers
def sh(args, timeout=30, env=None):
    return subprocess.run(args, capture_output=True, text=True, errors="replace",
                          timeout=timeout, env=env)


def docker_ok():
    if os.environ.get("USE_DOCKER") == "0" or not shutil.which("docker"):
        return False
    try:
        return sh(["docker", "info"], timeout=20).returncode == 0
    except Exception:
        return False


USE_DOCKER = docker_ok()


def cname(bid):
    return "bh-" + bid


def bot_dir(bid):
    if not re.fullmatch(r"[0-9a-f]{32}", bid):
        return None
    d = DATA / bid
    return d if d.is_dir() else None


def read_meta(d):
    return json.loads((d / "meta.json").read_text())


def write_meta(d, meta):
    (d / "meta.json").write_text(json.dumps(meta))


def note(d, msg):
    with open(d / "log.txt", "a") as f:
        f.write(msg + "\n")


# ------------------------------------------------- package auto-detection
PIP_MAP = {
    "telegram": "python-telegram-bot", "telebot": "pyTelegramBotAPI",
    "PIL": "pillow", "cv2": "opencv-python-headless", "bs4": "beautifulsoup4",
    "yaml": "pyyaml", "dotenv": "python-dotenv", "sklearn": "scikit-learn",
    "dateutil": "python-dateutil", "Crypto": "pycryptodome", "jwt": "pyjwt",
    "attr": "attrs", "serial": "pyserial", "magic": "python-magic",
    "docx": "python-docx", "pptx": "python-pptx", "fitz": "pymupdf",
    "googleapiclient": "google-api-python-client", "google": "google-api-python-client",
    "pyrogram": "pyrogram tgcrypto", "telethon": "telethon", "git": "gitpython",
    "zmq": "pyzmq", "nacl": "pynacl", "OpenSSL": "pyopenssl", "ffmpeg": "ffmpeg-python",
    "speech_recognition": "SpeechRecognition", "youtube_dl": "yt-dlp", "yt_dlp": "yt-dlp",
    "skimage": "scikit-image", "psycopg2": "psycopg2-binary", "MySQLdb": "mysqlclient",
    "discord": "discord.py", "redis": "redis", "flask_cors": "flask-cors",
}
STDLIB = set(getattr(sys, "stdlib_module_names", ())) | {"__future__"}


def detect_packages(code, d=None):
    mods = set()
    try:
        for n in ast.walk(ast.parse(code)):
            if isinstance(n, ast.Import):
                mods.update(a.name.split(".")[0] for a in n.names)
            elif isinstance(n, ast.ImportFrom) and n.level == 0 and n.module:
                mods.add(n.module.split(".")[0])
    except SyntaxError:
        mods.update(m.group(1) for m in re.finditer(r"^\s*(?:from|import)\s+([A-Za-z_]\w*)", code, re.M))
    local = set()
    if d:
        local = {p.stem for p in d.glob("*.py")} | {p.name for p in d.iterdir() if p.is_dir()}
    out = []
    for m in sorted(mods):
        if m in STDLIB or m in local or m.startswith("_"):
            continue
        out.extend(PIP_MAP.get(m, m).split())
    if "python-telegram-bot" in out and re.search(r"job_queue|JobQueue|run_repeating|run_daily|run_once", code):
        out[out.index("python-telegram-bot")] = "python-telegram-bot[job-queue]"
    return sorted(set(out))


def _norm(line):
    return re.split(r"[<>=!~\[;\s]", line, 1)[0].lower().replace("_", "-")


def compute_requirements(code, user_text, d=None):
    lines, names = [], set()
    for l in (user_text or "").splitlines():
        l = l.strip()
        if l and not l.startswith("#"):
            lines.append(l)
            names.add(_norm(l))
    for p in detect_packages(code, d):
        if _norm(p) not in names:
            lines.append(p)
            names.add(_norm(p))
    return lines


def build_requirements(d):
    code = (d / "bot.py").read_text(errors="replace")
    uf = d / "user_requirements.txt"
    lines = compute_requirements(code, uf.read_text() if uf.exists() else "", d)
    txt = "\n".join(lines) + ("\n" if lines else "")
    (d / "requirements.txt").write_text(txt)
    (d / ".req_want").write_text(hashlib.sha256(txt.encode()).hexdigest())
    return lines


# Runs inside the container (or locally): install missing packages, then start.
INSTALL_SH = r'''
if [ -s requirements.txt ] && [ "$(cat .req_want)" != "$(cat .req_done 2>/dev/null)" ]; then
  echo "--- installing packages ---"; cat requirements.txt
  if $PY -m pip install --no-cache-dir --disable-pip-version-check --target .pkgs -r requirements.txt; then
    cp .req_want .req_done; echo "--- packages installed ---"
  else
    echo "--- package install failed ---"; exit 1
  fi
fi
echo "--- starting bot ---"
exec $PY -u bot.py
'''


# ------------------------------------------------------------ run / stop
def running_ids():
    try:
        if USE_DOCKER:
            r = sh(["docker", "ps", "--format", "{{.Names}}"], timeout=15)
            return {n[3:] for n in r.stdout.split() if n.startswith("bh-")}
    except Exception:
        return set()
    return {b for b, p in procs.items() if p.poll() is None}


def status_of(bid, running):
    if bid in STARTING:
        return "starting"
    return "running" if bid in running else "stopped"


def docker_start(bid, d, meta):
    name = cname(bid)
    sh(["docker", "rm", "-f", name])
    if sh(["docker", "image", "inspect", IMAGE]).returncode != 0:
        note(d, f"--- pulling image {IMAGE} (first time only) ---")
        sh(["docker", "pull", IMAGE], timeout=900)
    env = os.environ.copy()
    env["BOT_TOKEN"] = meta["token"]      # passed by name, never on the command line
    cmd = [
        "docker", "run", "-d", "--name", name, "--restart", "unless-stopped",
        "--memory", f"{MEM_MB}m", "--memory-swap", f"{MEM_MB}m", "--cpus", CPUS,
        "--pids-limit", "256", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--user", f"{os.getuid()}:{os.getgid()}", "--tmpfs", "/tmp",
        "-v", f"{d}:/app", "-w", "/app",
        "-e", "BOT_TOKEN", "-e", "HOME=/tmp", "-e", "PYTHONUNBUFFERED=1",
        "-e", "PYTHONPATH=/app/.pkgs", "-e", "PY=python",
        "--log-driver", "json-file", "--log-opt", "max-size=1m", "--log-opt", "max-file=2",
        IMAGE, "sh", "-c", INSTALL_SH,
    ]
    r = sh(cmd, timeout=120, env=env)
    if r.returncode != 0:
        note(d, "docker run failed: " + r.stderr.strip())


def kill_orphan(d):
    pf = d / "pid.txt"
    try:
        pid = int(pf.read_text().strip())
        cmd = Path(f"/proc/{pid}/cmdline").read_bytes()
        if b"bot.py" in cmd:
            os.killpg(pid, 15)
            time.sleep(1)
    except Exception:
        pass
    try:
        pf.unlink()
    except FileNotFoundError:
        pass


def local_start(bid, d, meta):
    kill_orphan(d)
    env = os.environ.copy()
    for k in ("ADMIN_KEY", "API_KEY"):
        env.pop(k, None)
    env.update(BOT_TOKEN=meta["token"], PYTHONPATH=str(d / ".pkgs"),
               PY=sys.executable, PYTHONUNBUFFERED="1")
    logf = d / "log.txt"
    if logf.exists() and logf.stat().st_size > 1_000_000:
        logf.write_bytes(logf.read_bytes()[-200_000:])
    with open(logf, "ab") as log:
        procs[bid] = subprocess.Popen(["sh", "-c", INSTALL_SH], cwd=d, env=env, stdout=log,
                                      stderr=subprocess.STDOUT, start_new_session=True)
    (d / "pid.txt").write_text(str(procs[bid].pid))


def start_bot(bid, d):
    if bid in STARTING or bid in running_ids():
        return
    STARTING.add(bid)

    def work():
        try:
            if USE_DOCKER:
                (d / "log.txt").write_text("")
            meta = read_meta(d)
            build_requirements(d)
            (docker_start if USE_DOCKER else local_start)(bid, d, meta)
        except Exception as e:
            note(d, f"start failed: {e}")
        finally:
            STARTING.discard(bid)

    threading.Thread(target=work, daemon=True).start()


def stop_bot(bid, remove=False):
    d = bot_dir(bid)
    if USE_DOCKER:
        if remove:
            sh(["docker", "rm", "-f", cname(bid)])
        else:
            sh(["docker", "stop", "-t", "5", cname(bid)], timeout=30)
        return
    if d:
        kill_orphan(d)
    p = procs.pop(bid, None)
    if p and p.poll() is None:
        try:
            os.killpg(p.pid, 15)
            p.wait(timeout=5)
        except Exception:
            p.kill()


def get_log(bid, d):
    if USE_DOCKER:
        try:
            r = subprocess.run(["docker", "logs", "--tail", "300", cname(bid)], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, errors="replace", timeout=15)
            if r.returncode == 0 and r.stdout.strip():
                return r.stdout[-20000:]
        except Exception:
            pass
    f = d / "log.txt"
    return f.read_bytes()[-20000:].decode("utf-8", "replace") if f.exists() else ""


# ------------------------------------------------------- CPU / RAM monitor
STATS = {"host": {}, "bots": {}}


def _host_cpu():
    v = list(map(int, Path("/proc/stat").read_text().splitlines()[0].split()[1:]))
    return sum(v), v[3] + v[4]


def _host_mem():
    m = {}
    for l in Path("/proc/meminfo").read_text().splitlines():
        k, v = l.split(":")
        m[k] = int(v.split()[0]) * 1024
    return m["MemTotal"], m["MemTotal"] - m["MemAvailable"]


def _size(s):
    m = re.match(r"([\d.]+)\s*([A-Za-z]*)", s.strip())
    mult = {"b": 1, "kb": 1e3, "kib": 1024, "mb": 1e6, "mib": 1048576, "gb": 1e9, "gib": 1073741824}
    return int(float(m.group(1)) * mult.get(m.group(2).lower(), 1))


def _proc_sample(pid):
    s = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return int(s[11]) + int(s[12]), int(s[21]) * os.sysconf("SC_PAGE_SIZE")


def stats_loop():
    clk = os.sysconf("SC_CLK_TCK")
    prev_t, prev_tot, prev_idle = time.time(), *_host_cpu()
    prev_p = {}
    while True:
        time.sleep(3)
        try:
            bots = {}
            if USE_DOCKER:
                r = sh(["docker", "stats", "--no-stream", "--format", "{{json .}}"], timeout=30)
                for line in r.stdout.splitlines():
                    o = json.loads(line)
                    if o["Name"].startswith("bh-"):
                        used, lim = o["MemUsage"].split("/")
                        bots[o["Name"][3:]] = {"cpu": float(o["CPUPerc"].strip("%") or 0),
                                               "mem": _size(used), "mem_limit": _size(lim)}
            now = time.time()
            if not USE_DOCKER:
                for bid, p in list(procs.items()):
                    if p.poll() is None:
                        ticks, rss = _proc_sample(p.pid)
                        old = prev_p.get(bid)
                        cpu = 0.0
                        if old and now > old[0]:
                            cpu = (ticks - old[1]) / clk / (now - old[0]) * 100
                        prev_p[bid] = (now, ticks)
                        bots[bid] = {"cpu": round(max(cpu, 0), 1), "mem": rss, "mem_limit": 0}
            tot, idle = _host_cpu()
            dt, di = tot - prev_tot, idle - prev_idle
            cpu = (1 - di / dt) * 100 if dt > 0 else 0
            prev_t, prev_tot, prev_idle = now, tot, idle
            mt, mu = _host_mem()
            du = shutil.disk_usage(BASE)
            STATS["host"] = {"cpu": round(cpu, 1), "mem_used": mu, "mem_total": mt,
                             "disk_used": du.used, "disk_total": du.total, "docker": USE_DOCKER}
            STATS["bots"] = bots
        except Exception as e:
            print("stats error:", e, flush=True)


# ------------------------------------------------------------------- API
@app.get("/api/ping")
@auth
def ping():
    return jsonify(ok=True)


@app.get("/api/stats")
@auth
def stats():
    return jsonify(STATS["host"])


@app.get("/api/bots")
@auth
def list_bots():
    run = running_ids()
    out = []
    for d in sorted(DATA.iterdir()):
        if (d / "meta.json").exists():
            m = read_meta(d)
            st = status_of(d.name, run)
            b = STATS["bots"].get(d.name, {})
            out.append({"id": d.name, "name": m["name"], "status": st, "running": st != "stopped",
                        "cpu": b.get("cpu", 0), "mem": b.get("mem", 0)})
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


@app.post("/api/detect")
@auth
def detect():
    body = request.get_json(silent=True) or {}
    return jsonify(packages=compute_requirements(body.get("code", ""), body.get("requirements", "")))


@app.get("/api/bots/<bid>")
@auth
def get_bot(bid):
    d = bot_dir(bid)
    if not d:
        return jsonify(error="not found"), 404
    m = read_meta(d)
    code = (d / "bot.py").read_text(errors="replace")
    uf = d / "user_requirements.txt"
    return jsonify(name=m["name"], token=m["token"], code_size=len(code.encode()),
                   packages=compute_requirements(code, uf.read_text() if uf.exists() else "", d))


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
    if "requirements" in body:
        (d / "user_requirements.txt").write_text(body["requirements"])
    build_requirements(d)
    return jsonify(ok=True)


@app.delete("/api/bots/<bid>")
@auth
def delete_bot(bid):
    d = bot_dir(bid)
    if not d:
        return jsonify(error="not found"), 404
    stop_bot(bid, remove=True)
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
    st = status_of(bid, running_ids())
    return jsonify(ok=True, status=st, running=st != "stopped")


@app.get("/api/bots/<bid>/logs")
@auth
def logs(bid):
    d = bot_dir(bid)
    if not d:
        return jsonify(error="not found"), 404
    st = status_of(bid, running_ids())
    b = STATS["bots"].get(bid, {})
    return jsonify(status=st, running=st != "stopped", log=get_log(bid, d),
                   cpu=b.get("cpu", 0), mem=b.get("mem", 0), mem_limit=b.get("mem_limit", 0))


# ---------------------------------------------------- tunnel / discovery
def topic():
    return "bh-" + hashlib.sha256(ADMIN_KEY.encode()).hexdigest()[:32]


def publish_url(url):
    """Publish the current tunnel link so the app can find it by itself."""
    try:
        req = urllib.request.Request(f"https://ntfy.sh/{topic()}", data=url.encode(), method="POST")
        urllib.request.urlopen(req, timeout=10).read()
    except Exception as e:
        print("Could not publish link:", e, flush=True)


CLOUDFLARED_DEB = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb"


def install_cloudflared():
    """Opt-in only (AUTO_INSTALL_CLOUDFLARED=1). Runs the same two commands as the README.
    sudo may ask for your password in the terminal."""
    if not (shutil.which("curl") and shutil.which("dpkg") and shutil.which("sudo")):
        print("Auto-install needs curl, dpkg and sudo (Debian/Ubuntu).", flush=True)
        return None
    try:
        print("Installing cloudflared...", flush=True)
        subprocess.run(["curl", "-L", CLOUDFLARED_DEB, "-o", "cloudflared.deb"], cwd=BASE, check=True)
        subprocess.run(["sudo", "dpkg", "-i", "cloudflared.deb"], cwd=BASE, check=True)
        return shutil.which("cloudflared")
    except Exception as e:
        print("cloudflared install failed:", e, flush=True)
        return None


PUBLIC_URL = os.environ.get("PUBLIC_URL", "https://dexdev.ccwu.cc").rstrip("/")


def start_named_tunnel():
    """Use the fixed Cloudflare named tunnel domain. cloudflared must already be running
    as a service on this machine (sudo cloudflared service install <token>)."""
    global TUNNEL_URL
    TUNNEL_URL = PUBLIC_URL
    print(f"Cloudflare route for {PUBLIC_URL} must point to: http://localhost:{PORT}", flush=True)
    print("\n=== Admin login key (enter it in the app) ===")
    print(ADMIN_KEY)
    print("==============================================\n", flush=True)

    def republish():
        while True:
            publish_url(TUNNEL_URL)
            time.sleep(1800)

    threading.Thread(target=republish, daemon=True).start()
    return True


def start_tunnel():
    exe = shutil.which("cloudflared")
    if not exe and os.environ.get("AUTO_INSTALL_CLOUDFLARED") == "1":
        exe = install_cloudflared()
    if not exe:
        print("cloudflared not found. Install it with:\n"
              "  curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb -o cloudflared.deb\n"
              "  sudo dpkg -i cloudflared.deb\n"
              "then start run.py again.\n"
              "(Or set AUTO_INSTALL_CLOUDFLARED=1 to let run.py do this for you.)", flush=True)
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
        global TUNNEL_URL
        announced = False
        for line in p.stdout:
            m = re.search(r"https://[\w-]+\.trycloudflare\.com", line)
            if m and not announced:
                announced = True
                TUNNEL_URL = m.group(0)
                print("\n=== Admin login key (enter it in the app) ===")
                print(ADMIN_KEY)
                print("==============================================\n", flush=True)
                threading.Thread(target=republish, args=(TUNNEL_URL,), daemon=True).start()

    threading.Thread(target=watch, daemon=True).start()
    return True


if __name__ == "__main__":
    resolve_port()
    print("Mode:", "Docker (one container per bot)" if USE_DOCKER else
          "NO Docker found - bots run as plain processes (not isolated)", flush=True)
    threading.Thread(target=stats_loop, daemon=True).start()
    if os.environ.get("NO_TUNNEL") == "1":
        tunnel = False
    elif os.environ.get("NAMED_TUNNEL") == "1":
        tunnel = start_named_tunnel()    # fixed domain (cloudflared runs as a service)
    else:
        tunnel = start_tunnel()          # default: random trycloudflare.com quick tunnel
    # Named/quick tunnels reach us over localhost; only NO_TUNNEL listens on all interfaces.
    host = "0.0.0.0" if os.environ.get("NO_TUNNEL") == "1" else "127.0.0.1"
    if not tunnel:
        print(f"\nAdmin login key: {ADMIN_KEY}\nListening on port {PORT}\n")
    app.run(host=host, port=PORT, threaded=True)
