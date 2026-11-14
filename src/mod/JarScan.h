#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>

namespace luahost {

// What a quick look at a .jar file's central directory tells us, before any Java is started.
// Nothing here loads or runs code; it only reads the zip table of contents.
struct JarScanResult {
    std::filesystem::path path;
    std::uint64_t size = 0;
    bool validZip = false;
    bool hasDex = false;         // classes.dex, classes2.dex, ...
    bool hasDescriptor = false;  // modhost-mod.properties
    bool hasManifest = false;    // META-INF/MANIFEST.MF
    bool hasClassFiles = false;  // plain .class files (needs converting with d8 before it can run on Android)
    std::size_t entryCount = 0;
    std::string problem;         // set when validZip is false

    // True when the Java host can load this jar on a device.
    [[nodiscard]] bool loadable() const { return validZip && hasDex; }
};

class JarScan {
  public:
    using Logger = std::function<void(const char *level, const std::string &tag, const std::string &message)>;

    // Reads the end-of-central-directory record and the entry names of one jar.
    static JarScanResult inspect(const std::filesystem::path &jar);

    // Inspects every *.jar directly inside `dir` (sorted by name). A missing folder gives an empty list.
    static std::vector<JarScanResult> scanDirectory(const std::filesystem::path &dir);

    // Moves usable jars from `inbox` into `modsDir`. Jars that cannot run are left where they are and
    // the reason is logged. Returns how many were installed.
    static std::size_t installInbox(const std::filesystem::path &inbox, const std::filesystem::path &modsDir,
                                    const Logger &log);

    // One line for the log, such as "my_mod.jar: ok (dex, descriptor)" or "x.jar: needs d8 ...".
    [[nodiscard]] static std::string describe(const JarScanResult &r);

    // True for a plain file name that is safe to copy into the mods folder.
    [[nodiscard]] static bool safeFileName(const std::string &name);
};

} // namespace luahost
