# Mod Host (LeviLauncher Android mod)

Runs small mods inside Minecraft Bedrock on LeviLauncher Android, in two languages:

* **Lua** (sandboxed): start with `docs/LUA_QUICKSTART.md`, reference in `docs/LUA_API.md`, examples in `examples/`
* **Java** (`.jar` with `classes.dex`, not sandboxed): `docs/JAVA_MODS.md`. Jars do not have to use the
  Mod Host API: see `docs/JAR_MODS.md` for dropping in ordinary jars.

Both get the same things: input events, an (unconfirmed) attack event, an in-game menu,
timers, storage and logging. See each doc for what is and is not possible yet.

`tools/newluamod.py` creates a Lua mod, `tools/lualint.py` checks one, and `java/tools/.../AddJar.java`
turns an ordinary jar into one the host can load. `tools/jar2mod.py` turns a Java Edition mod's jar into a scaffold for either language (menu,
settings and event wiring; the logic itself has to be ported by hand). See `tools/README.md`.

## Build

Push the project to GitHub (the included workflow builds the `.levipack`), or run
`./scripts/package.ps1 -Abi arm64-v8a` with the Android NDK installed.

* `java/src/org/modhost`: the Java API and runtime. The workflow compiles it, converts it with
  `d8` and embeds the result in the native library (`src/mod/BridgeDex.inc`).
* `scripts/patch_sdk.py` adds the `onKeybind` member that the launcher's menu code expects to the
  pinned SDK header. Without it `registerModule` crashes the game.
* Lua 5.4.6 is downloaded from lua.org at configure time and compiled as C++.

## Layout

    src/mod/LuaHost.cpp     sandboxed Lua runtime, API, events, timers, menu registration
    src/mod/JavaBridge.cpp  JNI bridge to the Java host
    src/mod/MyMod.cpp       mod lifecycle, input callbacks, optional attack hook
    src/mod/Config.h        typed config (limits, javaMods, attackEvent, ...)
    src/mod/JarScan.cpp     native jar check and the inbox folder
    java/src/org/modhost    Java API and runtime
    java/src/org/modhost/jar  jar inspector, entry-point detection, adapter, report
    java/tools              AddJar (convert a jar for the device)
    java/test, tests        self-tests for the Java and C++ jar code
    tools                   Lua mod generator and checker, jar2mod scaffold tool
