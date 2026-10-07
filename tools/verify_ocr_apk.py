"""检查 APK 的原生打包、符号、依赖与编译后端；不执行设备推理。"""
from pathlib import Path
from zipfile import ZipFile
import argparse,subprocess,tempfile,json,hashlib,os,platform as host_platform,shutil
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,default=root/'verification/real-ocr/apk.json',help='APK 静态检查报告路径')
parser.add_argument('--readelf',type=Path,help='指定 NDK llvm-readelf（或 .exe）路径')
args=parser.parse_args()
output=args.output.resolve()
output.parent.mkdir(parents=True,exist_ok=True)
sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk and (root/'local.properties').is_file():
    for line in (root/'local.properties').read_text().splitlines():
        if line.startswith('sdk.dir='):sdk=line.split('=',1)[1].strip().replace('\\\\','\\').replace('\\:',':')
host={'Linux':'linux-x86_64','Darwin':'darwin-x86_64','Windows':'windows-x86_64'}[host_platform.system()]
name='llvm-readelf.exe' if host_platform.system()=='Windows' else 'llvm-readelf'
readelf=args.readelf or (Path(sdk)/'ndk/28.1.13356709/toolchains/llvm/prebuilt'/host/'bin'/name if sdk else shutil.which(name))
if not readelf or not Path(readelf).is_file():parser.error('请设置 ANDROID_HOME/local.properties 或用 --readelf 指定 NDK 工具')
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
                for symbol in ['dococr_create','dococr_job_run','dococr_job_status','dococr_job_next_event','dococr_job_cancel','dococr_job_result','dococr_job_asset','dococr_job_destroy','dococr_destroy','android_ocr_run_streaming','android_ocr_stream_snapshot','android_ocr_stream_forget']:
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
output.write_text(json.dumps({'environment':'Android NDK 编译与静态打包检查，未在设备加载','productionBackendCompileCommands':compiled,'apks':reports},indent=2)+'\n')
print('PASS arm64-v8a、公共 C ABI/JNI/LLM 符号、依赖闭合、生产后端编译、未打包权重')
