package org.modhost.jar;

/** Where a jar's entry point and metadata were found. */
public enum ModKind {
    /** modhost-mod.properties: a mod written for Mod Host. */
    MODHOST("Mod Host descriptor"),
    /** Mod-Host-Main or Main-Class in META-INF/MANIFEST.MF. */
    MANIFEST("jar manifest"),
    /** fabric.mod.json (a Fabric mod for Java Edition). */
    FABRIC("Fabric metadata"),
    /** quilt.mod.json (a Quilt mod for Java Edition). */
    QUILT("Quilt metadata"),
    /** plugin.yml (a Bukkit or Spigot style plugin). */
    PLUGIN_YML("plugin.yml"),
    /** No metadata; the host guessed from the classes in the jar. */
    GUESSED("guessed from the classes"),
    /** Nothing usable was found. */
    UNKNOWN("unknown");

    public final String label;

    ModKind(String label) {
        this.label = label;
    }
}
