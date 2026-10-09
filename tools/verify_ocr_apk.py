"""检查 APK 的原生打包、符号、依赖与编译后端；不执行设备推理。"""
from pathlib import Path
from zipfile import ZipFile
import argparse,subprocess,tempfile,json,hashlib,os,platform as host_platform,shutil,re,shlex
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,default=root/'verification/real-ocr/apk.json',help='APK 静态检查报告路径')
parser.add_argument('--readelf',type=Path,help='指定 NDK llvm-readelf（或 .exe）路径')
parser.add_argument('--require-native-release',action='store_true',help='要求 APK 对应原生构建为 Release/-O3/NDEBUG，MNN 无调试宏')
parser.add_argument('--require-document-crop',action='store_true',help='要求文档裁剪界面、OpenCV 和当前 NDK libc++ 已打包')
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
platform={'libc.so','libdl.so','libm.so','liblog.so','libandroid.so','libz.so','libjnigraphics.so','libmediandk.so'}
def build_id(path):
    notes=subprocess.check_output([readelf,'--notes',path],text=True)
    found=re.search(r'Build ID:\s*([0-9a-fA-F]+)',notes)
    assert found,('缺少 ELF build ID',str(path))
    return found.group(1)

def native_build(flavor,library_ids):
    # 用 APK 中三个引擎库的 build ID 关联 AGP 实际构建，不能扫描旧缓存后混合验收。
    matches=[]
    for model_path in (root/'app/build/intermediates/cxx').rglob('build_model.json'):
        model=json.loads(model_path.read_text())
        folder=Path(model['cxxBuildFolder'])
        metadata=folder/'android_gradle_build.json'
        if not metadata.is_file():continue
        libraries={item['artifactName']:Path(item['output']) for item in json.loads(metadata.read_text())['libraries'].values() if 'output' in item}
        required={'dococr_jni':'libdococr_jni.so','dococr_c':'libdococr_c.so','MNN':'libMNN.so'}
        if not all(name in libraries and libraries[name].is_file() for name in required):continue
        if all(build_id(libraries[name])==library_ids[filename] for name,filename in required.items()):
            matches.append((model_path,folder))
    assert matches,('APK 无匹配的原生构建记录，请先构建该 APK',flavor)
    # AGP 可让两个 flavor 共用相同原生构建；不同原生目录同时匹配时拒绝猜测。
    folders={folder.resolve() for _,folder in matches}
    assert len(folders)==1,('APK 对应多个原生目录，无法唯一确定编译证据',flavor,sorted(map(str,folders)))
    model_path,folder=matches[0]
    cache=dict(line.split('=',1) for line in (folder/'CMakeCache.txt').read_text().splitlines() if '=' in line and not line.startswith(('#','//')))
    rows=json.loads((folder/'compile_commands.json').read_text())
    backend=[r for r in rows if r['file'].endswith('printed_page_mnn_backend.cpp')]
    assert backend and all('DOCOCR_HAS_MNN=1' in r['command'] and 'DOCOCR_HAS_LLM=1' in r['command'] for r in backend)
    native_rows=[r for r in rows if Path(r['file']).suffix in {'.c','.cc','.cpp','.cxx'}]
    groups={
        'MNN_CPU':[r for r in native_rows if '/source/backend/cpu/' in r['file'].replace('\\','/')],
        'MNN_LLM':[r for r in native_rows if '/transformers/llm/engine/src/' in r['file'].replace('\\','/')],
        'docprase':[r for r in native_rows if '/docprase/' in r['file'].replace('\\','/') or '/android-engine/' in r['file'].replace('\\','/')],
        'JNI':[r for r in native_rows if r['file'].endswith('dococr_jni.cpp')],
    }
    assert all(groups.values()),('编译记录缺少生产目标',flavor)
    def flags(row):
        parts=row.get('arguments') or shlex.split(row['command'])
        optimization=[p for p in parts if re.fullmatch(r'-O(?:[0-3szg]|fast)',p)]
        macros={};i=0
        while i<len(parts):
            token=parts[i]
            if token in ('-D','-U'):
                i+=1;token+=parts[i]
            if token.startswith(('-D','-U')):macros[token[2:].split('=',1)[0]]=token.startswith('-D')
            i+=1
        return optimization,macros
    if args.require_native_release:
        assert cache.get('CMAKE_BUILD_TYPE:STRING')=='Release',('原生编译类型不是 Release',str(folder))
        for row in native_rows:
            optimization,macros=flags(row)
            assert optimization and optimization[-1]=='-O3',('最终生效优化参数不是 -O3',row['file'],optimization)
            assert macros.get('NDEBUG'),('未定义 NDEBUG',row['file'])
            if '/MNN/' in row['file'].replace('\\','/'):
                assert not macros.get('DEBUG') and not macros.get('MNN_DEBUG'),('MNN 仍含调试宏',row['file'])
    return {'flavor':flavor,'buildModel':str(model_path),'directory':str(folder),'buildType':cache.get('CMAKE_BUILD_TYPE:STRING'),
            'nativeReleaseRequired':args.require_native_release,'translationUnits':len(native_rows),
            'groups':{name:{'translationUnits':len(items),'sample':items[0],'optimization':flags(items[0])[0]} for name,items in groups.items()},
            'productionBackendCompileCommands':backend}

reports=[]
native_reports=[]
for flavor in ['user','lab']:
    apk=root/f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk'
    with ZipFile(apk) as archive,tempfile.TemporaryDirectory() as tmp:
        names=archive.namelist();libs={Path(p).name:p for p in names if p.startswith('lib/') and p.endswith('.so')}
        assert all(p.startswith('lib/arm64-v8a/') for p in libs.values())
        assert {'libdococr_jni.so','libdococr_c.so','libMNN.so','libMNN_Express.so','libMNNOpenCV.so','libllm.so','libc++_shared.so'}<=libs.keys()
        assert not any(p.endswith(('.mnn','.weight','.mtok')) for p in names)
        if args.require_document_crop:
            assert 'libopencv_java4.so' in libs,('缺少文档裁剪 OpenCV',flavor)
            assert 'assets/legal/opencv-LICENSE.txt' in names,('缺少 OpenCV 许可',flavor)
            ui=archive.read('assets/web/app.js').decode()
            ui=re.sub(r'\\u([0-9a-fA-F]{4})',lambda match:chr(int(match.group(1),16)),ui)
            assert all(value in ui for value in ['previewCrop','confirmCrop','beginCrop','确认并识别','预览裁剪']),('未打包裁剪界面',flavor)
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
            info[name]={'needed':deps,'size':len(archive.read(path)),'buildId':build_id(f)}
        if args.require_document_crop:
            assert sdk,'检查当前 NDK libc++ 需要 Android SDK 路径'
            runtime=Path(sdk)/'ndk/28.1.13356709/toolchains/llvm/prebuilt'/host/'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so'
            assert info['libc++_shared.so']['buildId']==build_id(runtime),('APK libc++ 不是当前 MNN 编译使用的 NDK 运行库',flavor)
        native_reports.append(native_build(flavor,{name:item['buildId'] for name,item in info.items()}))
        reports.append({'flavor':flavor,'path':str(apk),'sha256':hashlib.file_digest(apk.open('rb'),'sha256').hexdigest(),'size':apk.stat().st_size,'abi':'arm64-v8a','libraries':info,'weightsPackaged':False})
compiled=[row for report in native_reports for row in report['productionBackendCompileCommands']]
output.write_text(json.dumps({'environment':'Android NDK 编译与静态打包检查，未在设备加载','productionBackendCompileCommands':compiled,'nativeBuilds':native_reports,'apks':reports},indent=2)+'\n')
print('PASS arm64-v8a、公共 C ABI/JNI/LLM 符号、依赖闭合、生产后端编译、未打包权重')
if args.require_native_release:print('PASS APK build ID 关联的原生 Release/-O3/NDEBUG、MNN 无调试宏')
if args.require_document_crop:print('PASS 文档裁剪界面、OpenCV、许可及当前 NDK libc++ 运行库')
