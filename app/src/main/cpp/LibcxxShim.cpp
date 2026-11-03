// Compatibility shim for the prebuilt ncnn static library.
//
// ncnn-20261020 was built with a newer NDK (r27+) whose libc++ exports
// std::__hash_memory as an out-of-line symbol. The project is pinned to NDK
// r26.3 (the newest one installed locally, and the first one that provides
// every other libc++ symbol ncnn needs), where that symbol does not exist.
//
// Supplying it here keeps us on a single NDK version instead of mixing STLs.
// The only consumer is std::hash for strings/bytes, so any deterministic,
// well-distributed function is functionally equivalent: hash values are never
// persisted and never cross a process boundary.
//
// If the project ever moves to NDK r27+, delete this file and the CMake entry.

#include <cstddef>
#include <cstdint>

namespace std {
inline namespace __ndk1 {

// FNV-1a 64-bit.
size_t __hash_memory(const void* __p, size_t __n) noexcept {
    const unsigned char* p = static_cast<const unsigned char*>(__p);
    std::uint64_t h = 1469598103934665603ull;
    for (size_t i = 0; i < __n; i++) {
        h ^= static_cast<std::uint64_t>(p[i]);
        h *= 1099511628211ull;
    }
    return static_cast<size_t>(h);
}

}  // namespace __ndk1
}  // namespace std
