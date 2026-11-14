# Adding jar mods without rewriting them

Mod Host's Java API (`org.modhost.Mod`) is optional. Any `.jar` that contains a `classes.dex`
can be dropped in, and the host works out how to start it. This page explains what is detected,
how to get a jar into the right shape, and what still cannot work.

## The short version

1. Get a jar. If it has a `classes.dex` inside, skip to step 3.
2. If it only has `.class` files, convert it once (see "Converting a jar" below).
3. Put it in `java_mods/` (or in the `inbox/` folder next to it) and restart the game.
4. Read `mod_host/jar_report.txt` to see what loaded, what was skipped, and why.

    /storage/emulated/0/Android/media/<launcher package>/mod_host/java_mods/
    /storage/emulated/0/Android/media/<launcher package>/mod_host/inbox/

Jars in `inbox/` are checked at start-up. Ones that can run are moved to `java_mods/`; the rest
stay put and the log says why.

## How the host finds the entry point

For each jar the host (`org.modhost.jar.JarInspector`) looks at, in this order:

| Source | What it gives |
|--------|---------------|
| `modhost-mod.properties` | `main`, `id`, `name`, `version`, `author`, `description`, `api`. A Mod Host mod. |
| `Mod-Host-Main` in `META-INF/MANIFEST.MF` | The class to start. |
| `fabric.mod.json` | Id, name, version, authors, and the `main`, then `client`, then `server` entrypoint. |
| `quilt.mod.json` | Same, from `quilt_loader`. |
| `plugin.yml` | `main`, `name`, `version`, `author`. |
| `Main-Class` in the manifest | The class to start. |
| Nothing | A guess from the classes: one that implements `org.modhost.Mod` or a Fabric initializer, or has `onLoad`, `onEnable`, `init`, `start`, `main`... A class named `...Mod`, `...Plugin` or `Main` scores higher. Mixin classes and inner classes are never chosen. |

Classes are read without running them, from `.class` files and from `classes.dex` (all
`classesN.dex` files). If nothing scores high enough the jar is skipped and the report tells you
to name the class yourself.

## How the entry class is started

If it implements `org.modhost.Mod` it is used as is. Otherwise `ModAdapter` calls the first of
these that exists:

1. `onLoad(ModContext)`
2. `onInitialize()`, `onInitializeClient()`, `onInitializeServer()` (Fabric style)
3. a public no-argument `onEnable`, `onLoad`, `init`, `initialize`, `start`, `setup`, `enable` or `run`
4. a static `main(String[])`, run on its own thread

If the class has `setContext(ModContext)` or `setModContext(ModContext)` it is called first, so a
plain class can still reach logging, timers, the menu and events. On unload the adapter calls
`onUnload`, `onDisable`, `stop`, `shutdown` or `destroy`, or `close()` for an `AutoCloseable`.

The Fabric interfaces `ModInitializer`, `ClientModInitializer` and `DedicatedServerModInitializer`
exist in the host as empty stubs so that a Fabric entry class can load at all.

## Converting a jar

A device cannot run `.class` files; they must be turned into `classes.dex` by the Android `d8`
tool. `AddJar` does that and writes the `modhost-mod.properties` from whatever metadata it finds.

**From a phone, with GitHub:** put the jar in the `incoming/` folder of your copy of this
repository (or run the "Convert jar" workflow and give the path). The workflow converts every jar
in `incoming/` and attaches the results as the `converted-mods` artifact in the Actions tab.

**On a computer** (JDK 8+, Android SDK with build-tools and a platform):

    javac --release 8 -d build/classes $(find java/src java/tools -name '*.java')
    java -cp build/classes org.modhost.tools.AddJar SomeMod.jar
    java -cp build/classes org.modhost.tools.AddJar SomeMod.jar --main com.example.Main --id some_mod
    java -cp build/classes org.modhost.tools.AddJar SomeMod.jar --inspect

Options: `-o file`, `--out-dir dir`, `--id`, `--name`, `--main`, `--version`, `--author`,
`--description`, `--d8`, `--android-jar`, `--api-jar`, `--min-api`, `--inspect`. `d8` and
`android.jar` are found through `ANDROID_HOME` unless you pass them. Pass `--api-jar
modhost-api.jar` when the mod refers to `org.modhost` or the Fabric stubs.

Classes under `org/modhost/`, signature files and `module-info.class` are removed first (the host
supplies its own `org.modhost`). A jar that already has a `classes.dex` only gets a descriptor.

To check a jar quickly on a computer without Java, build the small native tool:

    g++ -std=c++20 -Isrc tools/jarscan_cli.cpp src/mod/JarScan.cpp -o jarscan && ./jarscan my_mod.jar

## What cannot work

Be realistic about Java Edition mods. Fabric, Quilt and Forge mods are written against Minecraft
Java's classes, and Bedrock has none of them.

* Code that touches `net.minecraft` or `com.mojang` classes fails with `NoClassDefFoundError`.
  The inspector warns when it sees such references.
* Mixins patch Java Edition code and are ignored.
* Fabric API, Forge events, Bukkit/Spigot APIs and similar are not present.

What does work: a jar whose own logic runs on plain Java (timers, files, networking, maths), using
Mod Host's `ModContext` for input events, the in-game menu, timers and storage. A mod that throws
5 times is stopped. **Java mods are not sandboxed**: install only jars you trust.

## The report file

`mod_host/jar_report.txt` is rewritten at every start:

    [OK]      my_mod.jar
              my_mod 1.0 by you: main com.example.MyMod (Mod Host descriptor)
    [SKIPPED] tool.jar
              the jar has no classes.dex. Convert it first with the AddJar tool (see docs/JAR_MODS.md)
              fix: Convert it first: java org.modhost.tools.AddJar <jar> ...

## Tests

    javac -d build/classes $(find java/src java/tools java/test/src -name '*.java')
    java -cp build/classes org.modhost.jar.SelfTest
    g++ -std=c++20 -Isrc tests/jarscan_test.cpp src/mod/JarScan.cpp -o jarscan_test && ./jarscan_test

The workflow runs both on every push.
