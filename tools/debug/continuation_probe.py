"""用户扫描页的异常续写复现：真实区域模型，退出 1=捕获，0=未捕获，2=未覆盖。

判据仅针对已人工查看的三个固定区域，不是生产截断或通用幻觉检测。
"""
from pathlib import Path
import argparse
import json
import re
import subprocess
import sys

REFERENCES = {
    'date': 'W: I did?',
    'numbers': 'answer to the question you have heard.',
    'punctuation': 'Questions 11 through 13 are based on the following passage.',
}


def anomaly(raw, kind):
    reference = REFERENCES[kind]
    index = raw.find(reference)
    if index < 0:
        return None
    tail = raw[index + len(reference):]
    if kind == 'date':
        match = re.search(r'(?:2023年1月1日\s*){6,}', tail)
        if match:
            return {'kind': 'unsupported_date_repetition',
                    'copies': match[0].count('2023年1月1日')}
    elif kind == 'punctuation':
        match = re.search(r'(?:\s*\.){32,}', tail)
        if match:
            return {'kind': 'punctuation_repetition', 'copies': match[0].count('.')}
    else:
        # 这一固定说明区域没有编号列表；连续递增值不是相同字符串重复。
        matches = list(re.finditer(r'(?m)^[ \t]*(\d+)\.[ \t]*$', tail))
        streak = []
        for match in matches:
            value = int(match[1])
            contiguous = not streak or (value == streak[-1][0] + 1 and
                not tail[streak[-1][2]:match.start()].strip())
            streak = streak + [(value, match.start(), match.end())] if contiguous else [(value, match.start(), match.end())]
            if len(streak) >= 12:
                return {'kind': 'unsupported_number_sequence',
                        'first': streak[0][0], 'last': value, 'minimumLines': len(streak)}
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--engine-config', type=Path)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--kind', choices=list(REFERENCES), required=True)
    parser.add_argument('--runs', type=int, default=3)
    parser.add_argument('--max-tokens', type=int, default=256)
    args = parser.parse_args()
    command = [sys.executable, str(Path(__file__).with_name('direct_region.py')),
               '--library', str(args.library), '--input', str(args.input),
               '--output', str(args.output), '--runs', str(args.runs),
               '--max-tokens', str(args.max_tokens)]
    if args.engine_config:
        command += ['--engine-config', str(args.engine_config)]
    result = subprocess.run(command)
    if result.returncode not in (0, 1) or not (args.output / 'report.json').is_file():
        return 2
    report = json.loads((args.output / 'report.json').read_text())
    verdicts = []
    for run in report['runs']:
        hit = anomaly(run['raw'], args.kind)
        verdict = 'ABNORMAL_CONTINUATION' if hit else 'NOT_REACHED' if REFERENCES[args.kind] not in run['raw'] else 'NOT_REPRODUCED'
        verdicts.append({'run': run['run'], 'verdict': verdict, 'anomaly': hit,
                         'stopReason': run.get('stopReason'), 'seconds': run['seconds']})
        print(verdict, args.kind, 'run', run['run'], json.dumps(hit, ensure_ascii=False), flush=True)
    evidence = {'scope': '只对这三个已查看的固定区域判定，不是生产通用规则',
                'kind': args.kind, 'expectedVisibleEnding': REFERENCES[args.kind],
                'directCommand': command, 'directExitCode': result.returncode,
                'rawAndActualTokens': 'report.json', 'verdicts': verdicts}
    (args.output / 'continuation-verdicts.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    return 1 if any(v['anomaly'] for v in verdicts) else 2 if any(v['verdict'] == 'NOT_REACHED' for v in verdicts) else 0


if __name__ == '__main__':
    raise SystemExit(main())
