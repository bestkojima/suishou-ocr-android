"""比较两模型完整流水线：单次异常页面，或排除预热后的正常页面中位数。"""
import argparse
import gzip
import json
from pathlib import Path
from statistics import median


def read_run(folder, run_number=None):
    folder = Path(folder)
    benchmark = json.loads((folder / 'benchmark.json').read_text())
    if run_number is None:
        assert len(benchmark['loads']) == 1, '多次实测请使用 --warmup-runs 指定预热次数'
        run_number = 1
    assert 1 <= run_number <= len(benchmark['loads']), '运行序号超出记录范围'
    benchmark = {**benchmark, 'loads': [benchmark['loads'][run_number - 1]]}
    manifest = json.loads((folder / f'manifest-{run_number}.json').read_text())
    document = json.loads((folder / f'run-{run_number}.json').read_text())
    return benchmark, manifest, document


def compare(glm_folder, ovis_folder, run_number=None):
    glm = read_run(glm_folder, run_number)
    ovis = read_run(ovis_folder, run_number)
    a, b = glm[0], ovis[0]
    for key in ('inputSha256', 'librarySha256', 'threads'):
        assert a[key] == b[key], f'两次运行 {key} 不同'
    assert a.get('loadedNativeLibrariesSha256') == b.get('loadedNativeLibrariesSha256'), '实际加载的 MNN 等动态库不同'
    for key in ('schema_version', 'mode', 'backend', 'skills', 'flow', 'processing', 'platform', 'execution'):
        assert a['config'][key] == b['config'][key], f'流水线设置 {key} 不同'
    assert a['config']['models']['layout'] == b['config']['models']['layout'], '版面模型必须相同'
    # 比较实际版面候选与处理框；只忽略置信度等无关计时元数据。
    def layout(document):
        return [(region['candidate_id'], region['model_label'], region['original_bbox'], region['selected'])
                for region in document['layout_diagnostics']['candidates']]
    assert layout(glm[2]) == layout(ovis[2]), '实际版面候选／框不同，不能当作同一组区域比较'
    rows = {}
    regions = {}
    for name, (benchmark, manifest, document) in (('glm', glm), ('ovis', ovis)):
        run = benchmark['loads'][0]
        stream = run['stream']
        runtime = manifest['runtime_configuration']
        assert runtime['use_mmap'] is False and runtime['kvcache_mmap'] is False
        assert stream['rawMatchesSavedAttempts'] is True
        regions[name] = {region['request_id']: region for region in manifest['regions']
                         if region.get('recognition', {}).get('attempts')}
        rows[name] = {
            'modelName': runtime['model_name'], 'status': run['status'],
            'engineReused': run['engineReused'],
            'loadingSeconds': run['seconds'], 'pipelineSeconds': stream['totalSeconds'],
            'firstContentFromPipelineStartSeconds': stream['firstLiveSeconds'],
            'saveResultsSeconds': stream['saveResultsSeconds'],
            'loadingPlusPipelinePlusSavingSeconds': run['seconds'] + stream['totalSeconds'] + stream['saveResultsSeconds'],
            'pipelineTimingsMs': stream['pipelineTimingsMs'],
            'recognitionRegions': stream['recognitionRegions'], 'attempts': stream['attempts'],
            'retriedRegions': stream['retriedRegions'],
            'notNormalRegions': [key for key, region in regions[name].items() if region['stop_reason'] != 'normal'],
            'lastAttemptPhasesMs': stream['phaseTotalsMs'],
            'lastAttemptPhasesScope': stream['phaseTotalsScope'],
            'outputTokensLastAttempts': stream['outputTokensLastAttempts'],
            'memoryAfterLoading': benchmark['memoryAfterLoading'], 'memoryAfterRecognition': run['memory'],
            'useMmap': runtime['use_mmap'], 'kvCacheMmap': runtime['kvcache_mmap'],
            'sampler': runtime['sampler'], 'sessionStrategy': runtime['session_strategy'],
            'savedAssets': stream.get('savedAssets'),
        }
    assert regions['glm'].keys() == regions['ovis'].keys(), '生成区域不相同'
    common = [key for key in regions['glm']
              if all(regions[name][key]['status'] == 'ok' and regions[name][key]['stop_reason'] == 'normal'
                     for name in ('glm', 'ovis'))]
    common_totals = {name: sum(regions[name][key]['elapsed_ms'] for key in common) / 1000
                     for name in ('glm', 'ovis')}
    for name, run in (('glm', a), ('ovis', b)):
        rows[name]['commonNormalRegionsPhasesMs'] = {
            key: sum(region[key] for region in run['loads'][0]['stream']['regions'] if region['requestId'] in common)
            for key in ('visionMs', 'prefillMs', 'decodeMs')}
    return {
        'environment': 'Linux x86_64 VirtualBox，同源 Android 原生完整流水线；非安卓设备或 UI 计时',
        'inputSha256': a['inputSha256'], 'inputDimensions': glm[2]['layout_diagnostics']['input_transform']['source_size'],
        'librarySha256': a['librarySha256'], 'threads': a['threads'], 'runsPerModel': 1,
        'independentWarmup': all(row['engineReused'] for row in rows.values()),
        'clearedOsDiskCache': False, 'sameLayoutCandidatesAndBoxes': True,
        'layoutCanvas': glm[2]['layout_diagnostics']['input_transform']['canvas_size'],
        'pipelineScope': '完整 PNG 的内存输入→解码→800画布版面检测→原图区域裁剪→视觉适配→识别与重试→DocumentIR/Markdown组装→作业恢复；文件保存另计',
        'models': rows,
        'pipelineComparison': {
            'ovisOverGlm': rows['ovis']['pipelineSeconds'] / rows['glm']['pipelineSeconds'],
            'glmSavedSeconds': rows['ovis']['pipelineSeconds'] - rows['glm']['pipelineSeconds'],
            'glmSavedPercentOfOvis': 100 * (1 - rows['glm']['pipelineSeconds'] / rows['ovis']['pipelineSeconds']),
        },
        'bothNormalRegionsSubset': {
            'requestIds': common, 'count': len(common), 'seconds': common_totals,
            'glmOverOvis': common_totals['glm'] / common_totals['ovis'] if common_totals['ovis'] else None,
            'scope': '由本次完整运行中双方均正常停止的相同区域派生；不是额外单区域实验，不含整页公共步骤',
        },
        'limitations': [
            '此项仅比较一个作业；测量顺序与预热情况须以外层报告为准，未清空系统缓存' if run_number else '每模型一次，无独立预热和三次中位数；顺序为 GLM 后 Ovis，未清空系统缓存',
            '模型目录的提示词、视觉 token/模板不同；测量的是当前各自适配的实际流水线',
            '正常停止不等于识别内容或准确率通过验收',
            '阶段 vision/prefill/decode 合计仅含每区最后一次尝试，不能排除重试前阶段后再称全程合计',
            '峰值为包含输入、模型、真实流采集和导出的整个进程 RSS，不能当作 Android 模型独立占用',
        ],
    }


def check_normal_page(comparison):
    for row in comparison['models'].values():
        assert row['status'] == 'ok' and not row['notNormalRegions'], '正常页有异常停止，不能归入正常页面比较'
        assert not row['retriedRegions'], '正常页面比较要求每个生成区域无重试'
    for row in comparison['models'].values():
        assert row['recognitionRegions'] == comparison['bothNormalRegionsSubset']['count']


def compare_warmed(glm_folder, ovis_folder, warmup_runs):
    """正常页面：排除预热作业，对每次完整运行先检查，再汇总中位数。"""
    folders = {'glm': Path(glm_folder), 'ovis': Path(ovis_folder)}
    benchmarks = {name: json.loads((folder / 'benchmark.json').read_text()) for name, folder in folders.items()}
    counts = {len(benchmark['loads']) for benchmark in benchmarks.values()}
    assert len(counts) == 1, '两模型的预热与测量次数必须相同'
    count = counts.pop()
    assert 1 <= warmup_runs < count, '须至少一次预热和一次计时'
    comparisons = [compare(glm_folder, ovis_folder, number) for number in range(1, count + 1)]
    for comparison in comparisons:
        check_normal_page(comparison)

    rows = {}
    for name, folder in folders.items():
        samples = []
        for number in range(warmup_runs + 1, count + 1):
            row = comparisons[number - 1]['models'][name]
            compressed = folder / f'timeline-{number}.json.gz'
            timeline = json.loads(gzip.decompress(compressed.read_bytes()) if compressed.exists()
                                  else (folder / f'timeline-{number}.json').read_bytes())
            # 完成很快的区域也能显示正文；不能因首次轮询时它已完成而漏掉首内容。
            first_content = next(item['seconds'] for item in timeline
                                 if item['beforeReturn'] and any(region['raw'] for region in item['stream']['regions']))
            samples.append({
                'run': number, 'pipelineSeconds': row['pipelineSeconds'],
                'firstContentObservedSeconds': first_content,
                'firstPartialRegionObservedSeconds': row['firstContentFromPipelineStartSeconds'],
                'pipelineTimingsMs': row['pipelineTimingsMs'],
                'recognitionPhasesMs': row['lastAttemptPhasesMs'],
                'outputTokens': row['outputTokensLastAttempts'],
                'saveResultsSeconds': row['saveResultsSeconds'],
                'memory': row['memoryAfterRecognition'],
            })
        rows[name] = {
            'modelName': comparisons[0]['models'][name]['modelName'],
            'loadingSeconds': benchmarks[name]['loads'][0]['seconds'],
            'warmupPipelineSeconds': comparisons[0]['models'][name]['pipelineSeconds'],
            'samples': samples,
            'medians': {key: median(sample[key] for sample in samples)
                        for key in ('pipelineSeconds', 'firstContentObservedSeconds', 'firstPartialRegionObservedSeconds', 'saveResultsSeconds', 'outputTokens')},
            'pipelinePhaseMediansMs': {key: median(sample['pipelineTimingsMs'][key] for sample in samples)
                                      for key in samples[0]['pipelineTimingsMs']},
            'recognitionPhaseMediansMs': {key: median(sample['recognitionPhasesMs'][key] for sample in samples)
                                         for key in samples[0]['recognitionPhasesMs']},
            'recognitionRegions': comparisons[0]['models'][name]['recognitionRegions'],
            'savedAssetsPerRun': comparisons[0]['models'][name]['savedAssets'],
            'normalRunsIncludingWarmup': count, 'retriedRegions': [],
            'memoryAfterLoading': benchmarks[name]['memoryAfterLoading'],
            'memoryLastRun': samples[-1]['memory'],
        }
    ovis_time = rows['ovis']['medians']['pipelineSeconds']
    glm_time = rows['glm']['medians']['pipelineSeconds']
    return {
        'environment': comparisons[0]['environment'],
        'inputSha256': comparisons[0]['inputSha256'], 'inputDimensions': comparisons[0]['inputDimensions'],
        'librarySha256': comparisons[0]['librarySha256'],
        'loadedNativeLibrariesSha256': benchmarks['ovis']['loadedNativeLibrariesSha256'],
        'threads': comparisons[0]['threads'], 'warmupRunsPerModel': warmup_runs,
        'measuredRunsPerModel': count - warmup_runs,
        'modelOrderEvidence': '执行顺序须见本次 environment.json；比较工具不由目录名推断先后',
        'sameLayoutCandidatesAndBoxes': True, 'layoutCanvas': comparisons[0]['layoutCanvas'],
        'pipelineScope': comparisons[0]['pipelineScope'], 'models': rows,
        'medianPipelineComparison': {'glmOverOvis': glm_time / ovis_time,
                                    'ovisSavedSeconds': glm_time - ovis_time,
                                    'ovisSavedPercentOfGlm': 100 * (1 - ovis_time / glm_time)},
        'runComparisonsIncludingWarmup': comparisons,
        'limitations': [
            'Linux VirtualBox 同源实测，不代表 Titan_1 或其他 Android 真机；未清空 OS 缓存',
            '每模型预热后复用引擎，实际执行顺序须见本次环境记录',
            '首内容为真实生成快照第一次可见正文，包含快速已完成区域，轮询间隔约 150 ms；区域未完成时首个片段另列',
            '正常停止且无重试不代表内容完全正确；样页并非问题英文扫描页，不可外推所有页面',
            '阶段分别取中位数，不保证阶段中位数之和等于整体中位数；模型自身提示词、视觉参数、输出 token 数不同',
            '峰值 RSS 包括模型、输入、真实流采集和导出；不是 Android 模型独立内存',
        ],
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--glm', type=Path, required=True, help='GLM benchmark 输出目录')
    parser.add_argument('--ovis', type=Path, required=True, help='Ovis benchmark 输出目录')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--warmup-runs', type=int, help='正常页面多次运行：排除指定预热作业后汇总中位数，并要求全部无异常、无重试')
    args = parser.parse_args()
    result = compare_warmed(args.glm, args.ovis, args.warmup_runs) if args.warmup_runs is not None else compare(args.glm, args.ovis)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    if args.warmup_runs is not None:
        print(json.dumps({'models': {name: row['medians'] for name, row in result['models'].items()},
                          'medianPipelineComparison': result['medianPipelineComparison']}, ensure_ascii=False, indent=2))
    else:
        print(json.dumps({'models': {name: {key: row[key] for key in ('loadingSeconds', 'pipelineSeconds', 'firstContentFromPipelineStartSeconds', 'status')}
                               for name, row in result['models'].items()},
                      'pipelineComparison': result['pipelineComparison'],
                      'bothNormalRegionsSubset': result['bothNormalRegionsSubset']}, ensure_ascii=False, indent=2))
