#pragma once

// Portable logging shim for Streamify native code.
// - On Android: thin passthrough to liblog (<android/log.h>).
// - On host (unit tests, LibFuzzer, sanitizers): stderr so shards stay
//   buildable off-device while still producing diagnosable output.
#if defined(__ANDROID__)

#include <android/log.h>

#else

#include <cstdio>
#include <cstdarg>

#ifndef ANDROID_LOG_VERBOSE
#define ANDROID_LOG_VERBOSE 2
#endif
#ifndef ANDROID_LOG_DEBUG
#define ANDROID_LOG_DEBUG 3
#endif
#ifndef ANDROID_LOG_INFO
#define ANDROID_LOG_INFO 4
#endif
#ifndef ANDROID_LOG_WARN
#define ANDROID_LOG_WARN 5
#endif
#ifndef ANDROID_LOG_ERROR
#define ANDROID_LOG_ERROR 6
#endif

static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    (void)prio;
    if (!fmt) return 0;
    fprintf(stderr, "[streamify:%s] ", tag ? tag : "native");
    va_list args;
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    va_end(args);
    fputc('\n', stderr);
    return 0;
}

#endif // __ANDROID__
