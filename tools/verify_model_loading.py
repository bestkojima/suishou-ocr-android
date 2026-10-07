"""验证 Android 加载适配源码在 Linux 同源引擎中的 mmap 加载及真实推理；不代表设备验收。"""
import argparse
import ctypes as c
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library', type=Path, required=True, help='使用 Android 源码适配层编译的 Linux dococr_c 库')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
engine_root = ROOT.parent / 'docprase'
config = json.loads((ROOT / 'app/src/main/assets/ocr/config.json').read_text())
config['models']['layout']['root'] = str(engine_root / 'models/doclayout')
config['models']['recognition']['root'] = str(engine_root / 'models/ovis')

# 模拟下载完成边界：在计时加载之前验证输入模型，加载阶段不再扫描权重。
for artifact in json.loads((ROOT / 'app/src/main/assets/ocr/models.json').read_text()):
    folder = 'doclayout' if 'PP-DocLayout' in artifact['repo'] else 'ovis'
    path = engine_root / 'models' / folder / artifact['path']
    assert path.stat().st_size == artifact['size'], path
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    assert digest == artifact['sha256'], path

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
for name, parameters in {
    'create': [View, c.POINTER(c.c_uint64)], 'destroy': [c.c_uint64],
    'job_create': [c.c_uint64, c.POINTER(c.c_uint64)], 'job_run': [c.c_uint64, c.POINTER(Input)],
    'job_result': [c.c_uint64, c.POINTER(Result)], 'job_destroy': [c.c_uint64],
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
image = (ROOT / 'verification/ticket5/source.png').read_bytes()
buffer = (c.c_uint8 * len(image)).from_buffer_copy(image)
request = Input(c.sizeof(Input), buffer, len(image), 1, 0, 0, 0, 0, 0, 0, 0, 0)
report = {'environment': 'Linux x86_64，同源 Android 加载适配＋真实模型；非设备推理',
          'library': str(library), 'librarySha256': hashlib.sha256(library.read_bytes()).hexdigest(),
          'inputSha256': hashlib.sha256(image).hexdigest(), 'modelsVerifiedBeforeLoading': True,
          'loads': []}
with tempfile.TemporaryDirectory(prefix='android-ocr-mmap-') as cache:
    os.environ['TMPDIR'] = cache
    for attempt in range(2):
        engine, job = c.c_uint64(), c.c_uint64()
        started = time.monotonic()
        check(lib.dococr_create(View(encoded, len(encoded)), c.byref(engine)))
        load_seconds = time.monotonic() - started
        try:
            check(lib.dococr_job_create(engine, c.byref(job)))
            check(lib.dococr_job_run(job, c.byref(request)))
            result = Result()
            result.struct_size = c.sizeof(Result)
            check(lib.dococr_job_result(job, c.byref(result)))
            document, markdown = take(result.json), take(result.markdown)
            ir = json.loads(document)
            blocks = sum(len(page['blocks']) for page in ir['pages'])
            assert ir['status'] == 'ok' and blocks > 0
            (args.output / f'run-{attempt + 1}.json').write_bytes(document)
            (args.output / f'run-{attempt + 1}.md').write_bytes(markdown)
            report['loads'].append({'attempt': attempt + 1, 'seconds': load_seconds,
                                    'status': ir['status'], 'blocks': blocks})
            print(f'PASS mmap load {attempt + 1}: {load_seconds:.3f}s, {blocks} real blocks', flush=True)
        finally:
            if job.value:
                check(lib.dococr_job_destroy(job))
            check(lib.dococr_destroy(engine))
(args.output / 'loading.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
