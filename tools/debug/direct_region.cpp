// 临时诊断 ABI：直接调用生产 PrintedPageMnnBackend 的同一视觉适配、prompt 和生成路径。
#include "backend_factory.hpp"
#include "android_engine_runtime.hpp"
#include <nlohmann/json.hpp>
#include <atomic>
#include <filesystem>
#include <memory>

namespace {
std::unique_ptr<dococr::IInferenceEngine> backend;
std::string reply;
}
extern "C" const char* ocr_diag_last_error() { return reply.c_str(); }
extern "C" int ocr_diag_create(const char* configuration, int threads) {
    try {
        const auto config = nlohmann::json::parse(configuration);
        dococr::BackendLoadSpec spec;
        spec.backend_id = "mnn:pp-doclayout-v3+ovisocr2";
        spec.config_hash = "diagnostic-direct-region";
        spec.device = "cpu";
        for (const std::string kind : {"layout", "recognition"}) {
            const auto model = config.at("models").at(kind);
            for (const auto artifact : model.at("artifacts")) {
                const auto path = std::filesystem::path(model.at("root").get<std::string>()) /
                                  artifact.at("path").get<std::string>();
                spec.artifacts.push_back({kind, path.u8string(), artifact.at("sha256").get<std::string>(), "contract_verified"});
            }
        }
        backend = dococr::make_backend(spec.backend_id);
        if (!dococr::load_android_backend(*backend, spec, threads)) {
            reply = backend->last_error(); backend.reset(); return 1;
        }
        return 0;
    } catch (const std::exception& error) { reply = error.what(); backend.reset(); return 1; }
}
extern "C" const char* ocr_diag_region(const unsigned char* rgb, size_t size, int width, int height, int max_tokens) {
    try {
        if (!backend || width < 1 || height < 1 || size != size_t(width) * height * 3)
            throw std::runtime_error("diagnostic_rgb_input_invalid");
        if (!backend->reset()) throw std::runtime_error("diagnostic_reset_failed");
        dococr::GenerationRequest generation;
        generation.request_id = "diagnostic-region"; generation.task = "text";
        generation.image = {width, height, std::vector<unsigned char>(rgb, rgb + size)};
        generation.source_box = {0, 0, width, height}; generation.max_new_tokens = max_tokens;
        generation.generation_timeout_ms = 120000;
        generation.visual_min_pixels = 65536; generation.visual_max_pixels = 313600;
        std::atomic_bool cancelled{false}; dococr::ExecutionContext context{cancelled};
        auto response = backend->execute({generation.request_id, generation}, context);
        const auto& result = std::get<dococr::GenerationOutput>(response.payload);
        reply = nlohmann::json{{"raw", result.raw_output}, {"finishReason", result.finish_reason},
                              {"stopReason", result.stop_reason}, {"error", result.error},
                              {"visualSize", {result.visual_transform.canvas_width, result.visual_transform.canvas_height}},
                              {"visualTokens", result.visual_tokens}}.dump();
    } catch (const std::exception& error) { reply = nlohmann::json{{"diagnosticError", error.what()}}.dump(); }
    return reply.c_str();
}
extern "C" void ocr_diag_destroy() { backend.reset(); }
