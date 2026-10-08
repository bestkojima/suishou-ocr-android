"""比较同源 Debug/mmap、Release/mmap、Release/非 mmap 的计时与真实内容；非真机准确率。"""
import argparse
import json
import hashlib
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline', type=Path, required=True)
parser.add_argument('--release-mmap', type=Path, required=True)
parser.add_argument('--release-no-mmap', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--review', type=Path, help='人工核对已知差异的固定输入／内容 SHA 对；默认任何差异都失败')
args = parser.parse_args()
cases = [('Debug+mmap', args.baseline, True), ('Release+mmap', args.release_mmap, True),
         ('Release+no-mmap', args.release_no_mmap, False)]


def content(document):
    # 计时、配置 hash、运行 profile 和模型数值置信度不作为正文质量。
    return {'status': document['status'], 'pages': [
        {'id': page['page_id'], 'readingOrder': page['reading_order'], 'blocks': [
            {'id': block['id'], 'type': block['type'], 'content': block['content']}
            for block in page['blocks']]} for page in document['pages']]}


reports = []
reference = None
input_hash = None
threads = None
differences = []
reference_hash = None
for name, folder, expected_mmap in cases:
    data = json.loads((folder / 'benchmark.json').read_text())
    if input_hash is None:
        input_hash, threads = data['inputSha256'], data['threads']
    assert data['inputSha256'] == input_hash and data['threads'] == threads
    assert data['modelsVerifiedBeforeLoading'] is True
    assert data['expectedMmap'] is expected_mmap
    runs = []
    for index, run in enumerate(data['loads'], 1):
        assert run['useMmap'] is expected_mmap
        document = json.loads((folder / f'run-{index}.json').read_text())
        projected = content(document)
        projected_hash = hashlib.sha256(json.dumps(projected, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        if reference is None:
            reference = projected
            reference_hash = projected_hash
        if projected != reference:
            differences.append({'configuration': name, 'run': index, 'baselineContentSha256': reference_hash,
                                'contentSha256': projected_hash, 'content': projected})
        stream = run['stream']
        runs.append({'run': index, 'engineReused': run['engineReused'], 'loadingSeconds': run['seconds'],
                     'firstContentSeconds': stream['firstLiveSeconds'], 'totalSeconds': stream['totalSeconds'],
                     'phasesMs': stream['phaseTotalsMs'], 'memory': run['memory'], 'blocks': run['blocks'],
                     'contentMatchesBaseline': projected == reference, 'rawMatchesSavedAttempts': stream['rawMatchesSavedAttempts']})
    reports.append({'configuration': name, 'librarySha256': data['librarySha256'],
                    'memoryAfterLoading': data['memoryAfterLoading'], 'runs': runs,
                    'temporaryDirectory': data['temporaryDirectory']})

review = json.loads(args.review.read_text()) if args.review else None
review_accepted = False
if review:
    assert review['inputSha256'] == input_hash, '复核记录不属于本次输入'
    accepted = {(p['baselineContentSha256'], p['contentSha256']) for p in review['acceptedPairs'] if p['reason']}
    review_accepted = bool(differences) and all((d['baselineContentSha256'], d['contentSha256']) in accepted for d in differences)
result = {'environment': 'Linux x86_64 VirtualBox，同源 C ABI＋固定 MNN＋真实模型；非 Android 性能或标注准确率',
          'inputSha256': input_hash, 'threads': threads, 'contentEquivalent': not differences,
          'reviewedDifferencesAccepted': review_accepted, 'validationPassed': not differences or review_accepted, 'review': review,
          'comparison': '页面状态、阅读顺序、内容块类型、正文／公式／表格结构与资源引用完全比较；忽略运行元数据',
          'memoryNote': 'RSS 为采样时进程驻留内存，processPeakRssBytes 为整个测试进程生命周期峰值',
          'configurations': reports, 'differences': differences}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
assert result['validationPassed'], '真实内容与改前不同，请查阅报告，解释差异后才能发布'
print('PASS 三配置实际 mmap 标记、同输入／线程／模型、流式保存一致；' + ('已知内容差异按固定 SHA 对复核通过' if review_accepted else '每次真实内容完全一致'))
