"""从已下载模型目录和配置生成引擎绑定；不在加载前重复校验大权重。"""
from pathlib import Path
import argparse
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]


def prepare(profile_id, model_root, layout_root, output, threads=4):
    assets = ROOT / 'app/src/main/assets/ocr'
    profile = json.loads((assets / 'model-profiles.json').read_text())[profile_id]
    model_root = Path(model_root).resolve(); layout_root = Path(layout_root).resolve()
    source = (model_root / 'config.json').read_bytes()
    source_hash = hashlib.sha256(source).hexdigest()
    runtime_path = model_root / 'ocr_runtime.json'
    runtime = json.loads(runtime_path.read_text()) if runtime_path.exists() else None
    if runtime is None or runtime.get('source_config_sha256') != source_hash:
        runtime = json.loads(source)
        if 'ocr' not in runtime:
            runtime.update(profile['config'])
        runtime['source_config_sha256'] = source_hash
        runtime_path.write_text(json.dumps(runtime, ensure_ascii=False, indent=2) + '\n')
    config = json.loads((assets / 'config.json').read_text())
    config['platform']['threads'] = threads
    recognition = config['models']['recognition']; recognition['root'] = str(model_root)
    config['models']['layout']['root'] = str(layout_root)
    artifacts = []
    for entry in json.loads((assets / profile['catalog']).read_text()):
        directory = model_root if entry['repo'] == profile['repo'] else layout_root
        assert (directory / entry['path']).stat().st_size == entry['size'], entry['path']
        if entry['repo'] == profile['repo']:
            artifacts.append({'path': entry['path'], 'sha256': entry['sha256']})
    artifacts.append({'path': 'ocr_runtime.json', 'sha256': hashlib.sha256(runtime_path.read_bytes()).hexdigest()})
    recognition['artifacts'] = artifacts
    output = Path(output); output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n')
    return config


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=['ovis', 'glm'], required=True)
    parser.add_argument('--model-root', type=Path, required=True)
    parser.add_argument('--layout-root', type=Path, default=ROOT.parent / 'docprase/models/doclayout')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--threads', type=int, choices=[1, 2, 4], default=4)
    args = parser.parse_args()
    prepare(args.profile, args.model_root, args.layout_root, args.output, args.threads)
    print('READY', args.output.resolve(), 'model configuration', (args.model_root / 'ocr_runtime.json').resolve())
