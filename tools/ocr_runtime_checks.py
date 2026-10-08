"""同源真实模型验证共用的运行配置、临时目录及 Linux 内存采样。"""
import ctypes as c
import json
from pathlib import Path
import resource


def check_runtime(lib, job, bytes_type, take, expected_mmap):
    lib.dococr_job_manifest.argtypes = [c.c_uint64, c.POINTER(bytes_type)]
    manifest = bytes_type()
    assert lib.dococr_job_manifest(job, c.byref(manifest)) == 0
    data = take(manifest)
    result = json.loads(data)
    runtime = result['runtime_configuration']
    assert runtime['use_mmap'] is expected_mmap, runtime
    assert runtime['kvcache_mmap'] is False, runtime
    return result, data


def memory_usage():
    # ru_maxrss 为整个测试进程生命周期峰值，不能当作当前单个区域的独立峰值。
    rss = next(int(line.split()[1]) * 1024 for line in Path('/proc/self/status').read_text().splitlines() if line.startswith('VmRSS:'))
    return {'rssBytes': rss, 'processPeakRssBytes': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024}


def check_cache_directory(directory, expected_mmap, existing_mmap_dirs=()):
    files = [{'path': str(path.relative_to(directory)), 'bytes': path.stat().st_size}
             for path in sorted(Path(directory).rglob('*')) if path.is_file()]
    mmap_dirs = [str(path.relative_to(directory)) for path in Path(directory).glob('ovis-mmap-*')]
    if not expected_mmap:
        created = set(mmap_dirs) - set(existing_mmap_dirs)
        assert not created, ('关闭 mmap 后仍创建权重缓存目录', sorted(created))
    return {'files': files, 'mmapDirectories': mmap_dirs}
