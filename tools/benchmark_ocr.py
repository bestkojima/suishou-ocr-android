"""对比同源 OCR 的 CPU 线程与连续识别，分开记录加载／首次／复用耗时；不代表设备验收。"""
import argparse
import ctypes as c
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
import threading
import gzip
from ocr_runtime_checks import check_runtime, memory_usage, check_cache_directory

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library', type=Path, required=True, help='使用 Android 源码适配层编译的 Linux dococr_c 库')
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--threads', type=int, choices=[1,2,4], default=1)
parser.add_argument('--runs', type=int, default=2)
parser.add_argument('--input', type=Path, default=ROOT / 'verification/ticket5/source.png')
parser.add_argument('--allow-partial', action='store_true', help='保留失败区域用于分辨率诊断；默认仍要求完整成功')
parser.add_argument('--expect-mmap', choices=['false','true'], default='false', help='验证实际配置；true 仅用于历史对照库')
parser.add_argument('--engine-config', type=Path, help='使用已准备的模型目录绑定（支持 Ovis／GLM）；不重复扫描权重 SHA')
parser.add_argument('--gzip-timeline', action='store_true', help='无损压缩生成时间线，适合完整长页面')
parser.add_argument('--export-assets', action='store_true', help='保存完整 DocumentIR／Markdown 及全部真实图片资源，单独记录落盘耗时')
parser.add_argument('--region-type', choices=['text','table','formula'], help='Android 区域重识别：直接识别输入裁图，不执行版面分析')
args = parser.parse_args()
expected_mmap = args.expect_mmap == 'true'
if args.runs < 1: parser.error("runs必须为正整数")
args.output.mkdir(parents=True, exist_ok=True)
engine_root = ROOT.parent / 'docprase'
config = json.loads((args.engine_config or ROOT / 'app/src/main/assets/ocr/config.json').read_text())
config['platform']['threads'] = args.threads
if not args.engine_config:
    config['models']['layout']['root'] = str(engine_root / 'models/doclayout')
    config['models']['recognition']['root'] = str(engine_root / 'models/ovis')

# 模拟下载完成边界：在计时加载之前验证输入模型，加载阶段不再扫描权重。
for artifact in ([] if args.engine_config else json.loads((ROOT / 'app/src/main/assets/ocr/models.json').read_text())):
    folder = 'doclayout' if 'PP-DocLayout' in artifact['repo'] else 'ovis'
    path = engine_root / 'models' / folder / artifact['path']
    assert path.stat().st_size == artifact['size'], path
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    assert digest == artifact['sha256'], path
if args.engine_config:
    # 这些工件在下载完成时已经校验；本次只确认所绑定文件仍然存在。
    for model in config['models'].values():
        for artifact in model['artifacts']:
            assert (Path(model['root']) / artifact['path']).is_file(), artifact['path']

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

library = args.library.resolve()
lib = c.CDLL(str(library))
# 记录进程实际映射的原生库，避免只凭 CMake 配置推断本次使用的 MNN。
loaded_libraries = {}
for line in Path('/proc/self/maps').read_text().splitlines():
    fields = line.split(maxsplit=5)
    if len(fields) == 6 and fields[5].startswith('/'):
        mapped = Path(fields[5]).resolve()
        if mapped == library or mapped.name.startswith(('libMNN', 'libllm')):
            loaded_libraries[str(mapped)] = hashlib.sha256(mapped.read_bytes()).hexdigest()
for name, parameters in {
    'create': [View, c.POINTER(c.c_uint64)], 'destroy': [c.c_uint64],
    'job_create': [c.c_uint64, c.POINTER(c.c_uint64)], 'job_run': [c.c_uint64, c.POINTER(Input)],
    'job_manifest': [c.c_uint64, c.POINTER(Bytes)], 'job_result': [c.c_uint64, c.POINTER(Result)], 'job_destroy': [c.c_uint64],
    'job_asset_count': [c.c_uint64, c.POINTER(c.c_size_t)],
    'job_asset': [c.c_uint64, c.c_size_t, c.POINTER(Bytes), c.POINTER(Bytes)],
    'bytes_free': [c.POINTER(Bytes)], 'last_error': [c.POINTER(Bytes)],
}.items():
    getattr(lib, 'dococr_' + name).argtypes = parameters

def take(value):
    data = c.string_at(value.data, value.size)
    assert lib.dococr_bytes_free(c.byref(value)) == 0
    return data

def check(code):
    if code != 0:
        error = Bytes()
        lib.dococr_last_error(c.byref(error))
        raise RuntimeError(f'{code}: {take(error).decode()}')

encoded = json.dumps(config).encode()
image = args.input.read_bytes()
buffer = (c.c_uint8 * len(image)).from_buffer_copy(image)
request = Input(c.sizeof(Input), buffer, len(image), 1, 0, 0, 0, 0, 0, 0, 0, 0)
report = {'environment': 'Linux x86_64，同源 Android 加载适配＋真实模型；非设备推理',
          'library': str(library), 'librarySha256': hashlib.sha256(library.read_bytes()).hexdigest(),
          'loadedNativeLibrariesSha256': loaded_libraries,
          'inputPath': str(args.input.resolve()), 'inputSha256': hashlib.sha256(image).hexdigest(),
          'modelsVerifiedBeforeLoading': args.engine_config is None,
          'modelIntegrityBoundary': '已准备绑定；本次不扫描权重 SHA' if args.engine_config else '计时前模拟下载完成校验',
          'config': config,
          'threads': args.threads, 'expectedMmap': expected_mmap, 'memoryBeforeLoading': memory_usage(), 'loads': []}
with tempfile.TemporaryDirectory(prefix='android-ocr-runtime-') as cache:
    os.environ['TMPDIR'] = cache
    for engine_attempt in range(1):
        engine, job = c.c_uint64(), c.c_uint64()
        started = time.monotonic()
        check(lib.dococr_create(View(encoded, len(encoded)), c.byref(engine)))
        load_seconds = time.monotonic() - started
        report['memoryAfterLoading'] = memory_usage()
        try:
            for attempt in range(args.runs):
                check(lib.dococr_job_create(engine, c.byref(job)))
                lib.android_ocr_run_streaming.argtypes = [c.c_uint64, c.POINTER(Input)]
                if args.region_type:
                    lib.android_ocr_run_region.argtypes = [c.c_uint64, c.POINTER(Input), c.c_char_p]
                lib.android_ocr_stream_snapshot.argtypes = [c.c_uint64]
                lib.android_ocr_stream_snapshot.restype = c.c_char_p
                lib.android_ocr_stream_forget.argtypes = [c.c_uint64]
                finished = threading.Event()
                outcome = []
                timeline = []
                completed_attempts = set()
                start = time.monotonic()
                def run():
                    try:
                        outcome.append(lib.android_ocr_run_region(job, c.byref(request), args.region_type.encode()) if args.region_type else
                                       lib.android_ocr_run_streaming(job, c.byref(request)))
                    finally: finished.set()
                worker = threading.Thread(target=run)
                worker.start()
                previous = 0
                while not finished.is_set():
                    snapshot = json.loads(lib.android_ocr_stream_snapshot(job).decode('utf-8'))
                    if snapshot['revision'] > previous:
                        timeline.append({'seconds': time.monotonic()-start, 'beforeReturn': not finished.is_set(), 'stream': snapshot})
                        previous = snapshot['revision']
                        if len(timeline) == 1: print('observed real stream started', flush=True)
                        for region in snapshot['regions']:
                            key = (region['requestId'], region['attempt'])
                            if region['done'] and key not in completed_attempts:
                                completed_attempts.add(key)
                                print(f"REGION {region['requestId']} attempt={region['attempt']} "
                                      f"{region['elapsedMs']/1000:.3f}s stop={region['finishReason']}", flush=True)
                    finished.wait(.15)
                worker.join()
                check(outcome[0])
                snapshot = json.loads(lib.android_ocr_stream_snapshot(job).decode('utf-8'))
                timeline.append({'seconds': time.monotonic()-start, 'beforeReturn': False, 'stream': snapshot})
                visible = [item for item in timeline if item['beforeReturn'] and any(r['raw'] and not r['done'] for r in item['stream']['regions'])]
                assert visible, '真实模型必须在区域生成及同步 run 返回之前输出正文'
                stream_report = {'firstLiveSeconds': visible[0]['seconds'], 'totalSeconds': time.monotonic()-start, 'snapshots': len(timeline), 'regions': snapshot['regions']}
                timeline_data = (json.dumps(timeline, ensure_ascii=False, indent=2)+'\n').encode()
                if args.gzip_timeline:
                    with gzip.open(args.output / f'timeline-{attempt+1}.json.gz', 'wb') as stream:
                        stream.write(timeline_data)
                else:
                    (args.output / f'timeline-{attempt+1}.json').write_bytes(timeline_data)
                print(f"PASS real live output: first={visible[0]['seconds']:.3f}s, total={stream_report['totalSeconds']:.3f}s, snapshots={len(timeline)}", flush=True)
                manifest_json, manifest_data = check_runtime(lib, job, Bytes, take, expected_mmap)
                assert manifest_json['effective_parameters']['threads'] == args.threads
                assert manifest_json['runtime_configuration']['ovis_threads'] == args.threads
                (args.output / f'manifest-{attempt+1}.json').write_bytes(manifest_data)
                result = Result()
                result.struct_size = c.sizeof(Result)
                check(lib.dococr_job_result(job, c.byref(result)))
                document, markdown = take(result.json), take(result.markdown)
                ir = json.loads(document)
                save_started = time.monotonic()
                (args.output / f'run-{attempt + 1}.json').write_bytes(document)
                (args.output / f'run-{attempt + 1}.md').write_bytes(markdown)
                if args.export_assets:
                    export = (args.output / f'run-{attempt + 1}').resolve()
                    export.mkdir(parents=True, exist_ok=True)
                    (export / 'document.json').write_bytes(document)
                    (export / 'document.md').write_bytes(markdown)
                    count = c.c_size_t()
                    check(lib.dococr_job_asset_count(job, c.byref(count)))
                    for index in range(count.value):
                        name, data = Bytes(), Bytes()
                        check(lib.dococr_job_asset(job, index, c.byref(name), c.byref(data)))
                        target = (export / take(name).decode()).resolve()
                        assert target.is_relative_to(export), '资源路径必须在导出目录内'
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(take(data))
                    stream_report['savedAssets'] = count.value
                stream_report['saveResultsSeconds'] = time.monotonic() - save_started
                blocks = sum(len(page['blocks']) for page in ir['pages'])
                if args.region_type:
                    assert blocks == 1 and ir['pages'][0]['blocks'][0]['type'] == args.region_type
                assert (ir['status'] == 'ok' or args.allow_partial and ir['status'] == 'partial') and blocks > 0, ir['status']
                by_request = {block['provenance']['request_id']: block for page in ir['pages'] for block in page['blocks'] if block.get('provenance', {}).get('request_id')}
                for region in snapshot['regions']:
                    attempts = by_request[region['requestId']]['provenance']['recognition']['attempts']
                    assert region['raw'] == attempts[-1]['output']['raw_output'], region['requestId']
                stream_report['rawMatchesSavedAttempts'] = True
                stream_report['phaseTotalsMs'] = {key: sum(region[key] for region in snapshot['regions']) for key in ('visionMs', 'prefillMs', 'decodeMs', 'elapsedMs')}
                # Stream 同 requestId 的重试会覆盖上一尝试；不能把最后尝试之和说成全流程各阶段之和。
                stream_report['phaseTotalsScope'] = '每个生成区域的最后一次尝试；重试前阶段不含在此合计中'
                recognition_regions = [region for region in manifest_json['regions'] if region.get('recognition', {}).get('attempts')]
                stream_report['pipelineTimingsMs'] = manifest_json['timings_ms']
                stream_report['pipelineTimingStatus'] = manifest_json['timing_status']
                stream_report['recognitionRegions'] = len(recognition_regions)
                stream_report['attempts'] = sum(len(region['recognition']['attempts']) for region in recognition_regions)
                stream_report['retriedRegions'] = [region['request_id'] for region in recognition_regions if len(region['recognition']['attempts']) > 1]
                stream_report['outputTokensLastAttempts'] = sum(region['outputTokens'] for region in snapshot['regions'])
                if args.region_type:
                    stream_report['regionType'] = args.region_type
                stream_report['modelName'] = manifest_json['runtime_configuration'].get('model_name')

                report['loads'].append({'attempt': attempt + 1, 'seconds': load_seconds if attempt == 0 else 0, 'engineReused': attempt > 0,
                                        'status': ir['status'], 'blocks': blocks, 'stream': stream_report, 'memory': memory_usage(),
                                        'useMmap': manifest_json['runtime_configuration']['use_mmap'], 'markdownSha256': hashlib.sha256(markdown).hexdigest()})
                check(lib.dococr_job_destroy(job))
                lib.android_ocr_stream_forget(job)
                job.value=0
                print(f'PASS image {attempt + 1}: engineReused={attempt > 0}, load={load_seconds if attempt == 0 else 0:.3f}s, {blocks} real blocks', flush=True)
        finally:
            if job.value:
                check(lib.dococr_job_destroy(job))
                lib.android_ocr_stream_forget(job)
            check(lib.dococr_destroy(engine))
    report['temporaryDirectory'] = check_cache_directory(cache, expected_mmap)
(args.output / 'benchmark.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
