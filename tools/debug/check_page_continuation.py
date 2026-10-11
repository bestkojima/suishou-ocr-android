"""检查固定英文扫描页的真实生成结果：1=捕获异常，0=未复现，2=未覆盖。

这里只读取已完成的真实推理输出；既不回放推理，也不改生产截断策略。
判据仅适用于人工检查过的这张原图，不能作为通用 OCR 正确性检测。
"""
import argparse
import gzip
import json
from pathlib import Path

from continuation_probe import REFERENCES, anomaly
from ocr_degeneracy import repetition

INPUT_SHA256 = '0f69c46def894afc60ba0d36901578d9aa65705ad3a9510274e8915fb710f88e'
REGIONS = {'reqr0009': 'date', 'reqr0013': 'numbers', 'reqr0014': 'punctuation'}


def inspect(folder):
    benchmark = json.loads((folder / 'benchmark.json').read_text())
    assert benchmark['inputSha256'] == INPUT_SHA256, '此判据只适用于固定用户原图'
    assert len(benchmark['loads']) == 1, '此入口检查单次完整推理'
    manifest = json.loads((folder / 'manifest-1.json').read_text())
    compressed = folder / 'timeline-1.json.gz'
    timeline = json.loads(gzip.decompress(compressed.read_bytes()) if compressed.exists()
                          else (folder / 'timeline-1.json').read_bytes())
    first_seen = {}
    for item in timeline:
        for region in item['stream']['regions']:
            if not item['beforeReturn']:
                continue
            kind = REGIONS.get(region['requestId'])
            hit = anomaly(region['raw'], kind) if kind else None
            if hit:
                first_seen.setdefault((region['requestId'], region['attempt']), item['seconds'])

    cases, repetitions, not_normal = [], [], []
    by_request = {region['request_id']: region for region in manifest['regions']}
    for request_id, kind in REGIONS.items():
        region = by_request[request_id]
        attempts = []
        for attempt in region['recognition']['attempts']:
            raw = attempt['output']['raw_output']
            covered = REFERENCES[kind] in raw
            hit = anomaly(raw, kind)
            attempts.append({
                'attempt': attempt['index'], 'coveredVisibleEnding': covered,
                'anomaly': hit, 'stopReason': attempt['output']['stop_reason'],
                'maxNewTokens': attempt['config']['max_new_tokens'],
                'elapsedMs': attempt['elapsed_ms'],
                'firstAnomalyBeforeReturnSeconds': first_seen.get((request_id, attempt['index'])),
                'rawOutput': raw,
            })
        cases.append({'requestId': request_id, 'kind': kind, 'attempts': attempts})
    for request_id, region in by_request.items():
        attempts = region.get('recognition', {}).get('attempts', [])
        if attempts and (region['status'] != 'ok' or region['stop_reason'] != 'normal'):
            not_normal.append(request_id)
        for attempt in attempts:
            hit = repetition(attempt['output']['raw_output'])
            if hit:
                repetitions.append({'requestId': request_id, 'attempt': attempt['index'], **hit})
    captured = bool(repetitions) or any(a['anomaly'] for case in cases for a in case['attempts'])
    covered = all(a['coveredVisibleEnding'] for case in cases for a in case['attempts'])
    verdict = 'ABNORMAL_CONTINUATION' if captured else 'NOT_REPRODUCED' if covered else 'NOT_COVERED'
    run = benchmark['loads'][0]
    return {
        'scope': '固定用户扫描页；三处可见正文末尾判据及全页短片段重复扫描，不等于准确率或通用幻觉验收',
        'inputSha256': INPUT_SHA256, 'verdict': verdict, 'coveredAllThreeVisibleEndings': covered,
        'hasContinuationAnomaly': captured, 'knownCases': cases, 'repeatedStrings': repetitions,
        'notNormalRegions': not_normal, 'status': run['status'],
        'pipelineSeconds': run['stream']['totalSeconds'],
        'recognitionRegions': run['stream']['recognitionRegions'],
        'attempts': run['stream']['attempts'], 'retriedRegions': run['stream']['retriedRegions'],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--folder', type=Path, required=True, help='新鲜完整推理的 benchmark 输出目录')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        report = inspect(args.folder)
    except (OSError, ValueError, KeyError, AssertionError) as error:
        print('NOT_COVERED', str(error))
        return 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(report['verdict'], report['status'], '异常区域', report['notNormalRegions'])
    return 1 if report['hasContinuationAnomaly'] else 0 if report['coveredAllThreeVisibleEndings'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
