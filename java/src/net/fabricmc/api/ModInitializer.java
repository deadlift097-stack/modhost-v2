package net.fabricmc.api;

/**
 * Compatibility stub. Lets the entry classes of Fabric mods load inside Mod Host so that
 * code which does not touch Minecraft classes can run. It is not the real Fabric API.
 */
public interface ModInitializer {
    void onInitialize();
}
