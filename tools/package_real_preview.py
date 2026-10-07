"""将本轮真实输出与输入打成可用现有 JSON/ZIP 导入器打开的预览文件。"""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import json,hashlib
root=Path(__file__).resolve().parents[1]
out=root/'verification/real-ocr'
for name in ['output','partial','blank','repeated']:
    folder=out/name
    if not (folder/'document.json').is_file():continue
    with ZipFile(out/(name+'.zip'),'w',ZIP_DEFLATED) as bundle:
        for f in folder.rglob('*'):
            if f.is_file():bundle.write(f,f.relative_to(folder))
        source=folder/'source.jpg' if name=='partial' else out/('blank.png' if name=='blank' else 'partial-source.png' if name=='repeated' else 'source.png')
        if name!='partial':bundle.write(source,'source.png')
input=out/'source.png'
files={str(f.relative_to(out)):{'size':f.stat().st_size,'sha256':hashlib.file_digest(f.open('rb'),'sha256').hexdigest()} for f in out.rglob('*') if f.is_file() and f.suffix not in ['.log'] and f.name!='artifacts.json'}
(out/'artifacts.json').write_text(json.dumps({'inputSha256':hashlib.file_digest(input.open('rb'),'sha256').hexdigest(),'files':files},indent=2)+'\n')
