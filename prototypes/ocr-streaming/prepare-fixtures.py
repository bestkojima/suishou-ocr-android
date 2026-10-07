"""Copy real docprase evidence; never regenerate or rewrite upstream results."""
import base64
import hashlib
import json
import mimetypes
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = Path('/home/dr/project/docprase')
FIXTURES = [('odb-13', '中文 · 表格 · 四张插图'), ('odb-09', '中文 · 三角公式 · 图片')]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def data_url(path):
    return f'data:{mimetypes.guess_type(path.name)[0]};base64,' + base64.b64encode(path.read_bytes()).decode()


datasets = []
for name, title in FIXTURES:
    evidence = SOURCE / 'docs/issue-27/evidence/pages/development' / name / 'command.json'
    command = json.loads(evidence.read_text())
    args = command['command']
    job = Path(args[args.index('--out') + 1])
    original = Path(args[args.index('--input') + 1])
    assert sha(original) == command['input_sha256'], 'Original image differs from recorded evidence'
    destination = HERE / 'fixtures' / name
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(job / 'document.json', destination / 'document.json')
    shutil.copy2(job / 'document.md', destination / 'document.md')
    shutil.copy2(original, destination / ('source' + original.suffix))
    document = json.loads((job / 'document.json').read_text())
    assets = {}
    copied = []
    for resource in document['resources']:
        relative = Path(resource['path'])
        source = job / relative
        # Fixture assets are constrained to this real job's assets directory.
        assert source.resolve().is_relative_to((job / 'assets').resolve())
        if not source.is_file():
            continue
        expected = command.get('files_sha256', {}).get('job/' + str(relative))
        if expected:
            assert sha(source) == expected, f'Asset differs from recorded evidence: {relative}'
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        assets[str(relative)] = dict(src=data_url(target), width=resource.get('width'), height=resource.get('height'))
        copied.append(dict(path=str(relative), sha256=sha(target)))
    page = document['pages'][0]
    by_id = {b['id']: b for b in page['blocks']}
    blocks = []
    for block_id in page['reading_order']:
        b = by_id[block_id]
        c = b['content']
        content = c.get('text', '')
        if b['type'] == 'image':
            content = f"![插图]({c['resource']})"
        elif c.get('format') == 'latex':
            content = '$$\n' + content.strip() + '\n$$'
        blocks.append(dict(id=block_id, type=b['type'], markdown=content,
                           sourceStatus=b['status'], resource=c.get('resource'),
                           bbox=b['bbox'], format=c.get('format')))
    provenance = dict(id=name, title=title, document_id=document['document_id'],
                      schema_version=document['schema_version'], status=document['status'],
                      source_json=str(job/'document.json'), source_json_sha256=sha(job/'document.json'),
                      original_path=str(original), original_sha256=sha(original),
                      assets=copied, simulation='Completed JSON replayed as synthetic character chunks; not recorded token events',
                      normalization='Use final page reading_order; wrap latex blocks in $$; convert resource blocks to Markdown images; other content preserved verbatim')
    (destination / 'provenance.json').write_text(json.dumps(provenance, ensure_ascii=False, indent=2))
    datasets.append(dict(id=name, title=title, provenance=provenance, blocks=blocks,
                         assets=assets, original=data_url(original), rasterSize=page['raster_size']))
(HERE / 'fixture-data.json').write_text(json.dumps(datasets, ensure_ascii=False))
print(json.dumps([dict(id=d['id'], blocks=len(d['blocks']), images=sum(b['type']=='image' for b in d['blocks']), assets=len(d['assets'])) for d in datasets]))
