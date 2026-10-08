"""Linux 生产 C ABI 证据：单作业、实际取消、恢复后完整输出及重复/空白输入；不代表设备推理。"""
import ctypes as c
import argparse
import json
import shutil
import time
import threading
import resource
import os
import tempfile
from pathlib import Path
from PIL import Image
from ocr_runtime_checks import check_runtime, check_cache_directory

ROOT=Path(__file__).resolve().parents[1]
ENGINE=ROOT.parent/'docprase'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,default=ROOT/'verification/real-ocr',help='独立的桌面生命周期证据目录')
parser.add_argument('--input',type=Path,default=ROOT/'verification/real-ocr/source.png',help='本次实际识别的输入图片')
parser.add_argument('--library',type=Path,default=ENGINE/'build/linux-current/libdococr_c.so',help='本次构建的同源生产 C ABI 库')
parser.add_argument('--threads',type=int,choices=[1,2,4],default=1)
parser.add_argument('--expect-mmap',choices=['false','true'],default='false',help='true 仅用于历史对照库')
args=parser.parse_args()
library=args.library.resolve()
expected_mmap=args.expect_mmap=='true'
threads=args.threads
runtime_dir=tempfile.TemporaryDirectory(prefix='android-ocr-lifecycle-')
os.environ['TMPDIR']=runtime_dir.name
OUT=args.output.resolve()
OUT.mkdir(parents=True,exist_ok=True)
source=args.input.resolve()
if OUT/'source.png'!=source:shutil.copyfile(source,OUT/'source.png')
class View(c.Structure):
    _fields_=[('data',c.c_char_p),('size',c.c_size_t)]
class Bytes(c.Structure):
    _fields_=[('data',c.POINTER(c.c_uint8)),('size',c.c_size_t),('allocation_id',c.c_uint64)]
class Input(c.Structure):
    _fields_=[('struct_size',c.c_uint32),('data',c.POINTER(c.c_uint8)),('size',c.c_size_t),('format',c.c_uint32),('width',c.c_uint32),('height',c.c_uint32),('row_stride',c.c_size_t),('first_page',c.c_uint32),('last_page',c.c_uint32),('dpi',c.c_uint32),('max_page_pixels',c.c_uint64),('timeout_ms',c.c_uint32)]
class Result(c.Structure):
    _fields_=[('struct_size',c.c_uint32),('json',Bytes),('markdown',Bytes)]
lib=c.CDLL(str(library))
for name,args in {
    'create':[View,c.POINTER(c.c_uint64)],'job_create':[c.c_uint64,c.POINTER(c.c_uint64)],
    'job_run':[c.c_uint64,c.POINTER(Input)],'job_result':[c.c_uint64,c.POINTER(Result)],
    'job_status':[c.c_uint64,c.POINTER(Bytes)],'job_next_event':[c.c_uint64,c.POINTER(Bytes)],
    'job_cancel':[c.c_uint64],'job_destroy':[c.c_uint64],'destroy':[c.c_uint64],
    'bytes_free':[c.POINTER(Bytes)],'job_manifest':[c.c_uint64,c.POINTER(Bytes)],
    'job_asset_count':[c.c_uint64,c.POINTER(c.c_size_t)],'job_asset':[c.c_uint64,c.c_size_t,c.POINTER(Bytes),c.POINTER(Bytes)],
}.items():getattr(lib,'dococr_'+name).argtypes=args

def take(bytes):
    value=c.string_at(bytes.data,bytes.size)
    assert lib.dococr_bytes_free(c.byref(bytes))==0
    return value

def snapshot(job):
    value=Bytes();assert lib.dococr_job_status(job,c.byref(value))==0
    return json.loads(take(value))

def request(path):
    data=path.read_bytes();buffer=(c.c_uint8*len(data)).from_buffer_copy(data)
    return buffer,Input(c.sizeof(Input),buffer,len(data),1,0,0,0,0,0,0,0,0)

def new_job(engine):
    job=c.c_uint64();assert lib.dococr_job_create(engine,c.byref(job))==0
    return job

def export(job,path):
    path.mkdir(parents=True,exist_ok=True);r=Result();r.struct_size=c.sizeof(Result)
    assert lib.dococr_job_result(job,c.byref(r))==0
    data=take(r.json);(path/'document.json').write_bytes(data);(path/'document.md').write_bytes(take(r.markdown))
    manifest,manifest_data=check_runtime(lib,job,Bytes,take,expected_mmap)
    assert manifest['runtime_configuration']['ovis_threads']==threads
    (path/'run-manifest.json').write_bytes(manifest_data)
    count=c.c_size_t();assert lib.dococr_job_asset_count(job,c.byref(count))==0
    for i in range(count.value):
        name,bytes=Bytes(),Bytes();assert lib.dococr_job_asset(job,i,c.byref(name),c.byref(bytes))==0
        target=path/take(name).decode();target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(take(bytes))
    return json.loads(data)

config=json.loads((ROOT/'app/src/main/assets/ocr/config.json').read_text())
for key,dir in [('layout','doclayout'),('recognition','ovis')]:config['models'][key]['root']=str(ENGINE/'models'/dir)
config['execution']['max_new_tokens']=512
config['platform']['threads']=threads
(OUT/'lifecycle-config.json').write_text(json.dumps(config,indent=2))
encoded=json.dumps(config).encode();engine=c.c_uint64();start=time.monotonic()
assert lib.dococr_create(View(encoded,len(encoded)),c.byref(engine))==0
load=time.monotonic()-start
job,other=new_job(engine),new_job(engine);buffer,req=request(OUT/'source.png')
result=[];thread=threading.Thread(target=lambda:result.append(lib.dococr_job_run(job,c.byref(req))))
thread.start();events=[];deadline=time.monotonic()+120
while time.monotonic()<deadline:
    e=Bytes();code=lib.dococr_job_next_event(job,c.byref(e))
    if code==0:
        event=json.loads(take(e));events.append(event)
        if event['kind']=='region_started':break
    else:assert code==8
    time.sleep(.02)
else:raise AssertionError('未开始真实区域识别')
assert lib.dococr_job_run(other,c.byref(req))==3
assert lib.dococr_job_destroy(job)==3
assert lib.dococr_job_cancel(job)==0
while thread.is_alive() and time.monotonic()<deadline:
    snapshot(job);time.sleep(.05)
thread.join(timeout=1);assert not thread.is_alive();assert result==[7]
cancelled=snapshot(job);assert cancelled['terminal'] and cancelled['state']=='cancelled'
r=Result();r.struct_size=c.sizeof(Result);assert lib.dococr_job_result(job,c.byref(r))==8
assert lib.dococr_job_destroy(job)==0;assert lib.dococr_job_destroy(other)==0
buffer,req=request(OUT/'source.png')
job=new_job(engine);assert lib.dococr_job_run(job,c.byref(req))==0
complete_ir=export(job,OUT/'output');complete=snapshot(job)
assert complete['terminal'] and complete_ir['pages'][0]['blocks']
assert lib.dococr_job_destroy(job)==0
Image.open(ROOT/'prototypes/ocr-streaming/fixtures/odb-09/source.jpg').convert('RGB').save(OUT/'partial-source.png')
buffer,req=request(OUT/'partial-source.png')
job=new_job(engine);assert lib.dococr_job_run(job,c.byref(req))==0
ir=export(job,OUT/'repeated');assert ir['status'] in ('ok','partial')
partial=snapshot(job);assert partial['terminal'];assert lib.dococr_job_destroy(job)==0
Image.new('RGB',(640,800),'white').save(OUT/'blank.png');buffer,req=request(OUT/'blank.png')
job=new_job(engine);assert lib.dococr_job_run(job,c.byref(req))==0
blank_ir=export(job,OUT/'blank');blank=snapshot(job)
assert not blank_ir['pages'][0]['blocks'];assert lib.dococr_job_destroy(job)==0
assert lib.dococr_destroy(engine)==0
report={'environment':'Linux x86_64 生产 C ABI，真实模型；非 Android','loadingSeconds':load,'elapsedSeconds':time.monotonic()-start,'maxRssKiB':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,'repeatedInput':str(OUT/'partial-source.png'),'checks':['真实加载','推理中第二项返回 BUSY','推理中销毁返回 BUSY','安全取消到 terminal','取消无有效正文','同一引擎取消恢复后完整识别与导出','重复作业真实结构化输出','真实空白页'],'cancelled':cancelled,'completed':complete,'outputStatus':complete_ir['status'],'repeated':partial,'repeatedOutputStatus':ir['status'],'blank':blank,'eventsBeforeCancel':events}
report.update({'library':str(library),'threads':threads,'expectedMmap':expected_mmap,'temporaryDirectory':check_cache_directory(runtime_dir.name,expected_mmap)})
(OUT/'lifecycle.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False,indent=2))
runtime_dir.cleanup()
