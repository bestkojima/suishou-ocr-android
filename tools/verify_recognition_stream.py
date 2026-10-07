"""测试实际流缓冲的字节边界、区域重试与隔离；C ABI 模型调用受控，不代表模型推理。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library', type=Path, required=True, help='同源 Android 流式适配构建的 Linux dococr_c')
parser.add_argument('--output', type=Path, default=ROOT / 'verification/live-ocr/buffer')
args = parser.parse_args()
library = args.library.resolve()
args.output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='ocr-stream-buffer-') as temporary:
    probe = Path(temporary) / 'probe'
    subprocess.run(['c++', '-std=c++17', '-pthread', '-Wl,--export-dynamic',
                    '-I' + str(ROOT / 'app/src/main/cpp'), '-I' + str(ROOT.parent / 'docprase/include'),
                    str(ROOT / 'app/src/test/cpp/recognition_stream_test.cpp'),
                    '-L' + str(library.parent), '-Wl,-rpath,' + str(library.parent),
                    '-l:' + library.name, '-o', str(probe)], check=True)
    captured = subprocess.check_output([probe], text=True)
states = [json.loads(line) for line in captured.splitlines()]
assert [s['regions'][0]['raw'] for s in states[:3]] == ['', '中', '中😀']
assert states[3]['regions'][0]['raw'] == '重试\n"<script>' and states[3]['regions'][0]['attempt'] == 2
assert not states[3]['regions'][0]['done'] and states[4]['regions'][0]['finishReason'] == 'failed'
assert not states[5]['regions'] and not states[6]['regions']
(args.output / 'snapshots.jsonl').write_text(captured)
(args.output / 'result.json').write_text(json.dumps({
    'environment': 'Linux 实际共享库流缓冲，受控 C ABI 模型调用，非模型推理',
    'librarySha256': hashlib.sha256(library.read_bytes()).hexdigest(),
    'passed': ['分段中文 UTF-8', '分段四字节 emoji', '重试替换及 JSON 转义', '异常结束标记', '作业隔离及资源清理'],
}, ensure_ascii=False, indent=2) + '\n')
print('PASS 分段 UTF-8/emoji、重试替换、异常结束、作业隔离及清理')
