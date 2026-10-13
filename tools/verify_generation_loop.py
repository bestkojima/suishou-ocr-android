"""编译实际流缓冲的循环检测回归测试；不运行模型。"""
from pathlib import Path
import subprocess
import tempfile
import json

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="ocr-loop-test-") as directory:
    binary = Path(directory) / "test"
    includes = ["app/src/main/cpp", "native/docprase/include", "native/docprase/src",
                "native/docprase/third_party/nlohmann", "native/MNN/include", "native/MNN/transformers/llm/engine/include"]
    subprocess.run(["c++", "-std=c++17", "-O2", "-pthread", *["-I" + str(root / p) for p in includes],
                    str(root / "app/src/test/cpp/generation_loop_test.cpp"),
                    str(root / "native/docprase/src/output_assessment.cpp"),
                    str(root / "app/src/main/cpp/android_recognition_stream.cpp"), "-o", str(binary)], check=True)
    subprocess.run([binary], check=True)
    # 历史真实模型输出逐字节回放，避免用合成数据代替已知故障。
    cases = root / 'verification/model-pipeline-speed/problem-page-rerun/ovis-anomalies.json'
    for case in json.loads(cases.read_text())['knownCases']:
        for index, attempt in enumerate(case['attempts']):
            path = Path(directory) / f"{case['kind']}-{index}.txt"
            path.write_text(attempt['rawOutput'])
            print(case['kind'], flush=True)
            subprocess.run([binary, path, 'text', 'stop'], check=True)
    for model in ['ovis', 'glm']:
        path = root / f'verification/model-pipeline-speed/problem-page-rerun/{model}/run-1.json'
        count = 0
        for page in json.loads(path.read_text())['pages']:
            for block in page['blocks']:
                raw = block.get('provenance', {}).get('raw_output')
                if block.get('status') != 'ok' or not raw:
                    continue
                sample = Path(directory) / 'normal.txt'
                sample.write_text(raw)
                subprocess.run([binary, sample, block['type'], 'keep'], check=True, stdout=subprocess.DEVNULL)
                count += 1
        print(f'PASS {model} 已有正常输出 {count} 区无误停')
