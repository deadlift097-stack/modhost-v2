# jar2mod: Java Edition mod -> Mod Host mod scaffold

    python3 tools/jar2mod.py SomeMod.jar -o converted            # Java project (default)
    python3 tools/jar2mod.py SomeMod.jar -o converted --lang lua # Lua mod instead

Needs only Python 3.8+. Writes `converted/<modid>/` with

* `java-project/` (or `lua-mod/`): the scaffold. For Java it also contains `build_mod.yml`, a
  one-file GitHub workflow that builds the mod jar (paste it into an empty repo's
  `.github/workflows/build.yml` and run it).
* `report.md`: mod info, every mixin and whether it maps to a Mod Host event, every setting found.
* `bytecode_notes.txt`: readable bytecode of the mod's hooks, to help port the logic.
* `assets/`: language files and textures, for reference.

What it can do: read Fabric, Quilt, Forge and NeoForge metadata; find settings kept in enum
constants (name, label, default); recognise hooks into attacks, mouse, keyboard and ticks.

What it cannot do: translate the mod's logic. Bedrock has none of Java Minecraft's classes, so
every handler is a TODO to be written again against the Mod Host API. Hooks into rendering,
networking, sound, the world or entities have no Mod Host equivalent yet and are listed as
unsupported. Settings stored in other ways than enums are not detected yet; send me the jar
and I can add the pattern.

## Other tools

| Tool | What it does |
|------|--------------|
| `tools/newluamod.py` | Creates a Lua mod (`main.lua` + `mod.json`) with a menu and the handlers you choose. |
| `tools/lualint.py` | Checks a Lua mod without running it: unbalanced blocks, sandbox-removed globals, menu rules. |
| `tools/test_lua_tools.py` | Tests for the two tools above (`python3 tools/test_lua_tools.py`). |
| `tools/jarscan_cli.cpp` | Native check of jar files, the same code Mod Host runs at start-up. |
| `java/tools/.../AddJar.java` | Makes an ordinary jar loadable on a device. See `docs/JAR_MODS.md`. |

`jar2mod.py` (above) ports a Java Edition mod by hand; `AddJar` instead runs a jar as it is.
