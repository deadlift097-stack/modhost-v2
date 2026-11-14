package org.modhost.jar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.modhost.ModContext;
import fixtures.FabricLike;
import fixtures.MainOnly;
import fixtures.PlainMod;
import fixtures.SetterMod;
import org.modhost.tools.AddJar;

/**
 * Plain-Java checks for the jar loading code (no test framework needed).
 * Run from the project root: see the "Test" step of .github/workflows/build.yml.
 */
public final class SelfTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String FIX = "fixtures/";
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    private SelfTest() {}

    public static void main(String[] args) throws Exception {
        json();
        classFiles();
        inspector();
        adapter();
        dex();
        addJar();
        report();
        System.out.println(passed + " checks passed, " + failures.size() + " failed");
        for (String f : failures) {
            System.out.println("FAILED: " + f);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------ helpers

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failures.add(what);
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(ok, what + " (expected <" + expected + "> but was <" + actual + ">)");
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = SelfTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing test resource " + path);
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            return b.toByteArray();
        }
    }

    private static byte[] classBytes(String simple) throws IOException {
        return resource(FIX + simple + ".class");
    }

    /** Builds a jar in a temp file from name/bytes pairs (alternating). */
    private static File jar(String name, Object... pairs) throws IOException {
        File f = File.createTempFile("jar_" + name, ".jar");
        f.deleteOnExit();
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f.toPath()))) {
            for (int i = 0; i < pairs.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) pairs[i]));
                Object v = pairs[i + 1];
                z.write(v instanceof byte[] ? (byte[]) v : ((String) v).getBytes(UTF8));
                z.closeEntry();
            }
        }
        return f;
    }

    private static ModContext fakeContext(final List<String> log) {
        InvocationHandler h = (proxy, method, a) -> {
            String n = method.getName();
            if (n.equals("log") || n.equals("warn") || n.equals("error")) {
                log.add(n + ":" + a[0]);
            }
            Class<?> r = method.getReturnType();
            return r == boolean.class ? Boolean.FALSE : r == int.class ? Integer.valueOf(0) : null;
        };
        return (ModContext) Proxy.newProxyInstance(SelfTest.class.getClassLoader(), new Class<?>[] {ModContext.class}, h);
    }

    // ------------------------------------------------------------ tests

    private static void json() {
        Object o = Json.parse("{\"a\": [1, 2.5, \"x\\u0041\"], \"b\": {\"c\": true, \"d\": null}, /* note */ \"e\": \"y\"}");
        eq("xA", Json.asArray(Json.path(o, "a")).get(2), "json unicode escape");
        eq(Boolean.TRUE, Json.path(o, "b.c"), "json nested path");
        eq("y", Json.string(o, "e"), "json comment skipped");
        eq(null, Json.path(o, "b.missing"), "json missing key");
        check(Json.tryParse("{\"a\": }") == null, "json rejects a missing value");
        check(Json.tryParse("[1, 2") == null, "json rejects an unterminated array");
        check(Json.tryParse("{} extra") == null, "json rejects trailing text");
        eq(null, Json.tryParse(null), "json null input");
    }

    private static void classFiles() throws Exception {
        ClassFileReader.Result r = ClassFileReader.read(classBytes("PlainMod"));
        eq("fixtures.PlainMod", r.info.name, "class name");
        check(r.info.hasMethod("onEnable"), "class has onEnable");
        check(r.info.instantiable(), "PlainMod is instantiable");
        check(!r.usesMinecraftJava, "PlainMod does not use Minecraft");
        check(ClassFileReader.read(classBytes("McTouch")).usesMinecraftJava, "McTouch mentions net/minecraft");
        check(ClassFileReader.read(classBytes("FabricLike")).info.implementsInterface("net.fabricmc.api.ModInitializer"),
                "FabricLike implements ModInitializer");
        try {
            ClassFileReader.read(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
            check(false, "garbage is rejected");
        } catch (IOException expected) {
            check(true, "garbage is rejected");
        }
    }

    private static void inspector() throws Exception {
        // 1. Mod Host descriptor
        File a = jar("a", JarInspector.DESCRIPTOR, "main=fixtures.HostMod\nid=My Mod\nname=Mine\nversion=2\napi=1\n",
                FIX + "HostMod.class", classBytes("HostMod"));
        JarInfo ia = JarInspector.inspect(a);
        eq(ModKind.MODHOST, ia.kind, "descriptor kind");
        eq("my_mod", ia.id, "descriptor id is sanitised");
        eq("Mine", ia.name, "descriptor name");
        check(ia.problems.isEmpty(), "descriptor jar has no problems: " + ia.problems);
        check(ia.hasDescriptor && ia.hasClassFiles && !ia.hasDex, "descriptor jar flags");
        check(!ia.loadable(true) && ia.loadable(false), "class-only jar loads on a JVM but not on a device");

        // 2. Fabric metadata, entrypoint with a member reference, author objects
        String fabric = "{\"schemaVersion\":1,\"id\":\"cool-mod\",\"version\":\"1.2\",\"name\":\"Cool\","
                + "\"authors\":[\"Ann\",{\"name\":\"Bob\"}],\"entrypoints\":{\"main\":[\"" + "fixtures.FabricLike::run\"]}}";
        JarInfo ib = JarInspector.inspect(jar("b", "fabric.mod.json", fabric, FIX + "FabricLike.class", classBytes("FabricLike")));
        eq(ModKind.FABRIC, ib.kind, "fabric kind");
        eq("fixtures.FabricLike", ib.mainClass, "fabric main class without member");
        eq("Ann, Bob", ib.author, "fabric authors");
        eq("cool-mod", ib.id, "fabric id");

        // 3. Manifest Main-Class
        JarInfo ic = JarInspector.inspect(jar("c", "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\nMain-Class: fixtures.MainOnly\r\n\r\n",
                FIX + "MainOnly.class", classBytes("MainOnly")));
        eq(ModKind.MANIFEST, ic.kind, "manifest kind");
        eq("fixtures.MainOnly", ic.mainClass, "manifest main");

        // 4. No metadata at all: guess, and do not pick the helper or the Mixin-looking class
        JarInfo id = JarInspector.inspect(jar("d", FIX + "Helper.class", classBytes("Helper"), FIX + "HostMod.class",
                classBytes("HostMod"), FIX + "McTouch.class", classBytes("McTouch")));
        eq(ModKind.GUESSED, id.kind, "guessed kind");
        eq("fixtures.HostMod", id.mainClass, "guess prefers the Mod implementation");
        check(id.usesMinecraftJava, "Minecraft use is detected across classes");

        // 5. plugin.yml
        JarInfo ie = JarInspector.inspect(jar("e", "plugin.yml", "name: Greeter\nmain: fixtures.PlainMod\n"
                + "version: '3.1'\nauthor: Zed\n", FIX + "PlainMod.class", classBytes("PlainMod")));
        eq(ModKind.PLUGIN_YML, ie.kind, "plugin.yml kind");
        eq("3.1", ie.version, "plugin.yml quoted version");
        eq("greeter", ie.id, "plugin.yml id");

        // 6. nothing usable
        JarInfo ig = JarInspector.inspect(jar("g", FIX + "Helper.class", classBytes("Helper")));
        check(ig.mainClass == null && !ig.problems.isEmpty(), "helper-only jar has no entry point");
        check(JarInspector.inspect(jar("h", "readme.txt", "hi")).problems.get(0).contains("no code"), "jar without code");

        // 7. main class named in metadata but missing
        JarInfo ih = JarInspector.inspect(jar("i", JarInspector.DESCRIPTOR, "main=does.not.Exist\n", FIX + "HostMod.class",
                classBytes("HostMod")));
        check(ih.problems.get(0).contains("not inside"), "missing main class is reported");

        // 8. unsafe names and unreadable files
        check(!JarInspector.inspect(jar("j", "../evil.txt", "x")).problems.isEmpty(), "path traversal is rejected");
        check(!JarInspector.inspect(File.createTempFile("notajar", ".jar")).problems.isEmpty(), "empty file is rejected");
        check(!JarInspector.inspect(new File("/no/such/file.jar")).problems.isEmpty(), "missing file is rejected");

        // 9. bundled API classes are noted and not treated as the mod
        JarInfo ik = JarInspector.inspect(jar("k", "org/modhost/Mod.class", classBytes("HostMod"), FIX + "HostMod.class",
                classBytes("HostMod")));
        boolean noted = false;
        for (String n : ik.notes) {
            noted |= n.contains("its own org.modhost classes");
        }
        check(noted, "bundled org.modhost classes are noted");

        // scoring and parsers
        eq("a", JarInspector.manifestKeys("Mod-Host-Main: a\nMain-Class: b\n")[0], "Mod-Host-Main wins in the parser");
        Map<String, String> y = JarInspector.simpleYaml("name: X\n  nested: no\n# c\nauthors: [A, B]\n");
        eq("A, B", y.get("authors"), "yaml list text");
        check(!y.containsKey("nested"), "yaml ignores indented lines");
    }

    private static void adapter() throws Exception {
        List<String> log = new ArrayList<>();
        ModContext ctx = fakeContext(log);

        PlainMod.events = "";
        ModAdapter plain = new ModAdapter(PlainMod.class, "plain");
        plain.onLoad(ctx);
        plain.onUnload();
        eq("enable;disable;", PlainMod.events, "onEnable then onDisable");
        eq("fixtures.PlainMod.onEnable()", ModAdapter.describeStart(PlainMod.class), "describeStart for onEnable");

        new ModAdapter(FabricLike.class, "fabric").onLoad(ctx);
        check(FabricLike.initialized, "Fabric initializer is called");

        new ModAdapter(MainOnly.class, "main").onLoad(ctx);
        long deadline = System.currentTimeMillis() + 3000;
        while (!MainOnly.ran && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        check(MainOnly.ran, "static main runs on its own thread");

        SetterMod.order = "";
        new ModAdapter(SetterMod.class, "setter").onLoad(ctx);
        eq("context;init;", SetterMod.order, "context is injected before init");

        check(ModAdapter.describeStart(fixtures.Helper.class) == null, "no start method is reported as null");
        try {
            new ModAdapter(fixtures.Helper.class, "helper").onLoad(ctx);
            check(true, "class without start method only warns");
        } catch (Exception e) {
            check(false, "class without start method should not throw: " + e);
        }
    }

    private static void dex() throws Exception {
        File f = new File(System.getProperty("fixtures.dir", "java/test/fixtures"), "hello-java-mod.dex");
        if (!f.isFile()) {
            System.out.println("skipping dex checks: " + f + " not found");
            return;
        }
        byte[] bytes = Files.readAllBytes(f.toPath());
        List<ClassInfo> classes = DexReader.readClasses(bytes);
        ClassInfo hello = null;
        for (ClassInfo c : classes) {
            if (c.name.equals("example.hello.HelloJavaMod")) {
                hello = c;
            }
        }
        check(hello != null, "dex lists the example mod class");
        if (hello != null) {
            check(hello.implementsInterface("org.modhost.Mod"), "dex: example implements org.modhost.Mod");
            check(hello.hasMethod("onLoad"), "dex: example has onLoad");
            check(hello.hasPublicNoArgConstructor, "dex: example has a public no-argument constructor");
        }
        JarInfo info = JarInspector.inspect(jar("dexjar", "classes.dex", bytes));
        eq("example.hello.HelloJavaMod", info.mainClass, "a dex-only jar is guessed correctly");
        check(info.loadable(true), "a dex jar loads on a device");
        try {
            DexReader.readClasses(new byte[200]);
            check(false, "bad dex is rejected");
        } catch (IOException expected) {
            check(true, "bad dex is rejected");
        }
    }

    private static void addJar() throws Exception {
        File in = jar("conv", "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n", "META-INF/X.SF", "sig", "module-info.class", "x",
                "org/modhost/Mod.class", "api", FIX + "PlainMod.class", classBytes("PlainMod"), "data/a.txt", "res");
        File filtered = File.createTempFile("filtered", ".jar");
        filtered.deleteOnExit();
        java.lang.reflect.Method m = AddJar.class.getDeclaredMethod("filterJar", File.class, File.class);
        m.setAccessible(true);
        eq(Integer.valueOf(1), m.invoke(null, in, filtered), "filterJar keeps only the mod's class files");
        JarInfo after = JarInspector.inspect(filtered);
        check(after.findClass("org.modhost.Mod") == null, "filterJar drops bundled API classes");

        eq(Integer.valueOf(0), Integer.valueOf(AddJar.compareVersions("34.0.0", "34")), "equal versions");
        check(AddJar.compareVersions("android-9", "android-34") < 0, "numeric version order");
        check(AddJar.compareVersions("34.0.1", "34.0.0") > 0, "patch version order");

        JarInfo info = new JarInfo(new File("x.jar"));
        info.mainClass = "a.B";
        info.description = "a=b: c\nd";
        info.kind = ModKind.GUESSED;
        java.lang.reflect.Method d = AddJar.class.getDeclaredMethod("descriptor", JarInfo.class);
        d.setAccessible(true);
        java.util.Properties p = new java.util.Properties();
        p.load(new ByteArrayInputStream((byte[]) d.invoke(null, info)));
        eq("a.B", p.getProperty("main"), "descriptor main");
        eq("a=b: c\nd", p.getProperty("description"), "descriptor escapes special characters");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = AddJar.run(new String[] {"--inspect", in.getPath()}, new PrintStream(out), new PrintStream(err));
        eq(Integer.valueOf(0), Integer.valueOf(code), "inspect exits 0 for a loadable jar: " + err);
        check(out.toString().contains("PlainMod"), "inspect prints the classes");
        eq(Integer.valueOf(2), Integer.valueOf(AddJar.run(new String[] {"--bogus"}, new PrintStream(out), new PrintStream(err))),
                "unknown option exits 2");
        eq(Integer.valueOf(1), Integer.valueOf(AddJar.run(new String[] {"--inspect", jar("empty", "a.txt", "x").getPath()},
                new PrintStream(out), new PrintStream(err))), "inspect exits 1 for a jar without code");

        // a jar that already has a dex only needs the descriptor
        File dexIn = new File(System.getProperty("fixtures.dir", "java/test/fixtures"), "hello-java-mod.dex");
        if (dexIn.isFile()) {
            File src = jar("hasdex", "classes.dex", Files.readAllBytes(dexIn.toPath()));
            File dest = File.createTempFile("converted", ".jar");
            dest.deleteOnExit();
            int rc = AddJar.run(new String[] {src.getPath(), "-o", dest.getPath(), "--id", "hello_x"}, new PrintStream(out),
                    new PrintStream(err));
            eq(Integer.valueOf(0), Integer.valueOf(rc), "dex jar converts without d8: " + err);
            JarInfo res = JarInspector.inspect(dest);
            check(res.hasDescriptor && res.hasDex, "converted jar has a descriptor and the dex");
            eq("hello_x", res.id, "converted jar keeps the id override");
            eq(ModKind.MODHOST, res.kind, "converted jar now reads as a Mod Host jar");
        }
    }

    private static void report() throws Exception {
        JarReport r = new JarReport();
        JarInfo ok = JarInspector.inspect(jar("rep", FIX + "HostMod.class", classBytes("HostMod")));
        r.loaded(ok, "adapted");
        JarInfo bad = JarInspector.inspect(jar("rep2", FIX + "Helper.class", classBytes("Helper")));
        r.skipped(bad, "could not find an entry point");
        eq(Integer.valueOf(1), Integer.valueOf(r.loadedCount()), "report counts loaded jars");
        String text = r.render();
        check(text.contains("1 of 2 jar(s) loaded") && text.contains("[SKIPPED]") && text.contains("fix:"), "report text");
        check(new JarReport().render().contains("No .jar files"), "empty report text");
    }
}
