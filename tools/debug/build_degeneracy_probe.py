"""在临时目录复用同源 host 库的对象，加入采样实验与 token 证据；不改生产源码。"""
from pathlib import Path
import argparse
import hashlib
import json
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
base = args.baseline.resolve(); output = args.output.resolve()
assert output != base and not output.is_relative_to(base), '诊断输出不得覆盖基线构建目录'
output.mkdir(parents=True, exist_ok=True)
original = base / 'android-engine/printed_page_mnn_backend.cpp'
source = original.read_text()
def replace(before, after):
    global source
    assert source.count(before) == 1, before
    source = source.replace(before, after)
source = '#include <nlohmann/json.hpp>\n#include <cstdlib>\n' + source
replace('        temporary.write(effective);', '''        // [DEBUG-b002] 临时 probe 专用：只接受诊断脚本设置的采样参数。
        if (const char* settings = std::getenv("OCR_DIAG_SAMPLER_JSON")) {
            auto config = nlohmann::json::parse(effective);
            config.update(nlohmann::json::parse(settings));
            effective = config.dump();
        }
        temporary.write(effective);''')
if 'count("\\\"sampler_type\\\":\\\"greedy\\\"") != 1' in source:
    replace('count("\\\"sampler_type\\\":\\\"greedy\\\"") != 1',
            'count("\\\"sampler_type\\\":" + json_quote(nlohmann::json::parse(effective).at("sampler_type").get<std::string>())) != 1')
replace('        raw.finish(output, state);', '''        // [DEBUG-b002] 捕获真实生成 token、实际配置及原始区域/视觉画布。
        if (state) if (const char* path = std::getenv("OCR_DIAG_TRACE")) {
            nlohmann::json trace;
            trace["tag"] = "[DEBUG-b002]";
            trace["requestId"] = generation->request_id;
            trace["task"] = generation->task;
            trace["prompt"] = user_content;
            trace["visualTokens"] = output.visual_tokens;
            trace["maxTokens"] = generation->max_new_tokens;
            trace["outputTokens"] = state->output_tokens;
            trace["generatedString"] = state->generate_str;
            trace["raw"] = output.raw_output;
            trace["stopReason"] = output.stop_reason;
            trace["visionUs"] = state->vision_us;
            trace["prefillUs"] = state->prefill_us;
            trace["decodeUs"] = state->decode_us;
            trace["llmConfig"] = nlohmann::json::parse(llm_->dump_config());
            trace["sourceSize"] = {generation->image.width, generation->image.height};
            trace["sourceBox"] = {generation->source_box.x0, generation->source_box.y0,
                                  generation->source_box.x1, generation->source_box.y1};
            std::ofstream log(path, std::ios::app); log << trace.dump() << "\\n";
            std::ofstream crop(std::string(path) + "." + generation->request_id + ".crop.png", std::ios::binary);
            const auto data = png(generation->image); crop.write(reinterpret_cast<const char*>(data.data()), data.size());
            std::filesystem::copy_file(image.path(), std::string(path) + "." + generation->request_id + ".visual.png",
                                       std::filesystem::copy_options::overwrite_existing);
        }
        raw.finish(output, state);''')
patched = output / 'printed_page_mnn_backend.cpp'; patched.write_text(source)
ninja = '/home/dr/Android/Sdk/cmake/3.31.6/bin/ninja'
target = 'docprase/CMakeFiles/dococr_c.dir/__/android-engine/printed_page_mnn_backend.cpp.o'
commands = subprocess.check_output([ninja, '-C', str(base), '-t', 'commands', target], text=True).splitlines()
command = next(line for line in reversed(commands) if str(original) in line and ' -c ' in line)
compile_args = shlex.split(command)
obj = output / 'printed_page_mnn_backend.cpp.o'
compile_args[compile_args.index('-o') + 1] = str(obj)
compile_args[compile_args.index('-c') + 1] = str(patched)
if '-MF' in compile_args: compile_args[compile_args.index('-MF') + 1] = str(obj) + '.d'
compile_args += ['-I' + str(ROOT / 'native/docprase/third_party')]
subprocess.run(compile_args, cwd=base, check=True)
direct_args = list(compile_args)
direct_obj = output / 'direct_region.cpp.o'
direct_args[direct_args.index('-o') + 1] = str(direct_obj)
direct_args[direct_args.index('-c') + 1] = str(ROOT / 'tools/debug/direct_region.cpp')
if '-MF' in direct_args: direct_args[direct_args.index('-MF') + 1] = str(direct_obj) + '.d'
subprocess.run(direct_args, cwd=base, check=True)
commands = subprocess.check_output([ninja, '-C', str(base), '-t', 'commands', 'docprase/libdococr_c.so'], text=True).splitlines()
link = next(line for line in reversed(commands) if ' -shared ' in line and ' -o docprase/libdococr_c.so ' in line)
link = link.removeprefix(': && ').removesuffix(' && :')
link_args = shlex.split(link)
library = output / 'libdococr_c_diagnostic.so'
link_args[link_args.index('-o') + 1] = str(library)
link_args = [str(obj) if arg == target else '-Wl,-soname,libdococr_c_diagnostic.so' if arg == '-Wl,-soname,libdococr_c.so' else arg for arg in link_args]
link_args.append(str(direct_obj))
subprocess.run(link_args, cwd=base, check=True)
record = {'baselineDirectory': str(base), 'originalSourceSha256': hashlib.sha256(original.read_bytes()).hexdigest(),
          'probeSourceSha256': hashlib.sha256(patched.read_bytes()).hexdigest(),
          'library': str(library), 'librarySha256': hashlib.sha256(library.read_bytes()).hexdigest(),
          'compile': compile_args, 'directCompile': direct_args, 'link': link_args,
          'changes': '临时加载参数覆盖、token/实际 config/图像证据；既有模型/MNN/核心对象不变'}
(output / 'build.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
print('READY diagnostic library', library)
