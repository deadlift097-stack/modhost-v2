package org.modhost.jar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Looks inside a jar (without loading any of its code) and works out what it is and how the
 * host can start it. This is what lets a jar be dropped in without being rewritten for the
 * Mod Host API: if there is no modhost-mod.properties the inspector falls back to the jar
 * manifest, Fabric or Quilt metadata, plugin.yml, and finally a guess from the classes.
 */
public final class JarInspector {
    public static final String DESCRIPTOR = "modhost-mod.properties";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_ENTRY_BYTES = 24 * 1024 * 1024;
    private static final int MAX_CLASS_ENTRIES = 30000;
    private static final Pattern DEX_NAME = Pattern.compile("classes\\d*\\.dex");
    private static final String[] MC_MARKERS = {"net/minecraft/", "com/mojang/"};

    private JarInspector() {}

    public static JarInfo inspect(File jar) {
        JarInfo info = new JarInfo(jar);
        if (!jar.isFile()) {
            info.problems.add("file not found");
            return info;
        }
        try (ZipFile zip = new ZipFile(jar)) {
            scan(zip, info);
        } catch (IOException e) {
            info.problems.add("not a readable jar (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
            info.problems.add("could not inspect the jar (" + e + ")");
        }
        return info;
    }

    // ------------------------------------------------------------ scanning

    private static void scan(ZipFile zip, JarInfo info) throws IOException {
        Properties descriptor = null;
        String manifestMain = null;
        String modhostMain = null;
        Object fabric = null;
        Object quilt = null;
        Map<String, String> pluginYml = null;
        int mixinConfigs = 0;
        int classEntries = 0;
        boolean bundledApi = false;

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.isDirectory()) {
                continue;
            }
            String n = e.getName();
            if (n.contains("..") || n.startsWith("/")) {
                info.problems.add("unsafe entry name: " + n);
                return;
            }
            if (n.equals(DESCRIPTOR)) {
                descriptor = new Properties();
                try (InputStream in = zip.getInputStream(e)) {
                    descriptor.load(in);
                }
            } else if (n.equals("META-INF/MANIFEST.MF")) {
                String[] keys = manifestKeys(readText(zip, e));
                modhostMain = keys[0];
                manifestMain = keys[1];
            } else if (n.equals("fabric.mod.json")) {
                fabric = Json.tryParse(readText(zip, e));
                if (fabric == null) {
                    info.notes.add("fabric.mod.json could not be parsed");
                }
            } else if (n.equals("quilt.mod.json")) {
                quilt = Json.tryParse(readText(zip, e));
            } else if (n.equals("plugin.yml")) {
                pluginYml = simpleYaml(readText(zip, e));
            } else if (n.endsWith(".mixins.json") || n.endsWith(".mixin.json")) {
                mixinConfigs++;
            } else if (DEX_NAME.matcher(n).matches()) {
                info.hasDex = true;
                byte[] dex = readBytes(zip, e);
                try {
                    info.classes.addAll(DexReader.readClasses(dex));
                } catch (IOException ex) {
                    info.notes.add(n + " could not be read (" + ex.getMessage() + ")");
                }
                if (containsAscii(dex, "Lnet/minecraft/") || containsAscii(dex, "Lcom/mojang/")) {
                    info.usesMinecraftJava = true;
                }
            } else if (n.endsWith(".class") && !n.startsWith("META-INF/") && !n.equals("module-info.class")) {
                info.hasClassFiles = true;
                if (n.startsWith("org/modhost/")) {
                    bundledApi = true;
                    continue;
                }
                if (++classEntries > MAX_CLASS_ENTRIES) {
                    continue;
                }
                try {
                    ClassFileReader.Result r = ClassFileReader.read(readBytes(zip, e));
                    info.classes.add(r.info);
                    if (r.usesMinecraftJava) {
                        info.usesMinecraftJava = true;
                    }
                } catch (IOException ex) {
                    info.notes.add(n + " skipped (" + ex.getMessage() + ")");
                }
            }
        }
        info.hasDescriptor = descriptor != null;

        if (bundledApi) {
            info.notes.add("the jar contains its own org.modhost classes; the host provides them and uses its own copy");
        }
        if (mixinConfigs > 0) {
            info.notes.add(mixinConfigs + " mixin config(s) found. Mixins patch Java Edition code and are ignored here");
        }
        if (info.usesMinecraftJava) {
            info.notes.add("the code refers to Minecraft Java Edition classes (net/minecraft, com/mojang). "
                    + "Those do not exist on Bedrock, so anything that touches them will fail");
        }

        if (descriptor != null) {
            applyDescriptor(info, descriptor);
        } else if (modhostMain != null) {
            info.kind = ModKind.MANIFEST;
            info.mainClass = modhostMain;
        } else if (fabric != null && applyFabric(info, fabric)) {
            // done
        } else if (quilt != null && applyQuilt(info, quilt)) {
            // done
        } else if (pluginYml != null && applyPluginYml(info, pluginYml)) {
            // done
        } else if (manifestMain != null) {
            info.kind = ModKind.MANIFEST;
            info.mainClass = manifestMain;
        }

        if (info.mainClass == null) {
            String guess = guessEntryPoint(info.classes);
            if (guess != null) {
                info.mainClass = guess;
                info.kind = ModKind.GUESSED;
                info.notes.add("no metadata names an entry point; using " + guess);
            }
        }
        validate(info);
    }

    private static void validate(JarInfo info) {
        if (info.mainClass == null) {
            if (info.classes.isEmpty() && !info.hasDex && !info.hasClassFiles) {
                info.problems.add("the jar has no code (no classes.dex and no .class files)");
            } else {
                info.problems.add("could not find an entry point. Add a modhost-mod.properties with "
                        + "main=<class>, or give --main to the AddJar tool");
            }
            return;
        }
        if (!info.classes.isEmpty() && info.findClass(info.mainClass) == null) {
            info.problems.add("main class " + info.mainClass + " is not inside the jar");
        }
    }

    // ------------------------------------------------------------ metadata sources

    private static void applyDescriptor(JarInfo info, Properties p) {
        info.kind = ModKind.MODHOST;
        info.mainClass = blankToNull(p.getProperty("main"));
        info.id = IdUtil.sanitize(p.getProperty("id", info.id));
        info.name = p.getProperty("name", IdUtil.prettyName(info.id));
        info.version = p.getProperty("version", info.version);
        info.author = p.getProperty("author", "");
        info.description = p.getProperty("description", "");
        try {
            info.api = Integer.parseInt(p.getProperty("api", "1").trim());
        } catch (NumberFormatException e) {
            info.api = 1;
        }
        if (info.mainClass == null) {
            info.notes.add(DESCRIPTOR + " has no 'main' class; looking for one");
        }
    }

    private static boolean applyFabric(JarInfo info, Object root) {
        Map<String, Object> m = Json.asObject(root);
        if (m == null) {
            return false;
        }
        String id = Json.string(root, "id");
        if (id != null) {
            info.id = IdUtil.sanitize(id);
        }
        info.name = orElse(Json.string(root, "name"), IdUtil.prettyName(info.id));
        info.version = orElse(Json.string(root, "version"), info.version);
        info.description = orElse(Json.string(root, "description"), "");
        info.author = authors(Json.asArray(m.get("authors")));
        info.kind = ModKind.FABRIC;
        Object eps = Json.path(root, "entrypoints");
        String main = firstEntrypoint(eps, "main");
        if (main == null) {
            main = firstEntrypoint(eps, "client");
        }
        if (main == null) {
            main = firstEntrypoint(eps, "server");
        }
        info.mainClass = main;
        info.notes.add("Fabric mod for Java Edition. The Fabric loader and Minecraft are not present; "
                + "stub initializer interfaces are provided so the entry class can load");
        return true;
    }

    private static boolean applyQuilt(JarInfo info, Object root) {
        Object loader = Json.path(root, "quilt_loader");
        if (Json.asObject(loader) == null) {
            return false;
        }
        String id = Json.string(root, "quilt_loader.id");
        if (id != null) {
            info.id = IdUtil.sanitize(id);
        }
        info.version = orElse(Json.string(root, "quilt_loader.version"), info.version);
        info.name = orElse(Json.string(root, "quilt_loader.metadata.name"), IdUtil.prettyName(info.id));
        info.description = orElse(Json.string(root, "quilt_loader.metadata.description"), "");
        info.kind = ModKind.QUILT;
        Object eps = Json.path(root, "quilt_loader.entrypoints");
        String main = firstEntrypoint(eps, "init");
        if (main == null) {
            main = firstEntrypoint(eps, "client_init");
        }
        info.mainClass = main;
        info.notes.add("Quilt mod for Java Edition. The Quilt loader and Minecraft are not present");
        return true;
    }

    private static boolean applyPluginYml(JarInfo info, Map<String, String> y) {
        String main = y.get("main");
        if (main == null) {
            return false;
        }
        info.kind = ModKind.PLUGIN_YML;
        info.mainClass = main;
        if (y.get("name") != null) {
            info.id = IdUtil.sanitize(y.get("name"));
            info.name = y.get("name");
        }
        info.version = orElse(y.get("version"), info.version);
        info.author = orElse(y.get("author"), "");
        info.description = orElse(y.get("description"), "");
        info.notes.add("plugin.yml found. The plugin's server API (Bukkit, Spigot) is not available here");
        return true;
    }

    /** Accepts "pkg.Class", "pkg.Class::member" and {"value": "..."}; returns the class part. */
    private static String firstEntrypoint(Object eps, String key) {
        Map<String, Object> m = Json.asObject(eps);
        if (m == null || !m.containsKey(key)) {
            return null;
        }
        Object v = m.get(key);
        List<Object> list = Json.asArray(v);
        Object first = list != null ? (list.isEmpty() ? null : list.get(0)) : v;
        if (first instanceof Map) {
            first = Json.asObject(first).get("value");
        }
        if (!(first instanceof String)) {
            return null;
        }
        String s = ((String) first).trim();
        int ref = s.indexOf("::");
        return blankToNull(ref >= 0 ? s.substring(0, ref) : s);
    }

    private static String authors(List<Object> list) {
        if (list == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (Object a : list) {
            String name = a instanceof String ? (String) a
                    : a instanceof Map ? Json.string(a, "name") : null;
            if (name != null) {
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(name);
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------ entry point guessing

    /** Picks the most plausible entry class, or null when nothing scores high enough. */
    static String guessEntryPoint(List<ClassInfo> classes) {
        ClassInfo best = null;
        int bestScore = 0;
        for (ClassInfo c : classes) {
            int s = score(c);
            if (s > bestScore || (s == bestScore && best != null && c.name.length() < best.name.length())) {
                best = c;
                bestScore = s;
            }
        }
        return bestScore >= 30 && best != null ? best.name : null;
    }

    static int score(ClassInfo c) {
        if (!c.isPublic || c.isAbstract || c.isInterface || c.name.indexOf('$') >= 0) {
            return -1;
        }
        String lower = c.name.toLowerCase();
        if (lower.contains("mixin") || lower.contains(".mixins.")) {
            return -1;
        }
        boolean hasMain = c.hasMethod("main");
        if (!c.hasPublicNoArgConstructor && !hasMain) {
            return -1;
        }
        int s = 0;
        if (c.implementsInterface("org.modhost.Mod")) {
            s += 100;
        }
        if (c.implementsInterface("net.fabricmc.api.ModInitializer")) {
            s += 90;
        }
        if (c.implementsInterface("net.fabricmc.api.ClientModInitializer")) {
            s += 85;
        }
        if (c.implementsInterface("net.fabricmc.api.DedicatedServerModInitializer")) {
            s += 40;
        }
        if (c.hasMethod("onLoad") || c.hasMethod("onEnable") || c.hasMethod("onInitialize")) {
            s += 30;
        }
        if (c.hasMethod("init") || c.hasMethod("initialize") || c.hasMethod("start") || c.hasMethod("setup")) {
            s += 10;
        }
        if (hasMain) {
            s += 15;
        }
        String simple = c.name.substring(c.name.lastIndexOf('.') + 1);
        if (simple.endsWith("Mod") || simple.endsWith("Plugin") || simple.equals("Main") || simple.endsWith("Entry")) {
            s += 10;
        }
        return s;
    }

    // ------------------------------------------------------------ small readers

    /** Returns {Mod-Host-Main, Main-Class} from a manifest; either may be null. */
    static String[] manifestKeys(String text) {
        String modhost = null;
        String main = null;
        for (String raw : text.split("\r?\n")) {
            int colon = raw.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = raw.substring(0, colon).trim();
            String value = raw.substring(colon + 1).trim();
            if (key.equalsIgnoreCase("Mod-Host-Main")) {
                modhost = blankToNull(value);
            } else if (key.equalsIgnoreCase("Main-Class")) {
                main = blankToNull(value);
            }
        }
        return new String[] {modhost, main};
    }

    /** Reads flat "key: value" lines; lists such as [a, b] are kept as text with brackets removed. */
    static Map<String, String> simpleYaml(String text) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String raw : text.split("\r?\n")) {
            if (raw.startsWith(" ") || raw.startsWith("\t") || raw.startsWith("#")) {
                continue;
            }
            int colon = raw.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String value = raw.substring(colon + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.startsWith("[") && value.endsWith("]")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            out.put(raw.substring(0, colon).trim(), value);
        }
        return out;
    }

    static boolean containsAscii(byte[] data, String needle) {
        byte[] n = needle.getBytes(UTF8);
        outer:
        for (int i = 0; i + n.length <= data.length; i++) {
            for (int k = 0; k < n.length; k++) {
                if (data[i + k] != n[k]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static String readText(ZipFile zip, ZipEntry e) throws IOException {
        return new String(readBytes(zip, e), UTF8);
    }

    static byte[] readBytes(ZipFile zip, ZipEntry e) throws IOException {
        if (e.getSize() > MAX_ENTRY_BYTES) {
            throw new IOException(e.getName() + " is too large");
        }
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(e.getSize() > 0 ? (int) e.getSize() : 4096);
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_ENTRY_BYTES) {
                    throw new IOException(e.getName() + " is too large");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** Names of the metadata files this inspector understands, for documentation and tests. */
    public static List<String> knownMetadataFiles() {
        List<String> l = new ArrayList<>();
        l.add(DESCRIPTOR);
        l.add("META-INF/MANIFEST.MF");
        l.add("fabric.mod.json");
        l.add("quilt.mod.json");
        l.add("plugin.yml");
        return l;
    }
}
