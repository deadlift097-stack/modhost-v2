package fixtures;

/** A class that knows nothing about Mod Host. */
public class PlainMod {
    public static volatile String events = "";

    public void onEnable() {
        events += "enable;";
    }

    public void onDisable() {
        events += "disable;";
    }
}
