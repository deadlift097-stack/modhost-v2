// Self-contained checks for JarScan. Build and run:
//   g++ -std=c++20 -Isrc tests/jarscan_test.cpp src/mod/JarScan.cpp -o jarscan_test && ./jarscan_test
#include "mod/JarScan.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

using luahost::JarScan;

namespace fs = std::filesystem;

namespace {

int failures = 0;
int passed = 0;

void check(bool ok, const std::string &what) {
    if (ok) {
        ++passed;
    } else {
        ++failures;
        std::printf("FAILED: %s\n", what.c_str());
    }
}

void put16(std::string &s, unsigned v) {
    s.push_back(static_cast<char>(v & 0xFF));
    s.push_back(static_cast<char>((v >> 8) & 0xFF));
}

void put32(std::string &s, unsigned long v) {
    put16(s, static_cast<unsigned>(v & 0xFFFF));
    put16(s, static_cast<unsigned>((v >> 16) & 0xFFFF));
}

// Builds a stored (uncompressed) zip with the given entry names. Contents are not needed by the scanner.
std::string makeZip(const std::vector<std::string> &names, const std::string &comment = "") {
    std::string body;
    std::string central;
    for (const std::string &n : names) {
        const unsigned long offset = body.size();
        put32(body, 0x04034b50);
        put16(body, 20);
        put16(body, 0);
        put16(body, 0);
        put32(body, 0);
        put32(body, 0);
        put32(body, 0);
        put32(body, 0);
        put16(body, static_cast<unsigned>(n.size()));
        put16(body, 0);
        body += n;

        put32(central, 0x02014b50);
        put16(central, 20);
        put16(central, 20);
        put16(central, 0);
        put16(central, 0);
        put32(central, 0);
        put32(central, 0);
        put32(central, 0);
        put32(central, 0);
        put16(central, static_cast<unsigned>(n.size()));
        put16(central, 0);
        put16(central, 0);
        put16(central, 0);
        put16(central, 0);
        put32(central, 0);
        put32(central, offset);
        central += n;
    }
    std::string out = body;
    const unsigned long cdOffset = out.size();
    out += central;
    put32(out, 0x06054b50);
    put16(out, 0);
    put16(out, 0);
    put16(out, static_cast<unsigned>(names.size()));
    put16(out, static_cast<unsigned>(names.size()));
    put32(out, central.size());
    put32(out, cdOffset);
    put16(out, static_cast<unsigned>(comment.size()));
    out += comment;
    return out;
}

void write(const fs::path &p, const std::string &data) {
    fs::create_directories(p.parent_path());
    std::ofstream(p, std::ios::binary) << data;
}

} // namespace

int main() {
    const fs::path root = fs::temp_directory_path() / "jarscan_test";
    fs::remove_all(root);
    fs::create_directories(root);

    write(root / "good.jar", makeZip({"classes.dex", "modhost-mod.properties", "META-INF/MANIFEST.MF"}));
    auto good = JarScan::inspect(root / "good.jar");
    check(good.validZip && good.hasDex && good.hasDescriptor && good.hasManifest, "dex jar is recognised");
    check(good.loadable() && !good.hasClassFiles && good.entryCount == 3, "dex jar is loadable");

    write(root / "multi.jar", makeZip({"classes.dex", "classes2.dex", "classes10.dex", "classesX.dex"}));
    check(JarScan::inspect(root / "multi.jar").hasDex, "multi-dex names are accepted");
    write(root / "notdex.jar", makeZip({"classesX.dex", "classes.dexx", "a/classes.dex"}));
    check(!JarScan::inspect(root / "notdex.jar").hasDex, "look-alike dex names are rejected");

    write(root / "plain.jar", makeZip({"com/example/Main.class", "META-INF/module-info.class", "data/x.txt"}));
    auto plain = JarScan::inspect(root / "plain.jar");
    check(plain.validZip && plain.hasClassFiles && !plain.hasDex && !plain.loadable(), "class-only jar needs conversion");
    check(JarScan::describe(plain).find("needs converting") != std::string::npos, "describe explains conversion");

    write(root / "empty.jar", makeZip({"readme.txt"}));
    check(JarScan::describe(JarScan::inspect(root / "empty.jar")).find("no code") != std::string::npos, "no-code jar");

    write(root / "comment.jar", makeZip({"classes.dex"}, std::string(300, 'c')));
    check(JarScan::inspect(root / "comment.jar").hasDex, "zip comment does not hide the directory");

    write(root / "bad.jar", "this is not a zip file at all, just text that is long enough");
    auto bad = JarScan::inspect(root / "bad.jar");
    check(!bad.validZip && !bad.problem.empty(), "text file is rejected");
    write(root / "tiny.jar", "PK");
    check(!JarScan::inspect(root / "tiny.jar").validZip, "tiny file is rejected");
    check(!JarScan::inspect(root / "missing.jar").validZip, "missing file is rejected");

    // Break the signature of the second central-directory entry.
    std::string damaged = makeZip({"classes.dex", "other.txt"});
    const std::string sig("PK\x01\x02", 4);
    const std::size_t first = damaged.find(sig);
    const std::size_t second = damaged.find(sig, first + 1);
    damaged[second + 2] = 'X';
    write(root / "trunc.jar", damaged);
    check(!JarScan::inspect(root / "trunc.jar").validZip, "damaged central directory is rejected");

    write(root / "evil.jar", makeZip({"../escape.txt", "classes.dex"}));
    check(!JarScan::inspect(root / "evil.jar").validZip, "path traversal entry is rejected");

    auto list = JarScan::scanDirectory(root);
    check(list.size() == 10, "scanDirectory finds every .jar (" + std::to_string(list.size()) + ")");
    check(JarScan::scanDirectory(root / "nope").empty(), "missing folder gives an empty list");

    check(JarScan::safeFileName("my mod-1.2.jar") && JarScan::safeFileName("A_b.JAR"), "ordinary names are safe");
    check(!JarScan::safeFileName("../x.jar") && !JarScan::safeFileName(".hidden.jar") && !JarScan::safeFileName("a.txt") &&
              !JarScan::safeFileName("") && !JarScan::safeFileName("a/b.jar") && !JarScan::safeFileName("a..b.jar"),
          "unsafe names are refused");

    // inbox: only jars with a dex move; the rest stay and are logged
    const fs::path inbox = root / "inbox";
    const fs::path mods = root / "java_mods";
    write(inbox / "ready.jar", makeZip({"classes.dex"}));
    write(inbox / "classes_only.jar", makeZip({"a/B.class"}));
    write(inbox / "junk.jar", "not a zip, long enough to pass the size check, really");
    write(inbox / "note.txt", "ignored");
    std::vector<std::string> lines;
    auto logger = [&lines](const char *level, const std::string &tag, const std::string &msg) {
        lines.push_back(std::string(level) + " " + tag + " " + msg);
    };
    check(JarScan::installInbox(inbox, mods, logger) == 1, "one jar is installed");
    check(fs::exists(mods / "ready.jar") && !fs::exists(inbox / "ready.jar"), "ready.jar moved");
    check(fs::exists(inbox / "classes_only.jar") && !fs::exists(mods / "classes_only.jar"), "class-only jar stays");
    check(fs::exists(inbox / "junk.jar") && fs::exists(inbox / "note.txt"), "other files stay");
    bool warned = false;
    for (const auto &l : lines)
        warned = warned || (l.rfind("WARN inbox", 0) == 0 && l.find("classes_only.jar") != std::string::npos);
    check(warned, "class-only jar is explained in the log");

    write(inbox / "ready.jar", makeZip({"classes.dex"}));
    lines.clear();
    check(JarScan::installInbox(inbox, mods, logger) == 0 && !fs::exists(inbox / "ready.jar"),
          "same jar again is not copied twice");
    check(JarScan::installInbox(root / "none", mods, logger) == 0, "missing inbox is fine");

    fs::remove_all(root);
    std::printf("%d checks passed, %d failed\n", passed, failures);
    return failures == 0 ? 0 : 1;
}
