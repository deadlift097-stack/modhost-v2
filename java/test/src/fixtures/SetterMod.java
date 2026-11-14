package fixtures;

import org.modhost.ModContext;

public class SetterMod {
    public static volatile String order = "";

    public void setContext(ModContext ctx) {
        order += "context;";
    }

    public void init() {
        order += "init;";
    }
}
