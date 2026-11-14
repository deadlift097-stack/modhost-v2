package fixtures;

import org.modhost.Mod;
import org.modhost.ModContext;

public class HostMod implements Mod {
    @Override
    public void onLoad(ModContext ctx) {
        ctx.log("hello");
    }

    @Override
    public void onUnload() {}
}
