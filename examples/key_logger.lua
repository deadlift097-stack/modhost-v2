-- key_logger.lua: logs key presses and can swallow one key. Android key codes: 111 = Escape,
-- 24 = Volume up, 25 = Volume down.
menu.register{
  id = "key_logger",
  name = "Key Logger",
  description = "Logs key codes; optionally consumes the volume-down key.",
  settings = {
    { key = "logKeys", label = "Log key presses", type = "toggle", default = true },
    { key = "blockVolumeDown", label = "Consume volume down", type = "toggle", default = false },
    { key = "sensitivity", label = "Touch log spacing (ms)", type = "slider", default = 500, min = 100, max = 5000 },
  },
}

local lastTouch = 0

on("key", function(code, down, ch)
  if settings.logKeys and down then
    local shown = ch ~= 0 and utf8.char(ch) or "-"
    log.info("key " .. code .. " (" .. shown .. ")")
  end
  if settings.blockVolumeDown and code == 25 then
    return true -- consumed: the game never sees this key
  end
  return false
end)

on("touch", function(action, pointer, x, y)
  local now = time.ms()
  if settings.logKeys and now - lastTouch >= settings.sensitivity then
    lastTouch = now
    log.info(string.format("touch action %d at %.0f, %.0f", action, x, y))
  end
  return false
end)
