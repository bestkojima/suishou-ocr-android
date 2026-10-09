"""在两张最小复现区域上逐项对照采样配置，收集重复率和已知文字匹配；不改默认值。"""
from pathlib import Path
import argparse
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library', type=Path, required=True)
parser.add_argument('--output', type=Path, default=ROOT / 'verification/ocr-degeneration/sampler-matrix')
parser.add_argument('--label', action='append', help='仅执行指定配置')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
configs = {'baseline': None, 'greedy-penalty-11': 'greedy-penalty-11',
           'penalty-neutral': 'penalty-neutral', 'penalty-105': 'penalty-105',
           'penalty-11': 'penalty-11', 'frequency-02': 'frequency-02'}
inputs = {'low': ('low-region.png', '商品统计'), 'large': ('large-region.png', '识')}
records = []
for label, filename in configs.items():
    if args.label and label not in args.label: continue
    for kind, (image_name, expected) in inputs.items():
        output = args.output / (label + '-' + kind)
        command = [sys.executable, str(ROOT / 'tools/debug/direct_region.py'),
                   '--library', str(args.library.resolve()), '--input', str(ROOT / 'verification/ocr-degeneration/captured' / image_name),
                   '--output', str(output), '--runs', '3', '--max-tokens', '64']
        if filename:
            command += ['--sampler-config', str(ROOT / 'verification/ocr-degeneration/sampler-configs' / (filename + '.json'))]
        log = args.output / (label + '-' + kind + '.log')
        with log.open('w') as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT)
        assert result.returncode in (0, 1), log.read_text()[-1000:]
        report = json.loads((output / 'report.json').read_text())
        trials = []
        for trial in report['runs']:
            normalized = re.sub(r'\s+', '', re.sub(r'(?m)^#+\s*', '', trial['raw']))
            trials.append({'repeat': trial['repeat'], 'raw': trial['raw'], 'stopReason': trial['stopReason'],
                           'exactExpectedText': normalized == expected, 'seconds': trial['seconds']})
        record = dict(label=label, kind=kind, expected=expected, inputSize=report['inputSize'],
                      overrides=report['samplerOverrides'], repetitions=sum(bool(t['repeat']) for t in trials),
                      exactExpectedText=sum(t['exactExpectedText'] for t in trials), trials=trials,
                      command=command, exitCode=result.returncode)
        records.append(record)
        (args.output / 'summary.json').write_text(json.dumps(records, ensure_ascii=False, indent=2) + '\n')
        print(label, kind, 'repeat', str(record['repetitions']) + '/3',
              'knownTextMatch', str(record['exactExpectedText']) + '/3',
              repr(trials[0]['raw'][:70]), flush=True)
