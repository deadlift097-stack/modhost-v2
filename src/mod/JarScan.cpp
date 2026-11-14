#include "mod/JarScan.h"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <system_error>

namespace luahost {

namespace {

constexpr std::uint32_t kEocdSignature = 0x06054b50;
constexpr std::uint32_t kCentralSignature = 0x02014b50;
constexpr std::size_t kMaxCentralDirectory = 16u * 1024u * 1024u;
constexpr std::size_t kMaxComment = 65535;
constexpr std::size_t kEocdSize = 22;

std::uint32_t le16(const std::vector<unsigned char> &b, std::size_t at) {
    return static_cast<std::uint32_t>(b[at]) | (static_cast<std::uint32_t>(b[at + 1]) << 8);
}

std::uint32_t le32(const std::vector<unsigned char> &b, std::size_t at) {
    return le16(b, at) | (le16(b, at + 2) << 16);
}

bool endsWith(const std::string &s, const std::string &suffix) {
    return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

// classes.dex, classes2.dex, classes10.dex ...
bool isDexName(const std::string &n) {
    if (n.rfind("classes", 0) != 0 || !endsWith(n, ".dex"))
        return false;
    const std::string middle = n.substr(7, n.size() - 7 - 4);
    return std::all_of(middle.begin(), middle.end(), [](unsigned char c) { return std::isdigit(c) != 0; });
}

void classify(const std::string &name, JarScanResult &r) {
    if (isDexName(name))
        r.hasDex = true;
    else if (name == "modhost-mod.properties")
        r.hasDescriptor = true;
    else if (name == "META-INF/MANIFEST.MF")
        r.hasManifest = true;
    else if (endsWith(name, ".class") && name.rfind("META-INF/", 0) != 0 && name != "module-info.class")
        r.hasClassFiles = true;
}

} // namespace

JarScanResult JarScan::inspect(const std::filesystem::path &jar) {
    JarScanResult r;
    r.path = jar;
    std::error_code ec;
    r.size = std::filesystem::file_size(jar, ec);
    if (ec) {
        r.problem = "cannot read the file (" + ec.message() + ")";
        return r;
    }
    if (r.size < kEocdSize) {
        r.problem = "too small to be a jar";
        return r;
    }
    std::ifstream in(jar, std::ios::binary);
    if (!in) {
        r.problem = "cannot open the file";
        return r;
    }

    // The end-of-central-directory record sits in the last 22 + comment bytes.
    const std::uint64_t tailSize = std::min<std::uint64_t>(r.size, kEocdSize + kMaxComment);
    std::vector<unsigned char> tail(static_cast<std::size_t>(tailSize));
    in.seekg(static_cast<std::streamoff>(r.size - tailSize));
    in.read(reinterpret_cast<char *>(tail.data()), static_cast<std::streamsize>(tail.size()));
    if (!in) {
        r.problem = "cannot read the end of the file";
        return r;
    }
    std::size_t eocd = std::string::npos;
    for (std::size_t i = tail.size() - kEocdSize + 1; i-- > 0;) {
        if (le32(tail, i) == kEocdSignature) {
            eocd = i;
            break;
        }
    }
    if (eocd == std::string::npos) {
        r.problem = "not a zip file (no end-of-central-directory record)";
        return r;
    }
    const std::uint32_t entries = le16(tail, eocd + 10);
    const std::uint32_t cdSize = le32(tail, eocd + 12);
    const std::uint32_t cdOffset = le32(tail, eocd + 16);
    if (entries == 0xFFFF || cdSize == 0xFFFFFFFFu || cdOffset == 0xFFFFFFFFu) {
        r.problem = "zip64 archives are not supported";
        return r;
    }
    if (cdSize > kMaxCentralDirectory || static_cast<std::uint64_t>(cdOffset) + cdSize > r.size) {
        r.problem = "the zip table of contents is damaged";
        return r;
    }

    std::vector<unsigned char> cd(cdSize);
    in.seekg(static_cast<std::streamoff>(cdOffset));
    in.read(reinterpret_cast<char *>(cd.data()), static_cast<std::streamsize>(cd.size()));
    if (!in) {
        r.problem = "cannot read the zip table of contents";
        return r;
    }

    std::size_t pos = 0;
    for (std::uint32_t i = 0; i < entries; ++i) {
        if (pos + 46 > cd.size() || le32(cd, pos) != kCentralSignature) {
            r.problem = "the zip table of contents is damaged (entry " + std::to_string(i) + ")";
            return r;
        }
        const std::size_t nameLen = le16(cd, pos + 28);
        const std::size_t extraLen = le16(cd, pos + 30);
        const std::size_t commentLen = le16(cd, pos + 32);
        if (pos + 46 + nameLen + extraLen + commentLen > cd.size()) {
            r.problem = "the zip table of contents is damaged (entry " + std::to_string(i) + ")";
            return r;
        }
        const std::string name(reinterpret_cast<const char *>(cd.data() + pos + 46), nameLen);
        if (name.find("..") != std::string::npos || (!name.empty() && name.front() == '/')) {
            r.problem = "unsafe entry name: " + name;
            return r;
        }
        if (!name.empty() && name.back() != '/')
            classify(name, r);
        pos += 46 + nameLen + extraLen + commentLen;
        ++r.entryCount;
    }
    r.validZip = true;
    return r;
}

std::vector<JarScanResult> JarScan::scanDirectory(const std::filesystem::path &dir) {
    std::vector<JarScanResult> out;
    std::error_code ec;
    if (!std::filesystem::is_directory(dir, ec))
        return out;
    std::vector<std::filesystem::path> jars;
    for (const auto &entry : std::filesystem::directory_iterator(dir, ec)) {
        if (entry.is_regular_file(ec) && lower(entry.path().extension().string()) == ".jar")
            jars.push_back(entry.path());
    }
    std::sort(jars.begin(), jars.end());
    for (const auto &p : jars)
        out.push_back(inspect(p));
    return out;
}

bool JarScan::safeFileName(const std::string &name) {
    if (name.empty() || name.size() > 128 || name.front() == '.')
        return false;
    if (lower(name).size() < 5 || !endsWith(lower(name), ".jar"))
        return false;
    return std::all_of(name.begin(), name.end(), [](unsigned char c) {
        return std::isalnum(c) != 0 || c == '_' || c == '-' || c == '.' || c == ' ' || c == '+' || c == '(' || c == ')';
    }) && name.find("..") == std::string::npos;
}

std::string JarScan::describe(const JarScanResult &r) {
    const std::string name = r.path.filename().string();
    if (!r.validZip)
        return name + ": " + r.problem;
    std::string parts;
    auto add = [&parts](const char *what) {
        parts += parts.empty() ? "" : ", ";
        parts += what;
    };
    if (r.hasDex)
        add("dex");
    if (r.hasDescriptor)
        add("descriptor");
    if (r.hasManifest)
        add("manifest");
    if (r.hasClassFiles)
        add("class files");
    if (r.hasDex)
        return name + ": ok (" + parts + ")";
    if (r.hasClassFiles)
        return name + ": needs converting first, it has class files but no classes.dex. "
                      "Run the AddJar tool or the Convert jar workflow (docs/JAR_MODS.md)";
    return name + ": has no code (no classes.dex and no class files)";
}

std::size_t JarScan::installInbox(const std::filesystem::path &inbox, const std::filesystem::path &modsDir,
                                  const Logger &log) {
    std::error_code ec;
    if (!std::filesystem::is_directory(inbox, ec))
        return 0;
    std::filesystem::create_directories(modsDir, ec);
    std::size_t installed = 0;
    for (const JarScanResult &r : scanDirectory(inbox)) {
        const std::string name = r.path.filename().string();
        if (!safeFileName(name)) {
            log("WARN", "inbox", name + ": skipped, the file name has characters that are not allowed");
            continue;
        }
        if (!r.loadable()) {
            log("WARN", "inbox", "left in the inbox: " + describe(r));
            continue;
        }
        const std::filesystem::path target = modsDir / name;
        if (std::filesystem::exists(target, ec) && std::filesystem::file_size(target, ec) == r.size) {
            log("INFO", "inbox", name + ": already installed with the same size, removing the inbox copy");
            std::filesystem::remove(r.path, ec);
            continue;
        }
        std::filesystem::copy_file(r.path, target, std::filesystem::copy_options::overwrite_existing, ec);
        if (ec) {
            log("ERROR", "inbox", name + ": could not copy into java_mods (" + ec.message() + ")");
            continue;
        }
        std::filesystem::remove(r.path, ec);
        log("INFO", "inbox", name + ": installed into java_mods");
        ++installed;
    }
    return installed;
}

} // namespace luahost
