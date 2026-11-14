package org.modhost.jar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Everything the inspector learned about one jar. Plain data; fields are filled by {@link JarInspector}. */
public final class JarInfo {
    public final File file;
    public String id;
    public String name;
    public String version = "0.0.0";
    public String author = "";
    public String description = "";
    public String mainClass;
    public ModKind kind = ModKind.UNKNOWN;
    public int api = 1;

    public boolean hasDex;
    public boolean hasClassFiles;
    public boolean hasDescriptor;
    /** True when classes in the jar refer to Minecraft Java Edition classes. */
    public boolean usesMinecraftJava;

    /** All classes found (from .class files or dex files). */
    public final List<ClassInfo> classes = new ArrayList<>();
    /** Plain information lines for the log. */
    public final List<String> notes = new ArrayList<>();
    /** Reasons the jar cannot be loaded. Empty when it can. */
    public final List<String> problems = new ArrayList<>();

    public JarInfo(File file) {
        this.file = file;
        String n = file.getName();
        this.id = IdUtil.sanitize(n.toLowerCase().endsWith(".jar") ? n.substring(0, n.length() - 4) : n);
        this.name = this.id;
    }

    public ClassInfo findClass(String dottedName) {
        for (ClassInfo c : classes) {
            if (c.name.equals(dottedName)) {
                return c;
            }
        }
        return null;
    }

    /** Can this jar be handed to the loader on a device (android = true) or a desktop JVM? */
    public boolean loadable(boolean android) {
        if (!problems.isEmpty() || mainClass == null) {
            return false;
        }
        return !android || hasDex;
    }

    /** First problem, or an empty string. */
    public String firstProblem() {
        return problems.isEmpty() ? "" : problems.get(0);
    }

    /** One line such as "my_mod 1.0 by you: main com.example.Main (Fabric metadata)". */
    public String summary() {
        StringBuilder b = new StringBuilder();
        b.append(id).append(' ').append(version);
        if (!author.isEmpty()) {
            b.append(" by ").append(author);
        }
        b.append(": ");
        b.append(mainClass == null ? "no entry point" : "main " + mainClass);
        b.append(" (").append(kind.label).append(')');
        return b.toString();
    }
}
