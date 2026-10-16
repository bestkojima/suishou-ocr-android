"""默认校验内附 MNN；仅 --source 模式下载固定源码供维护者重编译。"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from mnn_prebuilt import verify_bundle

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', action='store_true', help='显式下载/检查 MNN 源码，普通 APK 构建不需要')
parser.add_argument('--destination', type=Path, help='覆盖默认的 native/MNN 安装位置')
parser.add_argument('--check-only', action='store_true', help='只核对已安装源码的版本与受跟踪文件')
args = parser.parse_args()
if not args.source and args.destination is None:
    manifest = verify_bundle()
    print(f'已就绪：仓库内预编译 MNN @ {manifest["source"]["commit"]}；无需下载源码')
    raise SystemExit(0)
config = json.loads((ROOT / 'native/dependencies.json').read_text(encoding='utf-8'))['mnn']
destination = (args.destination or ROOT / config['directory']).resolve()

def git(*arguments, cwd=None, capture=False):
    return subprocess.run(['git', *arguments], cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout

def verify(directory):
    if not (directory / '.git').exists():
        raise RuntimeError(f'{directory} 已存在但不是独立 Git 仓库；请改用空目录')
    commit = git('rev-parse', 'HEAD', cwd=directory, capture=True).strip()
    if commit != config['commit']:
        raise RuntimeError(f'MNN 版本不同：{commit}；需要 {config["commit"]}。已有目录未修改。')
    if git('status', '--porcelain', '--untracked-files=no', cwd=directory, capture=True).strip():
        raise RuntimeError('MNN 有受跟踪文件改动；已有目录未修改，请使用干净的固定版本。')

if destination.exists():
    verify(destination)
    print(f'已就绪：{destination} @ {config["commit"]}')
elif args.check_only:
    raise SystemExit(f'MNN 目录不存在：{destination}')
else:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='mnn-download-', dir=destination.parent))
    try:
        git('init', str(temporary))
        git('remote', 'add', 'origin', config['repository'], cwd=temporary)
        git('fetch', '--depth', '1', 'origin', config['commit'], cwd=temporary)
        git('checkout', '--detach', 'FETCH_HEAD', cwd=temporary)
        verify(temporary)
        temporary.rename(destination)
        print(f'已安装：{destination} @ {config["commit"]}')
    finally:
        if temporary.exists(): shutil.rmtree(temporary)
