// jarscan: check jar files the way Mod Host does at start-up, on a computer.
//   g++ -std=c++20 -Isrc tools/jarscan_cli.cpp src/mod/JarScan.cpp -o jarscan
//   ./jarscan my_mod.jar other.jar        (exit status 1 if any jar cannot run on a device)
#include "mod/JarScan.h"

#include <cstdio>
#include <filesystem>

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "Usage: jarscan <file.jar|folder> [...]\n");
        return 2;
    }
    int bad = 0;
    for (int i = 1; i < argc; ++i) {
        const std::filesystem::path p(argv[i]);
        std::error_code ec;
        std::vector<luahost::JarScanResult> results;
        if (std::filesystem::is_directory(p, ec))
            results = luahost::JarScan::scanDirectory(p);
        else
            results.push_back(luahost::JarScan::inspect(p));
        if (results.empty())
            std::printf("%s: no .jar files\n", argv[i]);
        for (const luahost::JarScanResult &r : results) {
            std::printf("%s\n", luahost::JarScan::describe(r).c_str());
            if (!r.loadable())
                ++bad;
        }
    }
    return bad == 0 ? 0 : 1;
}
