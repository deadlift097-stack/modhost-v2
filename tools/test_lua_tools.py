#!/usr/bin/env python3
"""Tests for lualint.py and newluamod.py. Run: python3 tools/test_lua_tools.py"""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lualint  # noqa: E402
import newluamod  # noqa: E402


def messages(source):
    return [i.message for i in lualint.check(source)]


def has(source, part):
    return any(part in m for m in messages(source))


class LintTests(unittest.TestCase):
    def test_clean_script(self):
        self.assertEqual([], messages("local x = 1\nif x then print('a') end\nfor i = 1, 3 do end\n"))

    def test_block_balance(self):
        self.assertTrue(has("function f()\n  if true then\n  end\n", "never closed"))
        self.assertTrue(has("end", "without a matching block"))
        self.assertEqual([], messages("repeat local a = 1 until a == 1\nwhile false do end\n"))
        self.assertTrue(has("until x", "without a matching 'repeat'"))

    def test_comments_and_strings_are_skipped(self):
        self.assertEqual([], messages('-- if without end\nlocal s = "if"\n'))
        self.assertEqual([], messages("--[[ long\ncomment ]] local ok = [[\nlong string end]]\n"))
        self.assertTrue(has("local s = 'open\n", "not closed"))
        self.assertTrue(has("--[[ never ends", "never closed"))

    def test_sandbox_globals(self):
        self.assertTrue(has("local f = io.open('x')", "'io'"))
        self.assertTrue(has("os.execute('ls')", "os.execute"))
        self.assertEqual([], messages("local t = os.time()\nlocal c = os.clock()\n"))
        self.assertEqual([], messages("local t = { load = 1 }\nt.load = 2\n"))

    def test_menu_rules(self):
        self.assertTrue(has("menu.register{}\nmenu.register{}\n", "only be called once"))
        self.assertTrue(has('menu.register{ settings = { { key = "bad-key", type = "toggle" } } }', "bad-key"))
        self.assertTrue(has('menu.register{ settings = { { key = "k", type = "colour" } } }', "setting type"))
        many = ",".join('{ key = "k%d", type = "toggle" }' % i for i in range(65))
        self.assertTrue(has("menu.register{ settings = { %s } }" % many, "at most 64"))

    def test_events(self):
        self.assertTrue(has('on("keypress", function() end)', "unknown event"))
        self.assertTrue(has('on("attack", function() end)', "attackEvent"))
        self.assertEqual([], messages('on("key", function() return false end)'))

    def test_example_files_pass(self):
        examples = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "examples")
        for name in sorted(os.listdir(examples)):
            if name.endswith(".lua"):
                with open(os.path.join(examples, name), encoding="utf-8") as f:
                    errors = [i for i in lualint.check(f.read()) if i.error]
                self.assertEqual([], [str(e) for e in errors], name)


class NewModTests(unittest.TestCase):
    def run_tool(self, *args):
        err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            code = newluamod.main(list(args))
        return code, err.getvalue()

    def test_generated_mod_passes_lint(self):
        with tempfile.TemporaryDirectory() as d:
            code, err = self.run_tool("demo_mod", "--name", 'Demo "Mod"', "--events", "key,tick,attack,touch,mouse",
                                      "--toggle", "loud:Loud mode:true", "--slider", "volume:Volume:7:0:10",
                                      "--float", "scale:Scale:0.5:0:1", "--out", d)
            self.assertEqual(0, code, err)
            main = os.path.join(d, "demo_mod", "main.lua")
            with open(main, encoding="utf-8") as f:
                lua = f.read()
            self.assertIn('Demo \\"Mod\\"', lua)
            self.assertEqual([], [str(i) for i in lualint.check(lua) if i.error])
            with open(os.path.join(d, "demo_mod", "mod.json"), encoding="utf-8") as f:
                self.assertEqual("demo_mod", json.load(f)["id"])

    def test_existing_files_are_kept(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(0, self.run_tool("one", "--single-file", "--out", d)[0])
            self.assertTrue(os.path.isfile(os.path.join(d, "one.lua")))
            self.assertEqual(1, self.run_tool("one", "--single-file", "--out", d)[0])
            self.assertEqual(0, self.run_tool("one", "--single-file", "--force", "--out", d)[0])

    def test_bad_input_is_rejected(self):
        for args in (["Bad Id"], ["x", "--slider", "k:L:5:6:2"], ["x", "--toggle", "1k:L"], ["x", "--events", "jump"],
                     ["x", "--slider", "k:L:1.5:0:10"], ["x", "--toggle", "k:A", "--toggle", "k:B"],
                     ["x", "--toggle", "k:A:maybe"]):
            self.assertEqual(2, self.run_tool(*args)[0], args)


if __name__ == "__main__":
    unittest.main()
