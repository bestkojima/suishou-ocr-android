# Android 下载器在完成时校验 SHA，加载前由 RecognitionModels 检查私有下载记录。
# 为该平台生成配置／ABI／版面／识别四份适配源码；保留相邻 docprase 源码和桌面校验行为。
# 每个替换必须精确匹配一次，源引擎更新时失败并要求核对，避免静默漏掉校验路径。
function(android_replace_once before after)
  string(LENGTH "${android_source_text}" original_length)
  string(LENGTH "${before}" match_length)
  string(REPLACE "${before}" "" without_match "${android_source_text}")
  string(LENGTH "${without_match}" remaining_length)
  math(EXPR removed_length "${original_length} - ${remaining_length}")
  if(NOT removed_length EQUAL match_length)
    message(FATAL_ERROR "Android 引擎适配与 ${android_source_path} 不匹配，请核对 docprase 源码")
  endif()
  string(REPLACE "${before}" "${after}" android_source_text "${android_source_text}")
  set(android_source_text "${android_source_text}" PARENT_SCOPE)
endfunction()

function(android_engine_source target filename)
  set(android_source_path "${DOCOCR_SOURCE_ROOT}/src/${filename}")
  set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${android_source_path}")
  file(READ "${android_source_path}" android_source_text)
  if(filename STREQUAL "config.cpp")
    android_replace_once("std::string actual = sha256_file(resolved);" "std::string actual = expected;")
    android_replace_once([=[if (threads != 1) throw ConfigError("unsupported_parameter", "threads: only 1 is implemented");
    plan->threads = 1;]=] [=[if (threads != 1 && threads != 2 && threads != 4) throw ConfigError("unsupported_parameter", "threads: expected 1, 2 or 4");
    plan->threads = int(threads);]=])
  elseif(filename STREQUAL "layout_mnn_backend.cpp")
    android_replace_once(" ||\n            sha256_file(spec.artifacts[0].path) != model_hash" "")
    android_replace_once([=[config.numThread = 1;]=] [=[config.numThread = android_inference_threads();]=])
    android_replace_once([=[#include "config.hpp"]=] [=[#include "config.hpp"
#include "android_engine_runtime.hpp"]=])
  elseif(filename STREQUAL "printed_page_mnn_backend.cpp")
    android_replace_once("artifact.contract_status != \"contract_verified\" ||\n                sha256_file(artifact.path) != artifact.sha256"
      "artifact.contract_status != \"contract_verified\"")
    android_replace_once("if (!llm_ || !llm_->load())" "if (!llm_ || !load_android_llm(*llm_, spec.config_hash))")
    android_replace_once("GenerationOutput output;" "GenerationOutput output;\n        AndroidGenerationStream raw(*generation, threads_);")
    android_replace_once("std::ostringstream raw;" "// AndroidGenerationStream 已在视觉处理前建立，正文随模型写入发布。")
    android_replace_once("        return {output};\n    }\n    bool reset()" "        raw.finish(output, state);\n        return {output};\n    }\n    bool reset()")
    android_replace_once([=[temporary.write(effective);]=] [=[threads_ = android_inference_threads();
        effective = android_thread_config(effective);
        temporary.write(effective);]=])
    android_replace_once([=[count("\"thread_num\":1") != 2]=] [=[count("\"thread_num\":" + std::to_string(threads_)) != 2]=])
    android_replace_once([=[EngineCapabilities capabilities_{true, true, true, 1};]=] [=[int threads_ = 1;
    EngineCapabilities capabilities_{true, true, true, 1};]=])
    android_replace_once([=["/RuntimeConfig=" + runtime_config_hash_;]=] [=["/RuntimeConfig=" + runtime_config_hash_ + "/CPUThreads=" + std::to_string(threads_);]=])
    # loader 使用 config.hpp 中的 json_quote；头文件放在其定义之后。
    android_replace_once("#include \"config.hpp\"" "#include \"config.hpp\"\n#include \"android_engine_loading.hpp\"\n#include \"android_engine_runtime.hpp\"\n#include \"android_recognition_stream.hpp\"")
  elseif(filename STREQUAL "abi.cpp")
    android_replace_once([=[#include "config.hpp"]=] [=[#include "config.hpp"
#include "android_engine_runtime.hpp"]=])
    android_replace_once([=[engine->backend->load(load_spec)]=] [=[dococr::load_android_backend(*engine->backend, load_spec, engine->plan ? engine->plan->threads : 1)]=])
    android_replace_once([=[replacement->load(spec)]=] [=[dococr::load_android_backend(*replacement, spec, job->plan ? job->plan->threads : 1)]=])
    android_replace_once([=["\"effective_parameters\":{\"threads\":1,\"max_new_tokens\":"]=] [=["\"effective_parameters\":{\"threads\":" + std::to_string(plan.threads) + ",\"max_new_tokens\":"]=])
    android_replace_once([=[",\"runtime_configuration\":{\"layout_threads\":1,\"ovis_threads\":1,"]=] [=[",\"runtime_configuration\":{\"layout_threads\":" + std::to_string(plan.threads) + ",\"ovis_threads\":" + std::to_string(plan.threads) + ","]=])
    android_replace_once([=["\"prompt_cache\":false,\"use_mmap\":false,\"kvcache_mmap\":false,"]=] [=["\"prompt_cache\":false,\"use_mmap\":true,\"kvcache_mmap\":false,"]=])
  else()
    message(FATAL_ERROR "未知 Android 引擎适配源码：${filename}")
  endif()
  if(android_source_text MATCHES "sha256_file\\(")
    message(FATAL_ERROR "${filename} 仍含加载时文件 SHA 校验，请核对新源代码")
  endif()
  set(output "${CMAKE_CURRENT_BINARY_DIR}/android-engine/${filename}")
  file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/android-engine")
  file(WRITE "${output}" "${android_source_text}")
  get_target_property(sources ${target} SOURCES)
  list(FILTER sources EXCLUDE REGEX "(^|/)${filename}$")
  set_property(TARGET ${target} PROPERTY SOURCES "${sources}")
  target_sources(${target} PRIVATE "${output}")
  target_include_directories(${target} PRIVATE "${DOCOCR_SOURCE_ROOT}/src" "${CMAKE_CURRENT_LIST_DIR}")
endfunction()

android_engine_source(dococr_core config.cpp)
android_engine_source(dococr_c abi.cpp)
android_engine_source(dococr_c layout_mnn_backend.cpp)
android_engine_source(dococr_c printed_page_mnn_backend.cpp)

target_sources(dococr_c PRIVATE "${CMAKE_CURRENT_LIST_DIR}/android_recognition_stream.cpp")
