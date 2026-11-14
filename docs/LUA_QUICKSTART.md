# Lua mods: use one, or make your own

Lua mods are the lightest way to add something to Mod Host. They are sandboxed, need no build
step and are plain text files. The full reference is `LUA_API.md`; this page gets you started.

## Use a ready-made mod

1. Copy a `.lua` file from `examples/` to the device:

        /storage/emulated/0/Android/media/<launcher package>/mod_host/mods/

   One file is enough (`mods/hello.lua`). A folder works too (`mods/my_mod/main.lua`, with an
   optional `mod.json`).
2. Restart the game. The mod appears in the Mod Host menu with the toggles and sliders it declares.
3. Read `mod_host/mod_host.log` if something does not work. Errors are written there.

Ready-made examples:

| File | Shows |
|------|-------|
| `examples/hello.lua` | a menu, a timer and a key handler |
| `examples/hit_counter.lua` | the attack event, storage |
| `examples/timer_demo.lua` | repeating timers, storage between runs, `mod.enabled` |
| `examples/key_logger.lua` | consuming a key, key and touch events, `string.format` |

## Make your own

Generate a skeleton, edit it, check it:

    python3 tools/newluamod.py my_mod --name "My Mod" --events key,tick \
        --toggle "loud:Loud mode:false" --slider volume:Volume:7:0:10
    python3 tools/lualint.py my_mod/main.lua

`newluamod.py` writes `my_mod/main.lua` and `my_mod/mod.json` with the menu, the settings and an
empty handler for each event you asked for (`key`, `touch`, `mouse`, `attack`, `tick`). Setting
formats are `toggle key:Label[:default]`, `slider key:Label:default:min:max` (whole numbers) and
`float key:Label:default:min:max`. Add `--single-file` for one `my_mod.lua` instead of a folder.

`lualint.py` reads the file without running it and reports unbalanced `end`s, globals the sandbox
removes (`io`, `package`, `debug`, `load`, `dofile`, most of `os`), a second `menu.register`, bad
setting keys or types, and unknown events.

## A complete mod by hand

    menu.register{
      id = "greeter",
      name = "Greeter",
      description = "Says hello after a delay.",
      settings = {
        { key = "delay",  label = "Delay (s)", type = "slider", default = 3, min = 1, max = 30 },
        { key = "shout",  label = "Shout",     type = "toggle", default = false },
      },
    }

    timer.after(settings.delay * 1000, function()
      local text = "Hello from " .. mod.name
      log.info(settings.shout and text:upper() or text)
    end)

Things worth knowing:

* Call `menu.register` once, at the top of the file. Setting keys use letters, digits and `_`.
* `settings.<key>` always holds the current value; it is remembered between runs.
* Handlers must be quick (a single call may run 50 ms). Return `true` from `key`, `touch` and
  `mouse` handlers to consume the event.
* `storage.set("k", v)` and `storage.get("k", default)` keep booleans, numbers and strings.
* `on("attack")` only fires when `"attackEvent": true` is set in Mod Host's config.
* There is no HUD, sound, world or entity access yet; see "What is NOT possible yet" in `LUA_API.md`.

## Lua or Java?

Choose Lua for small input-driven tweaks: no tools, safe, one file. Choose a jar (see
`JAR_MODS.md`) when you need threads, files, networking or an existing Java library.
