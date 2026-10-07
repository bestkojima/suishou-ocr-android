#pragma once

#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <stdexcept>
#include <llm/llm.hpp>

namespace dococr {
// 对照 MNN Chat 的 LlmSession::Load：先设置 mmap/tmp_path，再调用 load。
inline bool load_android_llm(MNN::Transformer::Llm& llm, const std::string& config_hash) {
    const char* cache_root = std::getenv("TMPDIR");
    if (!cache_root || !*cache_root) throw std::runtime_error("android_model_cache_missing");
    // 按模型／配置身份隔离缓存；更换模型后不复用另一份权重缓存。
    auto cache = std::filesystem::path(cache_root) / ("ovis-mmap-" + config_hash);
    std::filesystem::create_directories(cache);
    llm.set_config("{\"use_mmap\":true,\"tmp_path\":" + json_quote(cache.string()) + "}");
    auto started = std::chrono::steady_clock::now();
    bool loaded = llm.load();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "OcrEngine", "Ovis load: %lld ms, success=%d, mmap=1",
        static_cast<long long>(elapsed), loaded);
#else
    std::fprintf(stderr, "Ovis load: %lld ms, success=%d, mmap=1\n", static_cast<long long>(elapsed), loaded);
#endif
    return loaded;
}
} // namespace dococr
