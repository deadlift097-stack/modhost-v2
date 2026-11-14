package org.modhost.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.modhost.jar.ClassInfo;
import org.modhost.jar.IdUtil;
import org.modhost.jar.JarInfo;
import org.modhost.jar.JarInspector;

/**
 * Turns an ordinary jar into one Mod Host can load on a device, without editing its code.
 *
 * <pre>
 *   java -cp build/classes org.modhost.tools.AddJar SomeMod.jar
 *   java -cp build/classes org.modhost.tools.AddJar SomeMod.jar --main com.example.Main --id some_mod
 * </pre>
 *
 * It inspects the jar, writes a {@code modhost-mod.properties} from whatever metadata it
 * finds (or from the options), converts the class files to {@code classes.dex} with the
 * Android {@code d8} tool, and writes {@code <id>-modhost.jar}. Jars that already contain a
 * dex only get the descriptor added. Copy the result to the java_mods folder.
 */
public final class AddJar {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private AddJar() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Parsed command line. */
    static final class Options {
        final List<File> inputs = new ArrayList<>();
        File output;
        File outDir;
        String id;
        String name;
        String main;
        String version;
        String author;
        String description;
        String d8;
        String androidJar;
        String apiJar;
        String minApi = "26";
        boolean inspectOnly;
        boolean help;
    }

    static Options parse(String[] args) {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h":
                case "--help":
                    o.help = true;
                    break;
                case "--inspect":
                    o.inspectOnly = true;
                    break;
                case "-o":
                case "--output":
                    o.output = new File(need(args, ++i, a));
                    break;
                case "--out-dir":
                    o.outDir = new File(need(args, ++i, a));
                    break;
                case "--id":
                    o.id = need(args, ++i, a);
                    break;
                case "--name":
                    o.name = need(args, ++i, a);
                    break;
                case "--main":
                    o.main = need(args, ++i, a);
                    break;
                case "--version":
                    o.version = need(args, ++i, a);
                    break;
                case "--author":
                    o.author = need(args, ++i, a);
                    break;
                case "--description":
                    o.description = need(args, ++i, a);
                    break;
                case "--d8":
                    o.d8 = need(args, ++i, a);
                    break;
                case "--android-jar":
                    o.androidJar = need(args, ++i, a);
                    break;
                case "--api-jar":
                    o.apiJar = need(args, ++i, a);
                    break;
                case "--min-api":
                    o.minApi = need(args, ++i, a);
                    break;
                default:
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option " + a);
                    }
                    o.inputs.add(new File(a));
            }
        }
        return o;
    }

    private static String need(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    static String usage() {
        return "Usage: AddJar <input.jar>... [options]\n"
                + "  -o <file>            output jar (single input only)\n"
                + "  --out-dir <dir>      where to write the results (default: next to each input)\n"
                + "  --id/--name/--main/--version/--author/--description <text>\n"
                + "                       override what the jar's metadata says\n"
                + "  --d8 <path>          the d8 tool (default: $D8, then Android build-tools)\n"
                + "  --android-jar <path> android.jar of a platform (default: newest in the SDK)\n"
                + "  --api-jar <path>     modhost-api.jar, needed when the mod refers to org.modhost\n"
                + "                       or to the Fabric stub interfaces\n"
                + "  --min-api <n>        minimum Android API for dex output (default 26)\n"
                + "  --inspect            only print what was found; write nothing\n";
    }

    /** Runs the tool and returns the process exit code. Separate from main so tests can call it. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println(usage());
            return 2;
        }
        if (o.help || o.inputs.isEmpty()) {
            (o.help ? out : err).println(usage());
            return o.help ? 0 : 2;
        }
        if (o.output != null && o.inputs.size() > 1) {
            err.println("error: -o works with a single input; use --out-dir for several");
            return 2;
        }
        int failures = 0;
        for (File in : o.inputs) {
            try {
                if (!convert(in, o, out)) {
                    failures++;
                }
            } catch (IOException | RuntimeException e) {
                err.println(in.getName() + ": " + e.getMessage());
                failures++;
            }
        }
        return failures == 0 ? 0 : 1;
    }

    private static boolean convert(File in, Options o, PrintStream out) throws IOException {
        JarInfo info = JarInspector.inspect(in);
        applyOverrides(info, o);
        out.println(in.getName() + ": " + info.summary());
        for (String n : info.notes) {
            out.println("  note: " + n);
        }
        // An explicit --main replaces a "no entry point" problem but must still exist in the jar.
        if (o.main != null) {
            info.problems.removeIf(p -> p.contains("entry point") || p.contains("is not inside"));
            if (!info.classes.isEmpty() && info.findClass(o.main) == null) {
                info.problems.add("--main " + o.main + " is not inside the jar");
            }
        }
        if (!info.problems.isEmpty()) {
            for (String p : info.problems) {
                out.println("  problem: " + p);
            }
            if (!o.inspectOnly) {
                return false;
            }
        }
        if (info.usesMinecraftJava) {
            out.println("  warning: refers to Minecraft Java classes; expect failures when those are used");
        }
        if (o.inspectOnly) {
            describeClasses(info, out);
            return info.problems.isEmpty();
        }

        File target = o.output != null ? o.output
                : new File(o.outDir != null ? o.outDir : orDot(in.getAbsoluteFile().getParentFile()),
                        info.id + "-modhost.jar");
        if (target.getAbsoluteFile().equals(in.getAbsoluteFile())) {
            throw new IOException("the output would overwrite the input; choose another name");
        }
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        byte[] descriptor = descriptor(info);

        if (info.hasDex) {
            out.println("  already has classes.dex: adding the descriptor only");
            copyWithDescriptor(in, target, descriptor);
        } else if (info.hasClassFiles) {
            convertClasses(in, target, descriptor, o, out);
        } else {
            throw new IOException("nothing to convert: no classes.dex and no .class files");
        }
        out.println("  wrote " + target + " (" + target.length() + " bytes)");
        out.println("  copy it to the java_mods folder and restart the game");
        return true;
    }

    private static File orDot(File f) {
        return f == null ? new File(".") : f;
    }

    private static void applyOverrides(JarInfo info, Options o) {
        if (o.id != null) {
            info.id = IdUtil.sanitize(o.id);
        }
        if (o.name != null) {
            info.name = o.name;
        } else if (o.id != null) {
            info.name = IdUtil.prettyName(info.id);
        }
        if (o.main != null) {
            info.mainClass = o.main;
        }
        if (o.version != null) {
            info.version = o.version;
        }
        if (o.author != null) {
            info.author = o.author;
        }
        if (o.description != null) {
            info.description = o.description;
        }
    }

    private static void describeClasses(JarInfo info, PrintStream out) {
        out.println("  " + info.classes.size() + " class(es); dex=" + info.hasDex + ", class files="
                + info.hasClassFiles);
        int shown = 0;
        for (ClassInfo c : info.classes) {
            if (c.isPublic && !c.isInterface && c.name.indexOf('$') < 0 && shown++ < 20) {
                out.println("    " + c.name + (c.hasPublicNoArgConstructor ? "" : " (no public no-arg constructor)"));
            }
        }
    }

    // ------------------------------------------------------------ descriptor

    /** The bytes of modhost-mod.properties for this jar. */
    static byte[] descriptor(JarInfo info) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("# Written by AddJar (").append(info.kind.label).append(")\n");
        line(b, "main", info.mainClass);
        line(b, "id", info.id);
        line(b, "name", info.name);
        line(b, "version", info.version);
        line(b, "author", info.author);
        line(b, "description", info.description);
        line(b, "api", "1");
        // Properties.load must read back exactly what was meant, so verify it.
        Properties check = new Properties();
        check.load(new java.io.StringReader(b.toString()));
        if (!info.mainClass.equals(check.getProperty("main"))) {
            throw new IOException("the main class name cannot be stored in a properties file");
        }
        return b.toString().getBytes(UTF8);
    }

    private static void line(StringBuilder b, String key, String value) {
        b.append(key).append('=').append(escape(value == null ? "" : value)).append('\n');
    }

    private static String escape(String v) {
        StringBuilder b = new StringBuilder();
        for (char c : v.toCharArray()) {
            switch (c) {
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '=':
                case ':':
                case '#':
                case '!':
                    b.append('\\').append(c);
                    break;
                default:
                    b.append(c);
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------ jar rewriting

    static boolean dropFromConversion(String entry) {
        String lower = entry.toLowerCase();
        return entry.startsWith("org/modhost/")
                || entry.equals("module-info.class")
                || entry.startsWith("META-INF/versions/")
                || entry.equals(JarInspector.DESCRIPTOR)
                || lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa")
                        || lower.endsWith(".dsa") || lower.endsWith(".ec"));
    }

    /** Copies a jar and replaces its descriptor. Used for jars that already contain a dex. */
    static void copyWithDescriptor(File in, File out, byte[] descriptor) throws IOException {
        try (ZipFile zip = new ZipFile(in); ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || dropFromConversion(e.getName())) {
                    continue;
                }
                putEntry(zos, e.getName(), read(zip, e));
            }
            putEntry(zos, JarInspector.DESCRIPTOR, descriptor);
        }
    }

    /** Writes a copy of the jar without the entries d8 should not see. Returns how many .class files remain. */
    static int filterJar(File in, File out) throws IOException {
        int classes = 0;
        try (ZipFile zip = new ZipFile(in); ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || dropFromConversion(e.getName())) {
                    continue;
                }
                if (e.getName().endsWith(".class")) {
                    classes++;
                }
                putEntry(zos, e.getName(), read(zip, e));
            }
        }
        return classes;
    }

    private static void convertClasses(File in, File target, byte[] descriptor, Options o, PrintStream out)
            throws IOException {
        File work = File.createTempFile("addjar", "");
        if (!work.delete() || !work.mkdirs()) {
            throw new IOException("cannot create a temporary folder");
        }
        try {
            File filtered = new File(work, "filtered.jar");
            int classes = filterJar(in, filtered);
            if (classes == 0) {
                throw new IOException("no classes left to convert after removing org/modhost and signatures");
            }
            File d8 = locateD8(o.d8);
            File androidJar = locateAndroidJar(o.androidJar);
            File dexDir = new File(work, "dex");
            dexDir.mkdirs();
            List<String> cmd = new ArrayList<>();
            cmd.add(d8.getPath());
            cmd.addAll(Arrays.asList("--release", "--min-api", o.minApi, "--lib", androidJar.getPath()));
            if (o.apiJar != null) {
                cmd.addAll(Arrays.asList("--classpath", o.apiJar));
            }
            cmd.addAll(Arrays.asList("--output", dexDir.getPath(), filtered.getPath()));
            out.println("  converting " + classes + " class(es) with " + d8.getName() + " ...");
            String log = exec(cmd);
            File[] dex = dexDir.listFiles((d, n) -> n.matches("classes\\d*\\.dex"));
            if (dex == null || dex.length == 0) {
                throw new IOException("d8 produced no classes.dex. Its output:\n" + log);
            }
            Arrays.sort(dex);
            try (ZipFile zip = new ZipFile(filtered); ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(target))) {
                for (File d : dex) {
                    putEntry(zos, d.getName(), java.nio.file.Files.readAllBytes(d.toPath()));
                }
                Enumeration<? extends ZipEntry> en = zip.entries();
                Set<String> seen = new HashSet<>();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || n.endsWith(".class") || n.equals("META-INF/MANIFEST.MF") || !seen.add(n)) {
                        continue;
                    }
                    putEntry(zos, n, read(zip, e));
                }
                putEntry(zos, JarInspector.DESCRIPTOR, descriptor);
            }
        } finally {
            deleteTree(work);
        }
    }

    // ------------------------------------------------------------ tool discovery

    static File locateD8(String explicit) throws IOException {
        List<String> tried = new ArrayList<>();
        if (explicit != null) {
            return mustExist(new File(explicit), "--d8");
        }
        String env = System.getenv("D8");
        if (env != null && !env.isEmpty()) {
            return mustExist(new File(env), "$D8");
        }
        for (String sdk : sdkRoots()) {
            File bt = new File(sdk, "build-tools");
            File[] versions = bt.listFiles();
            if (versions == null) {
                continue;
            }
            Arrays.sort(versions, Collections.reverseOrder(byVersion()));
            for (File v : versions) {
                for (String n : new String[] {"d8", "d8.bat"}) {
                    File f = new File(v, n);
                    tried.add(f.getPath());
                    if (f.isFile()) {
                        return f;
                    }
                }
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                for (String n : new String[] {"d8", "d8.bat"}) {
                    File f = new File(dir, n);
                    if (f.isFile()) {
                        return f;
                    }
                }
            }
        }
        throw new IOException("could not find d8. Install the Android SDK build-tools and set ANDROID_HOME, "
                + "or pass --d8 <path>." + (tried.isEmpty() ? "" : " Looked in: " + tried));
    }

    static File locateAndroidJar(String explicit) throws IOException {
        if (explicit != null) {
            return mustExist(new File(explicit), "--android-jar");
        }
        String env = System.getenv("ANDROID_JAR");
        if (env != null && !env.isEmpty()) {
            return mustExist(new File(env), "$ANDROID_JAR");
        }
        for (String sdk : sdkRoots()) {
            File[] platforms = new File(sdk, "platforms").listFiles();
            if (platforms == null) {
                continue;
            }
            Arrays.sort(platforms, Collections.reverseOrder(byVersion()));
            for (File p : platforms) {
                File f = new File(p, "android.jar");
                if (f.isFile()) {
                    return f;
                }
            }
        }
        throw new IOException("could not find android.jar. Install an Android platform with the SDK manager "
                + "and set ANDROID_HOME, or pass --android-jar <path>");
    }

    private static List<String> sdkRoots() {
        List<String> roots = new ArrayList<>();
        for (String key : new String[] {"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String v = System.getenv(key);
            if (v != null && !v.isEmpty()) {
                roots.add(v);
            }
        }
        return roots;
    }

    private static File mustExist(File f, String what) throws IOException {
        if (!f.isFile()) {
            throw new IOException(what + " points to a missing file: " + f);
        }
        return f;
    }

    /** Orders names like "34.0.0", "android-34" and "33.0.2" by their numbers. */
    static Comparator<File> byVersion() {
        return (a, b) -> compareVersions(a.getName(), b.getName());
    }

    public static int compareVersions(String a, String b) {
        long[] x = numbers(a);
        long[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? x[i] : 0;
            long q = i < y.length ? y[i] : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    private static long[] numbers(String s) {
        List<Long> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        while (m.find()) {
            out.add(Long.valueOf(m.group()));
        }
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }

    // ------------------------------------------------------------ plumbing

    private static String exec(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for d8");
        }
        String text = new String(buf.toByteArray(), UTF8);
        if (code != 0) {
            throw new IOException("d8 failed with exit code " + code + ":\n" + text
                    + (text.contains("Missing class") || text.contains("not found")
                            ? "\nHint: pass --api-jar modhost-api.jar so references to org.modhost resolve." : ""));
        }
        return text;
    }

    private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) {
                b.write(chunk, 0, n);
            }
            return b.toByteArray();
        }
    }

    private static void putEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }
}
