"""检查 APK 的原生打包、符号、依赖与编译后端；不执行设备推理。"""
from pathlib import Path
from zipfile import ZipFile
import subprocess,tempfile,json,hashlib
root=Path(__file__).resolve().parents[1]
readelf=Path('/home/dr/Android/Sdk/ndk/28.1.13356709/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf')
platform={'libc.so','libdl.so','libm.so','liblog.so','libandroid.so','libz.so'}
reports=[]
for flavor in ['user','lab']:
    apk=root/f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk'
    with ZipFile(apk) as archive,tempfile.TemporaryDirectory() as tmp:
        names=archive.namelist();libs={Path(p).name:p for p in names if p.startswith('lib/') and p.endswith('.so')}
        assert all(p.startswith('lib/arm64-v8a/') for p in libs.values())
        assert {'libdococr_jni.so','libdococr_c.so','libMNN.so','libMNN_Express.so','libMNNOpenCV.so','libllm.so','libc++_shared.so'}<=libs.keys()
        assert not any(p.endswith(('.mnn','.weight','.mtok')) for p in names)
        info={}
        for name,path in libs.items():
            f=Path(tmp)/name;f.write_bytes(archive.read(path))
            header=subprocess.check_output([readelf,'-h',f],text=True);assert 'AArch64' in header
            dynamic=subprocess.check_output([readelf,'-d',f],text=True)
            deps=[line.split('[')[1].split(']')[0] for line in dynamic.splitlines() if '(NEEDED)' in line]
            assert set(deps)<=libs.keys()|platform,(name,deps)
            symbols=subprocess.check_output([readelf,'--dyn-syms',f],text=True)
            if name=='libdococr_c.so':
                for symbol in ['dococr_create','dococr_job_run','dococr_job_status','dococr_job_next_event','dococr_job_cancel','dococr_job_result','dococr_job_asset','dococr_job_destroy','dococr_destroy']:
                    assert any(symbol in line and ' UND ' not in line for line in symbols.splitlines()),symbol
            if name=='libdococr_jni.so':
                for method in ['create','jobCreate','run','status','cancel','export','jobDestroy','destroy']:assert 'Java_cn_local_ocr_NativeOcr_'+method in symbols
            if name=='libllm.so':assert 'createLLM' in symbols
            info[name]={'needed':deps,'size':len(archive.read(path))}
        reports.append({'flavor':flavor,'path':str(apk),'sha256':hashlib.file_digest(apk.open('rb'),'sha256').hexdigest(),'size':apk.stat().st_size,'abi':'arm64-v8a','libraries':info,'weightsPackaged':False})
commands=list((root/'app/.cxx').rglob('compile_commands.json'))
assert commands
compiled=[x for f in commands for x in json.loads(f.read_text()) if x['file'].endswith('printed_page_mnn_backend.cpp')]
assert compiled and all('DOCOCR_HAS_MNN=1' in c['command'] and 'DOCOCR_HAS_LLM=1' in c['command'] for c in compiled)
(root/'verification/real-ocr/apk.json').write_text(json.dumps({'environment':'Android NDK 编译与静态打包检查，未在设备加载','productionBackendCompileCommands':compiled,'apks':reports},indent=2)+'\n')
print('PASS arm64-v8a、公共 C ABI/JNI/LLM 符号、依赖闭合、生产后端编译、未打包权重')
