-- timer_demo.lua: timers and storage. Counts how many times the game was started and logs a
-- heartbeat every few seconds while the module toggle is on.
menu.register{
  id = "timer_demo",
  name = "Timer Demo",
  description = "Logs a heartbeat and remembers how many starts it has seen.",
  settings = {
    { key = "heartbeat", label = "Heartbeat (seconds)", type = "slider", default = 10, min = 2, max = 60 },
    { key = "verbose", label = "Verbose log", type = "toggle", default = false },
  },
}

local starts = storage.get("starts", 0) + 1
storage.set("starts", starts)
log.info("start number " .. starts)

local beats = 0
timer.every(settings.heartbeat * 1000, function()
  beats = beats + 1
  if settings.verbose then
    log.info("heartbeat " .. beats .. " at " .. time.ms() .. " ms")
  elseif beats % 6 == 0 then
    log.info(beats .. " heartbeats so far")
  end
end)

timer.after(2000, function()
  log.info("two seconds after loading, " .. mod.name .. " is " .. (mod.enabled and "enabled" or "disabled"))
end)
