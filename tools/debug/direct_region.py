"""对从真实触发页提取的原始区域调用同一生产识别 backend，移除 UI、版面与其他区域。"""
from pathlib import Path
import argparse
import ctypes as c
import hashlib
import json
import os
import tempfile
import time
import sys
from PIL import Image
from ocr_degeneracy import ROOT, repetition
sys.path.insert(0, str(ROOT / 'tools'))
from ocr_runtime_checks import memory_usage

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library', type=Path, required=True)
parser.add_argument('--input', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--sampler-config', type=Path)
parser.add_argument('--engine-config', type=Path, help='已绑定模型目录的引擎配置；只复核登记大小，不重新扫描权重')
parser.add_argument('--max-tokens', type=int, default=64)
parser.add_argument('--runs', type=int, default=3)
args = parser.parse_args()
assert 'diagnostic' in args.library.name and args.runs > 0 and 1 <= args.max_tokens <= 4096
args.output.mkdir(parents=True, exist_ok=True)
overrides = json.loads(args.sampler_config.read_text()) if args.sampler_config else {}
allowed = {'sampler_type', 'penalty_sampler', 'mixed_samplers', 'repetition_penalty',
           'presence_penalty', 'frequency_penalty', 'penalty_window', 'n_gram', 'ngram_factor',
           'temperature', 'top_k', 'top_p', 'min_p', 'tfs_z', 'typical'}
assert set(overrides) <= allowed
if args.sampler_config: os.environ['OCR_DIAG_SAMPLER_JSON'] = json.dumps(overrides)
else: os.environ.pop('OCR_DIAG_SAMPLER_JSON', None)
image = Image.open(args.input).convert('RGB'); rgb = image.tobytes()
config = json.loads((args.engine_config or ROOT / 'app/src/main/assets/ocr/config.json').read_text())
if not args.engine_config:
    for kind, folder in [('layout', 'doclayout'), ('recognition', 'ovis')]:
        config['models'][kind]['root'] = str(ROOT.parent / 'docprase/models' / folder)
    for artifact in json.loads((ROOT / 'app/src/main/assets/ocr/models.json').read_text()):
        folder = 'doclayout' if 'PP-DocLayout' in artifact['repo'] else 'ovis'
        assert (ROOT.parent / 'docprase/models' / folder / artifact['path']).stat().st_size == artifact['size']
lib = c.CDLL(str(args.library.resolve()))
lib.ocr_diag_create.argtypes = [c.c_char_p, c.c_int]
lib.ocr_diag_last_error.restype = c.c_char_p
lib.ocr_diag_region.argtypes = [c.POINTER(c.c_uint8), c.c_size_t, c.c_int, c.c_int, c.c_int]
lib.ocr_diag_region.restype = c.c_char_p
report = dict(environment='Linux x86_64，临时 ABI 直接调用生产区域 backend；非设备验收',
              library=str(args.library.resolve()), librarySha256=hashlib.sha256(args.library.read_bytes()).hexdigest(),
              input=str(args.input.resolve()), inputSha256=hashlib.sha256(args.input.read_bytes()).hexdigest(),
              inputSize=list(image.size), samplerOverrides=overrides, maxTokens=args.max_tokens,
              memoryBeforeLoading=memory_usage(), runs=[])
with tempfile.TemporaryDirectory(prefix='ocr-direct-loop-') as cache:
    os.environ['TMPDIR'] = cache
    start = time.monotonic()
    assert lib.ocr_diag_create(json.dumps(config).encode(), 4) == 0, lib.ocr_diag_last_error().decode()
    report['loadSeconds'] = time.monotonic() - start
    report['memoryAfterLoading'] = memory_usage()
    try:
        buffer = (c.c_uint8 * len(rgb)).from_buffer_copy(rgb)
        for attempt in range(1, args.runs + 1):
            trace = args.output / ('native-trace-' + str(attempt) + '.jsonl')
            trace.unlink(missing_ok=True)
            os.environ['OCR_DIAG_TRACE'] = str(trace.resolve())
            start = time.monotonic()
            result = json.loads(lib.ocr_diag_region(buffer, len(rgb), image.width, image.height, args.max_tokens).decode())
            result.update(run=attempt, seconds=time.monotonic()-start)
            result['memoryAfterRecognition'] = memory_usage()
            assert 'diagnosticError' not in result, result
            assert trace.is_file(), '未采集真实 token 证据'
            native = json.loads(trace.read_text().strip())
            actual = native['llmConfig']
            assert actual['use_mmap'] is False and actual['kvcache_mmap'] is False
            assert all(actual.get(key) == value for key, value in overrides.items())
            result['repeat'] = repetition(result['raw'])
            result['verdict'] = 'REPETITION' if result['repeat'] else 'NO_REPEAT' if result['raw'] else 'ERROR'
            result['native'] = native
            report['runs'].append(result)
            (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            print(result['verdict'], args.input.name, attempt, round(result['seconds'], 3), repr(result['raw'][:110]), flush=True)
    finally: lib.ocr_diag_destroy()
    assert not list(Path(cache).glob('ovis-mmap-*'))
raise SystemExit(1 if any(r['repeat'] for r in report['runs']) else 2 if any(r['verdict'] == 'ERROR' for r in report['runs']) else 0)
