"""真实模型原始流重复诊断。退出 1=捕获重复，0=进入生成且无重复，2=错误或未覆盖。"""
from pathlib import Path
import argparse
import ctypes as c
import hashlib
import json
import os
import re
import tempfile
import threading
import time
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT / 'tools'))
from ocr_runtime_checks import memory_usage

def repetition(raw, minimum=32, copies=6):
    # 仅用于已知不含这种过量重复的诊断图片，不是生产截断算法。
    text = re.sub(r'\s+', '', raw)
    for width in range(1, min(128, len(text) // copies) + 1):
        for start in range(len(text) - width * copies + 1):
            unit = text[start:start + width]
            if text[start:start + width * copies] != unit * copies:
                continue
            count = copies
            while text[start + width * count:start + width * (count + 1)] == unit:
                count += 1
            if width * count >= minimum:
                return dict(unit=unit, copies=count, compactStart=start,
                            repeatedCharacters=width * count)
    return None

class View(c.Structure):
    _fields_ = [('data', c.c_char_p), ('size', c.c_size_t)]
class Bytes(c.Structure):
    _fields_ = [('data', c.POINTER(c.c_uint8)), ('size', c.c_size_t), ('allocation_id', c.c_uint64)]
class Input(c.Structure):
    _fields_ = [('struct_size', c.c_uint32), ('data', c.POINTER(c.c_uint8)), ('size', c.c_size_t),
               ('format', c.c_uint32), ('width', c.c_uint32), ('height', c.c_uint32),
               ('row_stride', c.c_size_t), ('first_page', c.c_uint32), ('last_page', c.c_uint32),
               ('dpi', c.c_uint32), ('max_page_pixels', c.c_uint64), ('timeout_ms', c.c_uint32)]
class Result(c.Structure):
    _fields_ = [('struct_size', c.c_uint32), ('json', Bytes), ('markdown', Bytes)]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path, default=ROOT / 'verification/ocr-degeneration/fixtures/fixtures.json')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', action='append', help='只运行指定样图，可重复传入')
    parser.add_argument('--runs', type=int, default=1)
    parser.add_argument('--threads', type=int, choices=[1, 2, 4], default=4)
    parser.add_argument('--timeout', type=float, default=90)
    parser.add_argument('--max-tokens', type=int, default=512)
    parser.add_argument('--sampler-config', type=Path, help='仅临时 probe 使用的采样参数 JSON；不会修改模型文件')
    parser.add_argument('--engine-config', type=Path, help='已绑定模型目录的引擎配置')
    parser.add_argument('--cancel-on-repeat', action='store_true', help='诊断命中后请求现有取消接口，记录命中前原始输出；不是产品修复')
    args = parser.parse_args()
    assert args.runs > 0 and args.timeout > 0 and args.max_tokens > 0
    assert repetition('测' * 32)['unit'] == '测'
    assert repetition('你好' * 16)['unit'] == '你好'
    assert repetition('你好，今天进行离线识别。') is None
    assert repetition('测试' * 3) is None
    args.output.mkdir(parents=True, exist_ok=True)
    overrides = json.loads(args.sampler_config.read_text()) if args.sampler_config else {}
    allowed = {'sampler_type', 'penalty_sampler', 'mixed_samplers', 'repetition_penalty',
               'presence_penalty', 'frequency_penalty', 'penalty_window', 'n_gram', 'ngram_factor',
               'temperature', 'top_k', 'top_p', 'min_p', 'tfs_z', 'typical'}
    assert set(overrides) <= allowed, '只允许采样实验参数'
    if args.sampler_config:
        assert 'diagnostic' in args.library.name, '采样实验必须使用临时 probe 库'
        os.environ['OCR_DIAG_SAMPLER_JSON'] = json.dumps(overrides)
    else:
        os.environ.pop('OCR_DIAG_SAMPLER_JSON', None)
    cases = json.loads(args.fixtures.read_text())['cases']
    if args.case:
        cases = [case for case in cases if case['id'] in args.case]
        assert {case['id'] for case in cases} == set(args.case), '样图名称不存在'
    config = json.loads((args.engine_config or ROOT / 'app/src/main/assets/ocr/config.json').read_text())
    config['platform']['threads'] = args.threads
    config['execution']['max_new_tokens'] = args.max_tokens
    if not args.engine_config:
        for kind, folder in [('layout', 'doclayout'), ('recognition', 'ovis')]:
            config['models'][kind]['root'] = str(ROOT.parent / 'docprase/models' / folder)
    # 已下载工件只复核大小；不在模型加载前重复扫描完整权重 SHA。
    for artifact in ([] if args.engine_config else json.loads((ROOT / 'app/src/main/assets/ocr/models.json').read_text())):
        folder = 'doclayout' if 'PP-DocLayout' in artifact['repo'] else 'ovis'
        assert (ROOT.parent / 'docprase/models' / folder / artifact['path']).stat().st_size == artifact['size']
    library = args.library.resolve()
    lib = c.CDLL(str(library))
    definitions = {
        'create': [View, c.POINTER(c.c_uint64)], 'destroy': [c.c_uint64],
        'job_create': [c.c_uint64, c.POINTER(c.c_uint64)], 'job_destroy': [c.c_uint64],
        'job_cancel': [c.c_uint64], 'job_result': [c.c_uint64, c.POINTER(Result)],
        'job_manifest': [c.c_uint64, c.POINTER(Bytes)], 'bytes_free': [c.POINTER(Bytes)],
        'last_error': [c.POINTER(Bytes)],
    }
    for name, parameters in definitions.items():
        getattr(lib, 'dococr_' + name).argtypes = parameters
    lib.android_ocr_run_streaming.argtypes = [c.c_uint64, c.POINTER(Input)]
    lib.android_ocr_stream_snapshot.argtypes = [c.c_uint64]
    lib.android_ocr_stream_snapshot.restype = c.c_char_p
    lib.android_ocr_stream_forget.argtypes = [c.c_uint64]
    def take(value):
        data = c.string_at(value.data, value.size)
        assert lib.dococr_bytes_free(c.byref(value)) == 0
        return data
    def check(code):
        if code:
            error = Bytes(); lib.dococr_last_error(c.byref(error))
            raise RuntimeError(str(code) + ': ' + take(error).decode())
    report = dict(environment='Linux x86_64 同源 Android 生产适配，真实模型；非设备复现',
                  library=str(library), librarySha256=hashlib.sha256(library.read_bytes()).hexdigest(),
                  threads=args.threads, maxNewTokens=args.max_tokens,
                  predicate='同一连续片段≥6次且总计≥32个非空白字符，基于原始生成流',
                  diagnosticCancellation=args.cancel_on_repeat, config=config,
                  memoryBeforeLoading=memory_usage(), runs=[])
    report['samplerOverrides'] = overrides
    def persist():
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    previous_tmp = os.environ.get('TMPDIR')
    with tempfile.TemporaryDirectory(prefix='ocr-loop-diagnosis-') as temporary:
        os.environ['TMPDIR'] = temporary
        engine = c.c_uint64(); encoded = json.dumps(config).encode(); started = time.monotonic()
        check(lib.dococr_create(View(encoded, len(encoded)), c.byref(engine)))
        report['loadSeconds'] = time.monotonic() - started
        report['memoryAfterLoading'] = memory_usage()
        print('LOADED real model', round(report['loadSeconds'], 3), 'seconds', flush=True)
        try:
            for case in cases:
                image = Path(case['path']).read_bytes()
                assert hashlib.sha256(image).hexdigest() == case['sha256']
                for attempt in range(1, args.runs + 1):
                    folder = args.output / (case['id'] + '-run-' + str(attempt)); folder.mkdir(exist_ok=True)
                    trace_path = folder / 'native-trace.jsonl'
                    trace_path.unlink(missing_ok=True)
                    os.environ['OCR_DIAG_TRACE'] = str(trace_path.resolve())
                    job = c.c_uint64(); check(lib.dococr_job_create(engine, c.byref(job)))
                    buffer = (c.c_uint8 * len(image)).from_buffer_copy(image)
                    request = Input(c.sizeof(Input), buffer, len(image), 1, 0, 0, 0, 0, 0, 0, 0, 0)
                    finished = threading.Event(); outcome = []; snapshots = []; regions = {}; hit = None; cancellation = None
                    start = time.monotonic()
                    def run():
                        try: outcome.append(lib.android_ocr_run_streaming(job, c.byref(request)))
                        finally: finished.set()
                    worker = threading.Thread(target=run); worker.start(); revision = -1
                    try:
                        while True:
                            snapshot = json.loads(lib.android_ocr_stream_snapshot(job).decode())
                            seconds = time.monotonic() - start
                            if snapshot['revision'] != revision:
                                snapshots.append(dict(seconds=seconds, beforeReturn=not finished.is_set(), stream=snapshot))
                                revision = snapshot['revision']
                                for region in snapshot['regions']:
                                    key = (region['requestId'], region['attempt']); regions[key] = region
                                    repeated = repetition(region['raw'])
                                    if repeated and hit is None:
                                        hit = dict(seconds=seconds, requestId=key[0], attempt=key[1], **repeated)
                                        print('RED', case['id'], 'real repeated unit=', repr(repeated['unit']), 'copies=', repeated['copies'], flush=True)
                            if cancellation is None and ((hit and args.cancel_on_repeat) or seconds > args.timeout):
                                cancellation = 'diagnostic_repeat' if hit else 'diagnostic_timeout'
                                check(lib.dococr_job_cancel(job))
                            if finished.is_set(): break
                            finished.wait(.05)
                        worker.join()
                        value = dict(case=case, run=attempt, seconds=time.monotonic()-start,
                                     nativeReturn=outcome[0], cancellation=cancellation, repeat=hit,
                                     regions=list(regions.values()))
                        value['memoryAfterRecognition'] = memory_usage()
                        value['verdict'] = 'REPETITION' if hit else 'ERROR' if outcome[0] else 'NO_REPEAT' if any(r['raw'] for r in regions.values()) else 'NOT_REACHED'
                        manifest = Bytes(); status = lib.dococr_job_manifest(job, c.byref(manifest))
                        if status == 0:
                            raw_manifest = take(manifest); (folder / 'manifest.json').write_bytes(raw_manifest)
                            runtime = json.loads(raw_manifest)['runtime_configuration']
                            assert runtime['use_mmap'] is False and runtime['kvcache_mmap'] is False
                            value['runtime'] = runtime
                        if trace_path.exists():
                            traces = [json.loads(line) for line in trace_path.read_text().splitlines()]
                            value['nativeTraces'] = traces
                            actual = traces[-1]['llmConfig']
                            value['actualLlmConfig'] = actual
                            assert all(actual.get(key) == requested for key, requested in overrides.items()), '实验参数未实际生效'
                            assert actual['use_mmap'] is False and actual['kvcache_mmap'] is False
                            value['runtimeSource'] = '临时 probe 的实际 dump_config；采样实验覆盖值以实际配置为准'
                            value['runtime']['sampler'] = actual['sampler_type']
                            for key in ['repetition_penalty', 'presence_penalty', 'frequency_penalty', 'penalty_window', 'temperature', 'top_k', 'top_p']:
                                value['runtime'][key] = actual[key]
                            (folder / 'actual-llm-config.json').write_text(json.dumps(actual, ensure_ascii=False, indent=2) + '\n')
                        elif args.sampler_config:
                            raise RuntimeError('缺少实际 config/token trace，不能确认参数生效')
                        result = Result(); result.struct_size = c.sizeof(Result)
                        if lib.dococr_job_result(job, c.byref(result)) == 0:
                            (folder / 'document.json').write_bytes(take(result.json))
                            (folder / 'document.md').write_bytes(take(result.markdown))
                        (folder / 'timeline.json').write_text(json.dumps(snapshots, ensure_ascii=False, indent=2) + '\n')
                        (folder / 'raw.json').write_text(json.dumps(value['regions'], ensure_ascii=False, indent=2) + '\n')
                        report['runs'].append(value); persist()
                        print(value['verdict'], case['id'], 'run', attempt, 'seconds', round(value['seconds'], 3), 'regions', len(regions), flush=True)
                    finally:
                        if worker.is_alive(): lib.dococr_job_cancel(job); worker.join()
                        check(lib.dococr_job_destroy(job)); lib.android_ocr_stream_forget(job)
        finally:
            check(lib.dococr_destroy(engine))
            if previous_tmp is None: os.environ.pop('TMPDIR', None)
            else: os.environ['TMPDIR'] = previous_tmp
        report['newMmapWeightCaches'] = list(Path(temporary).glob('ovis-mmap-*'))
        assert not report['newMmapWeightCaches']
    persist()
    return 1 if any(run['verdict'] == 'REPETITION' for run in report['runs']) else 2 if any(run['verdict'] in ('ERROR', 'NOT_REACHED') for run in report['runs']) else 0

if __name__ == '__main__':
    raise SystemExit(main())
