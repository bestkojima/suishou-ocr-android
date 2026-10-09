#include "model_ocr_config.hpp"
#include <iostream>

using namespace dococr;
namespace fs = std::filesystem;
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
void write(const fs::path& path, const ModelJson& data) {
    std::ofstream out(path);out << data.dump();
}
template<class F> void rejected(F operation, const std::string& expected) {
    try { operation(); } catch (const std::exception& error) {
        require(std::string(error.what()).find(expected) != std::string::npos, error.what());return;
    }
    throw std::runtime_error("无效模型配置被接受");
}
int main(int argc, char** argv) {
    require(argc == 3, "需要项目目录及测试临时目录");
    const fs::path project(argv[1]), root(argv[2]);fs::create_directories(root);
    require(same_model_json(ModelJson{{"mean",{0.48145466}}},ModelJson{{"mean",{0.48145467}}}), "浮点序列化舍入被误判");
    require(!same_model_json(ModelJson{{"use_mmap",false}},ModelJson{{"use_mmap",true}}), "mmap 配置差异被忽略");
    require(!same_model_json(ModelJson{{"thread_num",2}},ModelJson{{"thread_num",4}}), "线程配置差异被忽略");
    const auto profiles = read_model_json(project / "app/src/main/assets/ocr/model-profiles.json");
    require(ModelJson::parse(legacy_ovis_profile) == profiles.at("ovis").at("config"), "旧 Ovis 兼容配置漂移");
    auto runtime = profiles.at("glm").at("config");
    runtime["ocr"]["name"] = "按字段配置的任意模型";
    runtime["ocr"]["prompts"]["text"] = "识别文字";
    runtime["ocr"]["prompts"]["formula"] = "识别公式";
    runtime["ocr"]["prompts"]["table"] = "识别表格";
    runtime["thread_num"] = 99;runtime["use_mmap"] = true;runtime["kvcache_mmap"] = true;
    ModelJson model{{"is_visual",true},{"image_pad",62000},{"model_type","custom"},{"jinja",{{"chat_template","template"}}}};
    std::vector<ArtifactInfo> artifacts;
    for (const auto& name : {"config.json","ocr_runtime.json","llm_config.json","llm.mnn","llm.mnn.weight","visual.mnn","visual.mnn.weight","tokenizer.txt","embeddings_bf16.bin"}) {
        std::ofstream(root / name) << "fixture";
        ArtifactInfo artifact;artifact.model="recognition";artifact.path=(root/name).u8string();
        artifact.sha256=std::string(64,'a');artifact.contract_status="contract_verified";artifacts.push_back(artifact);
    }
    write(root/"config.json",ModelJson::object());write(root/"ocr_runtime.json",runtime);write(root/"llm_config.json",model);
    const auto selected = ocr_model_config(artifacts, 2);
    require(selected.name == "按字段配置的任意模型" && selected.image_pad == 62000, "模型名称或图像 token 未从字段读取");
    require(selected.prompt("text")=="识别文字" && selected.prompt("formula")=="识别公式" && selected.prompt("table")=="识别表格", "任务提示词未分派");
    require(selected.prompt("unknown")=="识别文字", "未知任务应回退正文提示词");
    require(selected.runtime.at("thread_num")==2 && selected.runtime.at("mllm").at("thread_num")==2, "线程未同步");
    require(selected.runtime.at("use_mmap")==false && selected.runtime.at("kvcache_mmap")==false, "mmap 未关闭");
    runtime["tokenizer_file"]="../tokenizer.txt";write(root/"ocr_runtime.json",runtime);
    rejected([&]{ocr_model_config(artifacts,2);}, "ocr_config_artifact_missing");
    runtime["tokenizer_file"]="tokenizer.txt";runtime["sampler_type"]="unsupported";write(root/"ocr_runtime.json",runtime);
    rejected([&]{ocr_model_config(artifacts,2);}, "ocr_sampler_invalid");
    runtime["sampler_type"]="mixed";runtime["mixed_samplers"]={"mixed"};write(root/"ocr_runtime.json",runtime);
    rejected([&]{ocr_model_config(artifacts,2);}, "ocr_mixed_samplers_invalid");
    runtime["sampler_type"]="greedy";runtime.erase("mixed_samplers");write(root/"ocr_runtime.json",runtime);
    model["llm_weight"]="../unbound.weight";write(root/"llm_config.json",model);
    rejected([&]{ocr_model_config(artifacts,2);}, "ocr_config_artifact_missing");
    model.erase("llm_weight");write(root/"llm_config.json",model);
    fs::remove(root/"embeddings_bf16.bin");
    rejected([&]{ocr_model_config(artifacts,2);}, "ocr_artifact_binding_invalid");
    std::cout << "PASS 配置驱动任务、图像 token、工件约束、采样错误、线程/mmap 与旧 Ovis 兼容配置\n";
}
