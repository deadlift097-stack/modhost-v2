#!/usr/bin/env python3
"""lualint: a quick static check for Mod Host Lua mods.

    python3 tools/lualint.py mods/my_mod/main.lua [more.lua ...]

It does not run Lua. It reads the file the way a tokenizer would (skipping strings and
comments) and reports the mistakes that are most common in practice: unbalanced blocks,
use of globals the sandbox removes, a second menu.register, and menu settings that break
the limits in docs/LUA_API.md. Exit status is 1 when any error was found.
"""
import re
import sys

OPENERS = {"function", "if", "do", "repeat"}
REMOVED = {"io", "package", "debug", "dofile", "loadfile", "load", "loadstring"}
EVENTS = {"key", "touch", "mouse", "attack", "tick"}
ALLOWED_OS = {"time", "clock", "date", "difftime"}
SETTING_TYPES = {"toggle", "slider", "float"}
KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


class Issue:
    def __init__(self, line, error, message):
        self.line, self.error, self.message = line, error, message

    def __str__(self):
        return "%s: line %d: %s" % ("error" if self.error else "warning", self.line, self.message)


class Token:
    def __init__(self, text, line, is_string=False):
        self.text, self.line, self.is_string = text, line, is_string

    def unquoted(self):
        t = self.text
        return t[1:-1] if self.is_string and len(t) >= 2 else t


def long_bracket_level(s, pos):
    """Level of a long bracket such as [==[ at pos, or -1."""
    if pos >= len(s) or s[pos] != "[":
        return -1
    i = pos + 1
    while i < len(s) and s[i] == "=":
        i += 1
    return i - pos - 1 if i < len(s) and s[i] == "[" else -1


def tokenize(s, issues):
    out, line, i, n = [], 1, 0, len(s)
    while i < n:
        c = s[i]
        if c == "\n":
            line += 1
            i += 1
        elif c.isspace():
            i += 1
        elif s.startswith("--", i):
            level = long_bracket_level(s, i + 2)
            if level >= 0:
                close = "]" + "=" * level + "]"
                end = s.find(close, i)
                stop = n if end < 0 else end
                line += s.count("\n", i, stop)
                if end < 0:
                    issues.append(Issue(line, True, "a long comment is never closed"))
                i = n if end < 0 else end + len(close)
            else:
                while i < n and s[i] != "\n":
                    i += 1
        elif c in "\"'":
            start, start_line, closed = i, line, False
            i += 1
            while i < n:
                d = s[i]
                if d == "\\":
                    i += 2
                elif d == c:
                    closed = True
                    i += 1
                    break
                elif d == "\n":
                    break
                else:
                    i += 1
            if not closed:
                issues.append(Issue(start_line, True, "a string is not closed on its line"))
            out.append(Token(s[start:min(i, n)], start_line, True))
        elif c == "[" and long_bracket_level(s, i) >= 0:
            level = long_bracket_level(s, i)
            close = "]" + "=" * level + "]"
            end = s.find(close, i)
            out.append(Token("[[...]]", line, True))
            line += s.count("\n", i, n if end < 0 else end)
            if end < 0:
                issues.append(Issue(line, True, "a long string is never closed"))
            i = n if end < 0 else end + len(close)
        elif c.isalpha() or c == "_":
            start = i
            while i < n and (s[i].isalnum() or s[i] == "_"):
                i += 1
            out.append(Token(s[start:i], line))
        elif c.isdigit():
            start = i
            while i < n and (s[i].isalnum() or s[i] == "."):
                i += 1
            out.append(Token(s[start:i], line))
        else:
            out.append(Token(c, line))
            i += 1
    return out


def check_blocks(tokens, issues):
    stack, in_loop_header = [], False
    for t in tokens:
        if t.is_string:
            continue
        w = t.text
        if w in ("for", "while"):
            in_loop_header = True  # the loop's own "do" does not open a second block
            stack.append(t)
        elif w == "do" and in_loop_header:
            in_loop_header = False
        elif w in OPENERS:
            stack.append(t)
        elif w == "end":
            if stack:
                stack.pop()
            else:
                issues.append(Issue(t.line, True, "'end' without a matching block"))
        elif w == "until":
            if stack and stack[-1].text == "repeat":
                stack.pop()
            else:
                issues.append(Issue(t.line, True, "'until' without a matching 'repeat'"))
    for t in stack:
        issues.append(Issue(t.line, True, "'%s' is never closed with 'end'" % t.text))


def check_globals(tokens, issues):
    for i, t in enumerate(tokens):
        if t.is_string or t.text not in REMOVED:
            continue
        after_dot = i > 0 and tokens[i - 1].text in (".", ":")
        is_field = i + 1 < len(tokens) and tokens[i + 1].text == "="
        if not after_dot and not is_field:
            issues.append(Issue(t.line, True, "'%s' does not exist in the Mod Host sandbox" % t.text))
    for i in range(len(tokens) - 2):
        if tokens[i].text == "os" and tokens[i + 1].text == "." and tokens[i + 2].text not in ALLOWED_OS:
            issues.append(Issue(tokens[i].line, True,
                                "os.%s is not available; only time, clock, date and difftime are" % tokens[i + 2].text))


def check_menu(tokens, issues):
    first, count = -1, 0
    for i in range(len(tokens) - 2):
        if [t.text for t in tokens[i:i + 3]] == ["menu", ".", "register"]:
            count += 1
            if first < 0:
                first = i
            if count == 2:
                issues.append(Issue(tokens[i].line, True, "menu.register can only be called once per mod"))
    if first < 0:
        return
    settings = 0
    for i in range(first, len(tokens) - 3):
        t = tokens[i]
        if not t.is_string and t.text == "key" and tokens[i + 1].text == "=" and tokens[i + 2].is_string:
            settings += 1
            key = tokens[i + 2].unquoted()
            if not KEY_RE.match(key):
                issues.append(Issue(t.line, True, "setting key '%s' may only contain letters, digits and '_' "
                                                  "and cannot start with a digit" % key))
    if settings > 64:
        issues.append(Issue(tokens[first].line, True, "a mod can have at most 64 settings"))
    for i in range(len(tokens) - 2):
        if tokens[i].text == "type" and tokens[i + 1].text == "=" and tokens[i + 2].is_string \
                and tokens[i + 2].unquoted() not in SETTING_TYPES:
            issues.append(Issue(tokens[i].line, True, 'setting type must be "toggle", "slider" or "float"'))


def check_events(tokens, issues):
    for i in range(len(tokens) - 3):
        t = tokens[i]
        if t.is_string or t.text != "on" or tokens[i + 1].text != "(" or not tokens[i + 2].is_string:
            continue
        name = tokens[i + 2].unquoted()
        if name not in EVENTS:
            issues.append(Issue(t.line, False, 'unknown event "%s"; events are key, touch, mouse, attack and tick' % name))
        elif name == "attack":
            issues.append(Issue(t.line, False,
                                'the attack event only fires when "attackEvent": true is set in Mod Host\'s config'))


def check(source):
    """Returns a list of Issue objects for a Lua source string."""
    issues = []
    tokens = tokenize(source, issues)
    check_blocks(tokens, issues)
    check_globals(tokens, issues)
    check_menu(tokens, issues)
    check_events(tokens, issues)
    return sorted(issues, key=lambda i: i.line)


def main(argv):
    if not argv:
        print("Usage: lualint.py <file.lua> [...]", file=sys.stderr)
        return 2
    errors = 0
    for path in argv:
        try:
            with open(path, encoding="utf-8") as f:
                issues = check(f.read())
        except OSError as e:
            print("%s: %s" % (path, e.strerror or e), file=sys.stderr)
            errors += 1
            continue
        for issue in issues:
            print("%s: %s" % (path, issue))
            errors += issue.error
        if not issues:
            print("%s: ok" % path)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
