"""Config files: every command-line option can live in a YAML or JSON file instead.

    python -m lichen                     uses ./lichen.yaml (or .yml / .json) if present
    python -m lichen --config me.yaml    uses me.yaml
    python -m lichen --init              writes a commented starter lichen.yaml

Options given on the command line override the file.
"""

import json
from pathlib import Path

DEFAULT_FILES = ("lichen.yaml", "lichen.yml", "lichen.json")

# option -> (type, help). The CLI builds its flags from this too, so the two can't drift apart.
OPTIONS: dict[str, tuple[type, str]] = {
    "name": (str, "display name (not unique; your key is your identity)"),
    "relay": (list, "hub(s) to receive messages through: HOST:PORT, ws://... or wss://... (repeatable; "
                    "several hubs = redundancy). Use this unless you've opened a port yourself. Also used as bootstraps."),
    "bootstrap": (list, "addresses of known nodes / hubs to fetch phonebooks from (repeatable)"),
    "port": (int, "port to listen on, TCP and UDP (default 9000; ignored for relay-only nodes)"),
    "host": (str, "address others can reach you at directly, e.g. your public IP if you've opened a port. "
                  "Default 127.0.0.1, or none at all when using --relay"),
    "bind": (str, "interface to listen on (default: same as host; 0.0.0.0 for all)"),
    "url": (str, "public address to advertise instead of host:port, e.g. wss://chat.example.com for a "
                 "hub behind Cloudflare Tunnel or another HTTPS proxy"),
    "stun": (list, "STUN servers for finding your public UDP address (HOST:PORT, repeatable). "
                   "Default: Google and Cloudflare. Empty list disables."),
    "data": (str, "state directory (default: data/<name>)"),
    "downloads": (str, "where accepted files are saved (default: data/<name>/downloads)"),
    "max_file_mb": (float, "refuse file offers bigger than this many MB (default 100)"),
    "auto_download_mb": (float, "auto-accept files up to this many MB from trusted contacts (default 0 = always ask)"),
    "ui": (str, "tui (full-screen terminal app), cli (plain prompt), or auto (default: tui in an "
                "interactive terminal, cli when input is piped)"),
    "vault_mb": (float, "hubs: MB of encrypted sync data for people's other devices to hold (default 256 on "
                        "a node that accepts connections; 0 = hold none)"),
}

# What travels with an identity export (--export): your settings, minus the ones that describe *this*
# machine (where it listens, where its files live), which another device would get wrong.
PORTABLE = ("name", "relay", "bootstrap", "stun", "ui", "max_file_mb", "auto_download_mb")

TEMPLATE = """\
# Lichen config. Run with:  lichen   (or: lichen --config this-file.yaml)
# Anything here can also be given as a command-line flag, which wins over the file.

name: {name}

# --- Joining someone else's hub (most people) -----------------------------------------------
# The hub's address: HOST:PORT for a hub with an open port, or wss://... for one behind
# Cloudflare Tunnel / an HTTPS proxy. For redundancy, list several hubs:
#   relay:
#     - wss://chat.example.com
#     - other-hub.example.org:9000
relay: {relay}

# --- Running a hub (uncomment) ---------------------------------------------------------------
# A hub needs to be reachable. Either open/forward a port (TCP + UDP) and set host:
#   port: 9000
#   host: 203.0.113.7        # your public IP or a (dynamic) DNS name
#   bind: 0.0.0.0
# ...or keep it on localhost behind Cloudflare Tunnel (cloudflared -> http://localhost:9000):
#   port: 9000
#   url: wss://chat.example.com
# Link hubs together by bootstrapping from each other:
#   bootstrap:
#     - wss://other-hub.example.org

# --- Advanced --------------------------------------------------------------------------------
# stun:                      # where to learn your public UDP address for direct connections
#   - stun.l.google.com:19302
#   - stun.cloudflare.com:3478
# data: data/{name}          # where your keys, phonebook and messages are stored
# ui: auto                   # tui, cli, or auto (tui in a real terminal)
# downloads: ~/Downloads/lichen   # where accepted files go
# max_file_mb: 100           # refuse bigger offers
# auto_download_mb: 0        # >0: auto-accept files up to this size from your contacts
# vault_mb: 256              # hubs: MB of encrypted sync data for people's other devices (0 = none)
"""


class ConfigError(ValueError):
    pass


def find_default(cwd: Path | None = None) -> Path | None:
    for name in DEFAULT_FILES:
        path = (cwd or Path.cwd()) / name
        if path.exists():
            return path
    return None


def load(path: Path) -> dict:
    return parse(path.read_text(encoding="utf-8"), path.suffix.lower() == ".json", str(path))


def parse(text: str, is_json: bool = False, where: str = "config") -> dict:
    if is_json:
        data = json.loads(text)
    else:
        try:
            import yaml
        except ImportError as e:
            raise ConfigError("reading YAML configs needs PyYAML: pip install pyyaml (or use a .json config)") from e
        data = yaml.safe_load(text) or {}
    if not isinstance(data, dict):
        raise ConfigError(f"{where}: expected a mapping of option: value")
    return validate(data, where)


def validate(data: dict, where: str = "config") -> dict:
    out = {}
    for key, value in data.items():
        key = str(key).replace("-", "_")
        if key not in OPTIONS:
            raise ConfigError(f"{where}: unknown option {key!r} (known: {', '.join(OPTIONS)})")
        kind = OPTIONS[key][0]
        if value is None:
            continue
        if kind is list:
            value = [value] if isinstance(value, str) else value
            if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
                raise ConfigError(f"{where}: {key} should be a list of strings")
        elif kind in (int, float):
            if isinstance(value, bool) or not isinstance(value, (int, float)) or (kind is int and not isinstance(value, int)):
                raise ConfigError(f"{where}: {key} should be a number")
            value = kind(value)
        else:
            value = str(value)
        out[key] = value
    return out


def render(settings: dict, header: str = "") -> str:
    """A lichen.yaml for the portable options in settings (JSON scalars and flow lists are valid YAML,
    so this needs no YAML library). Options that describe this machine are left out."""
    lines = [header.rstrip() + "\n\n"] if header else []
    for key in PORTABLE:
        value = settings.get(key)
        if value is None or value == []:
            continue
        if key in ("relay", "bootstrap", "stun") and isinstance(value, list):
            lines.append(f"{key}:\n" + "".join(f"  - {json.dumps(v)}\n" for v in value))
        else:
            lines.append(f"{key}: {json.dumps(value) if isinstance(value, str) else value}\n")
    return "".join(lines)


def write_template(path: Path, name: str = "yourname", relay: str = "chat.example.com:9000") -> None:
    if path.exists():
        raise ConfigError(f"{path} already exists; not overwriting it")
    path.write_text(TEMPLATE.format(name=name, relay=relay), encoding="utf-8")
