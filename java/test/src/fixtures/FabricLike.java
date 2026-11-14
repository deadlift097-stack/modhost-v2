package fixtures;

import net.fabricmc.api.ModInitializer;

public class FabricLike implements ModInitializer {
    public static volatile boolean initialized;

    @Override
    public void onInitialize() {
        initialized = true;
    }
}
