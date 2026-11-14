#!/usr/bin/env python3
"""newluamod: create a ready-to-edit Lua mod for Mod Host.

    python3 tools/newluamod.py my_mod --name "My Mod" --events key,tick \\
        --toggle loud:Loud:false --slider volume:Volume:7:0:10

Writes mods/<id>/main.lua and mod.json (or <id>.lua with --single-file). The result passes
tools/lualint.py. Copy it into the Mod Host `mods` folder on the device and restart the game.
"""
import argparse
import json
import os
import re
import sys

ALL_EVENTS = ["key", "touch", "mouse", "attack", "tick"]
KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
ID_RE = re.compile(r"^[a-z0-9_.-]+$")


class SpecError(ValueError):
    pass


def lua_string(text):
    out = text.replace("\\", "\\\\").replace('"', '\\"').replace("\r", "").replace("\n", "\\n")
    return '"%s"' % out


def pretty_name(mod_id):
    return " ".join(p.capitalize() for p in re.split(r"[_-]+", mod_id) if p) or mod_id


def parse_setting(kind, text):
    """Turns 'key:Label:default:min:max' into a dict; raises SpecError on bad input."""
    parts = text.split(":")
    if len(parts) < 2:
        raise SpecError("--%s needs key:Label[...], got '%s'" % (kind, text))
    key, label = parts[0], parts[1]
    if not KEY_RE.match(key):
        raise SpecError("setting key '%s' may only contain letters, digits and '_' and cannot start with a digit" % key)
    if kind == "toggle":
        default = (parts[2] if len(parts) > 2 else "false").lower()
        if default not in ("true", "false"):
            raise SpecError("toggle default must be true or false")
        return {"type": kind, "key": key, "label": label, "default": default}
    if len(parts) != 5:
        raise SpecError("--%s needs key:Label:default:min:max, got '%s'" % (kind, text))
    try:
        default, low, high = (float(p) for p in parts[2:])
    except ValueError:
        raise SpecError("default, min and max must be numbers in '%s'" % text)
    if low > high or not low <= default <= high:
        raise SpecError("need min <= default <= max in '%s'" % text)
    if kind == "slider" and any(v != int(v) for v in (default, low, high)):
        raise SpecError("slider values must be whole numbers in '%s'" % text)
    return {"type": kind, "key": key, "label": label, "default": parts[2], "min": parts[3], "max": parts[4]}


def validate(spec):
    if not spec["id"] or not ID_RE.match(spec["id"]):
        raise SpecError("the id may only use lowercase letters, digits, '_', '-' and '.'")
    if len(spec["settings"]) > 64:
        raise SpecError("a mod can have at most 64 settings")
    seen = set()
    for s in spec["settings"]:
        if s["key"] in seen:
            raise SpecError("setting key '%s' is used twice" % s["key"])
        seen.add(s["key"])


def event_block(event, spec):
    toggle = next((s["key"] for s in spec["settings"] if s["type"] == "toggle"), None)
    guard = "  if not settings.%s then return false end\n" % toggle if toggle else ""
    if event == "key":
        return ('on("key", function(code, down, ch)\n' + guard +
                '  if down then log.info("key " .. code) end\n'
                '  return false -- return true to consume the key\nend)\n')
    if event == "touch":
        return ('on("touch", function(action, pointer, x, y)\n' + guard +
                '  return false -- return true to consume the touch\nend)\n')
    if event == "mouse":
        return ('on("mouse", function(button, down)\n' + guard +
                '  return false -- return true to consume the click\nend)\n')
    if event == "attack":
        return ('-- needs "attackEvent": true in Mod Host\'s config\n'
                'on("attack", function(info)\n  log.info("attack at " .. info.time_ms)\nend)\n')
    return ('local ticks = 0\non("tick", function(dt_ms)\n  ticks = ticks + 1\n'
            '  if ticks % 200 == 0 then log.info("still running (" .. ticks .. " ticks)") end\nend)\n')


def generate(spec):
    lines = ["-- %s: %s" % (spec["id"], spec["description"]),
             "-- Reference: docs/LUA_API.md. Check this file with tools/lualint.py before copying it to the device.",
             "", "menu.register{",
             "  id = %s," % lua_string(spec["id"]),
             "  name = %s," % lua_string(spec["name"]),
             "  description = %s," % lua_string(spec["description"])]
    if spec["settings"]:
        lines.append("  settings = {")
        for s in spec["settings"]:
            row = "    { key = %s, label = %s, type = %s, default = %s" % (
                lua_string(s["key"]), lua_string(s["label"]), lua_string(s["type"]), s["default"])
            if "min" in s:
                row += ", min = %s, max = %s" % (s["min"], s["max"])
            lines.append(row + " },")
        lines.append("  },")
    lines += ["}", "", 'log.info("loaded " .. mod.name .. " v" .. mod.version)']
    text = "\n".join(lines) + "\n"
    for event in spec["events"]:
        text += "\n" + event_block(event, spec)
    if not spec["events"]:
        text += ('\n-- Add handlers with on("key", fn), on("touch", fn), on("mouse", fn),\n'
                 '-- on("attack", fn) or on("tick", fn). Timers: timer.after(ms, fn), timer.every(ms, fn).\n')
    return text


def mod_json(spec):
    return json.dumps({"id": spec["id"], "name": spec["name"], "version": "1.0.0", "author": spec["author"],
                       "description": spec["description"], "enabled": True}, indent=2) + "\n"


def build_parser():
    p = argparse.ArgumentParser(description="Create a Lua mod for Mod Host.")
    p.add_argument("id")
    p.add_argument("--name")
    p.add_argument("--author", default="you")
    p.add_argument("--description", default="A Mod Host Lua mod")
    p.add_argument("--events", default="", help="comma list of: " + ",".join(ALL_EVENTS))
    p.add_argument("--toggle", action="append", default=[], metavar="key:Label[:default]")
    p.add_argument("--slider", action="append", default=[], metavar="key:Label:default:min:max")
    p.add_argument("--float", action="append", default=[], metavar="key:Label:default:min:max")
    p.add_argument("--single-file", action="store_true")
    p.add_argument("--out", default=".")
    p.add_argument("--force", action="store_true")
    return p


def make_spec(args):
    events = []
    for e in filter(None, (x.strip().lower() for x in args.events.split(","))):
        if e not in ALL_EVENTS:
            raise SpecError("unknown event '%s' (use %s)" % (e, ", ".join(ALL_EVENTS)))
        if e not in events:
            events.append(e)
    settings = [parse_setting("toggle", t) for t in args.toggle]
    settings += [parse_setting("slider", t) for t in args.slider]
    settings += [parse_setting("float", t) for t in args.float]
    spec = {"id": args.id, "name": args.name or pretty_name(args.id), "author": args.author,
            "description": args.description, "events": events, "settings": settings}
    validate(spec)
    return spec


def main(argv):
    args = build_parser().parse_args(argv)
    try:
        spec = make_spec(args)
    except SpecError as e:
        print("error: %s" % e, file=sys.stderr)
        return 2
    target = os.path.join(args.out, spec["id"] + ".lua") if args.single_file \
        else os.path.join(args.out, spec["id"], "main.lua")
    if os.path.exists(target) and not args.force:
        print("error: %s already exists (use --force to replace it)" % target, file=sys.stderr)
        return 1
    os.makedirs(os.path.dirname(os.path.abspath(target)), exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        f.write(generate(spec))
    print("wrote", target)
    if not args.single_file:
        json_path = os.path.join(os.path.dirname(target), "mod.json")
        with open(json_path, "w", encoding="utf-8") as f:
            f.write(mod_json(spec))
        print("wrote", json_path)
    print("copy it into the mods folder of Mod Host and restart the game")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
