"""获取固定版本 MNN；保留已有目录，下载成功后再移到目标位置。"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--destination', type=Path, help='覆盖默认的 native/MNN 安装位置')
parser.add_argument('--check-only', action='store_true', help='只核对已安装源码的版本与受跟踪文件')
args = parser.parse_args()
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
