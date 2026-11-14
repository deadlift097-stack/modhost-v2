package org.modhost.jar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Collects what happened to each jar at start-up and writes a plain-text report that can be
 * read in a file manager. It turns "skipped: ..." log lines into a short list with a fix hint.
 */
public final class JarReport {
    /** Outcome of one jar. */
    public static final class Entry {
        public final String jar;
        public final boolean loaded;
        public final String detail;
        public final String hint;

        Entry(String jar, boolean loaded, String detail, String hint) {
            this.jar = jar;
            this.loaded = loaded;
            this.detail = detail;
            this.hint = hint;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void loaded(JarInfo info, String how) {
        entries.add(new Entry(info.file.getName(), true, info.summary() + (how == null ? "" : "; " + how), ""));
    }

    public synchronized void skipped(JarInfo info, String reason) {
        entries.add(new Entry(info.file.getName(), false, reason, hintFor(info, reason)));
    }

    public synchronized void skipped(String jarName, String reason) {
        entries.add(new Entry(jarName, false, reason, ""));
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public synchronized int loadedCount() {
        int n = 0;
        for (Entry e : entries) {
            if (e.loaded) {
                n++;
            }
        }
        return n;
    }

    /** A short instruction for the most common ways a jar fails to load. */
    static String hintFor(JarInfo info, String reason) {
        if (info != null && info.hasClassFiles && !info.hasDex) {
            return "Convert it first: java org.modhost.tools.AddJar <jar> (needs the Android build-tools), "
                    + "or run the 'Convert jar' GitHub workflow. See docs/JAR_MODS.md.";
        }
        if (reason.contains("entry point")) {
            return "Tell the host which class to start: add modhost-mod.properties with main=<class>.";
        }
        if (reason.contains("API")) {
            return "Use a newer Mod Host build.";
        }
        return "";
    }

    public synchronized String render() {
        StringBuilder b = new StringBuilder();
        b.append("Mod Host jar report, ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append('\n');
        b.append(loadedCount()).append(" of ").append(entries.size()).append(" jar(s) loaded\n\n");
        for (Entry e : entries) {
            b.append(e.loaded ? "[OK]      " : "[SKIPPED] ").append(e.jar).append('\n');
            b.append("          ").append(e.detail).append('\n');
            if (!e.hint.isEmpty()) {
                b.append("          fix: ").append(e.hint).append('\n');
            }
        }
        if (entries.isEmpty()) {
            b.append("No .jar files were found in the java_mods folder.\n");
        }
        return b.toString();
    }

    /** Writes the report; failures are ignored because the report is a convenience. */
    public void write(File file) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8"))) {
            w.write(render());
        } catch (IOException ignored) {
            // the log still has every message
        }
    }
}
