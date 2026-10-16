"""校验随仓库提供的 MNN；维护者可从已验证的 Android 构建重新生成预编译包。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LIBRARIES = ('libMNN.so', 'libMNN_Express.so', 'libMNNOpenCV.so', 'libllm.so')

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def compile_flags(row):
    parts = row.get('arguments') or shlex.split(row['command'])
    optimization = [p for p in parts if re.fullmatch(r'-O(?:[0-3szg]|fast)', p)]
    macros = {}; i = 0
    while i < len(parts):
        token = parts[i]
        if token in ('-D', '-U'):
            i += 1; token += parts[i]
        if token.startswith(('-D', '-U')):
            macros[token[2:].split('=', 1)[0]] = token.startswith('-D')
        i += 1
    return optimization, macros

def verify_bundle(directory=None):
    directory = Path(directory or ROOT / 'native/mnn-prebuilt')
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    pin = json.loads((ROOT / 'native/dependencies.json').read_text(encoding='utf-8'))['mnn']
    if manifest['source']['commit'] != pin['commit'] or manifest['source']['repository'] != pin['repository']:
        raise ValueError('预编译 MNN 来源与固定依赖不符')
    if manifest['android'] != {'abi': 'arm64-v8a', 'minSdk': 26, 'ndk': '28.1.13356709', 'stl': 'c++_shared'}:
        raise ValueError('预编译 MNN 的 Android 构建契约不符')
    if set(manifest['libraries']) != set(LIBRARIES):
        raise ValueError('预编译 MNN 分库不完整')
    evidence = manifest['build']
    if evidence['buildType'] != 'Release' or not evidence['translationUnits']:
        raise ValueError('预编译 MNN 缺少 Release 编译证据')
    for group in ('MNN_CPU', 'MNN_LLM'):
        if not evidence['groups'].get(group, 0):
            raise ValueError('预编译 MNN 缺少 CPU/LLM 编译证据')
    for row in evidence['translationUnits']:
        if row['optimization'][-1:] != ['-O3'] or not row['macros'].get('NDEBUG') or row['macros'].get('DEBUG') or row['macros'].get('MNN_DEBUG'):
            raise ValueError('预编译 MNN 含未优化或调试编译单元')
    files = manifest['filesSha256']
    for name in LIBRARIES:
        path = 'lib/arm64-v8a/' + name
        if files.get(path) != manifest['libraries'][name]['sha256']:
            raise ValueError('预编译 MNN 库清单不一致：' + name)
    for required in ('include/MNN/Interpreter.hpp', 'include/llm/llm.hpp', 'LICENSE.txt'):
        if required not in files:
            raise ValueError('预编译 MNN 缺少头文件/许可：' + required)
    for relative, expected in files.items():
        path = (directory / relative).resolve()
        if not path.is_relative_to(directory.resolve()) or not path.is_file() or digest(path) != expected:
            raise ValueError('预编译 MNN 文件缺失或校验失败：' + relative)
    return manifest

def package(source, build, output):
    pin = json.loads((ROOT / 'native/dependencies.json').read_text())['mnn']
    def run(*command):
        return subprocess.check_output(list(map(str, command)), text=True).strip()
    if run('git', '-C', source, 'rev-parse', 'HEAD') != pin['commit'] or run('git', '-C', source, 'status', '--porcelain', '--untracked-files=no'):
        raise ValueError('请使用无修改的固定 MNN 提交')
    cache = dict(line.split('=', 1) for line in (build / 'CMakeCache.txt').read_text().splitlines() if '=' in line and not line.startswith(('#', '//')))
    if Path(cache['MNN_SOURCE_ROOT:PATH']).resolve() != source.resolve():
        raise ValueError('构建记录与指定 MNN 源码不同')
    if cache['CMAKE_BUILD_TYPE:STRING'] != 'Release' or cache['CMAKE_SYSTEM_VERSION:UNINITIALIZED'] != '26':
        raise ValueError('需要 Release / Android API 26 构建')
    if cache['MNN_SEP_BUILD:BOOL'] != 'ON':
        raise ValueError('需要 MNN 分库构建')
    ndk = Path(cache['CMAKE_ANDROID_NDK:UNINITIALIZED'])
    if '28.1.13356709' not in (ndk / 'source.properties').read_text():
        raise ValueError('需要项目固定 NDK')
    rows = json.loads((build / 'compile_commands.json').read_text())
    units = []
    for row in rows:
        path = Path(row['file'])
        if not path.is_relative_to(source) or path.suffix not in ('.c', '.cc', '.cpp', '.cxx'):
            continue
        optimization, macros = compile_flags(row)
        units.append({'file': path.relative_to(source).as_posix(), 'optimization': optimization, 'macros': macros})
    manifest = {'schemaVersion': 1, 'source': {'repository': pin['repository'], 'commit': pin['commit'], 'version': '3.6.1'},
        'android': {'abi': 'arm64-v8a', 'minSdk': 26, 'ndk': '28.1.13356709', 'stl': 'c++_shared'},
        'build': {'buildType': 'Release', 'translationUnits': units, 'groups': {
            'MNN_CPU': sum('source/backend/cpu/' in r['file'] for r in units),
            'MNN_LLM': sum('transformers/llm/engine/src/' in r['file'] for r in units)}}, 'libraries': {}}
    output.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source / 'include/MNN', output / 'include/MNN', dirs_exist_ok=True)
    (output / 'include/llm').mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / 'transformers/llm/engine/include/llm/llm.hpp', output / 'include/llm/llm.hpp')
    shutil.copy2(source / 'LICENSE.txt', output / 'LICENSE.txt')
    shutil.copytree(source / '3rd_party/lic', output / 'licenses', dirs_exist_ok=True)
    for name in ('flatbuffers/LICENSE.txt', 'rapidjson/license.txt', 'half/LICENSE.txt'):
        shutil.copy2(source / '3rd_party' / name, output / 'licenses' / (name.split('/')[0] + '-LICENSE.txt'))
    stb = (source / '3rd_party/imageHelper/stb_image.h').read_text()
    notice = stb[stb.rindex('This software is available under 2 licenses'):]
    (output / 'licenses/stb-LICENSE.txt').write_text(notice, encoding='utf-8')
    shutil.copy2(source / '3rd_party/protobuf/LICENSE', output / 'licenses/protobuf-LICENSE.txt')
    artifacts = {v['artifactName']: Path(v['output']) for v in json.loads((build / 'android_gradle_build.json').read_text())['libraries'].values() if 'output' in v}
    for name in LIBRARIES:
        dest = output / 'lib/arm64-v8a' / name; dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(artifacts[name[3:-3]], dest)
        subprocess.run([cache['CMAKE_STRIP:FILEPATH'], '--strip-unneeded', str(dest)], check=True)
        notes = run(cache['CMAKE_READELF:FILEPATH'], '--notes', dest)
        dynamic = run(cache['CMAKE_READELF:FILEPATH'], '-d', dest)
        manifest['libraries'][name] = {'sha256': digest(dest), 'buildId': re.search(r'Build ID:\s*([0-9a-fA-F]+)', notes).group(1),
            'bytes': dest.stat().st_size, 'needed': re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)}
    manifest['filesSha256'] = {p.relative_to(output).as_posix(): digest(p) for p in sorted(output.rglob('*')) if p.is_file() and p.name not in ('manifest.json', 'README.md')}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    verify_bundle(output)
    print('已生成 MNN 预编译包：', output)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path)
    parser.add_argument('--build-dir', type=Path)
    parser.add_argument('--directory', type=Path, default=ROOT / 'native/mnn-prebuilt')
    args = parser.parse_args()
    if args.source_root or args.build_dir:
        if not args.source_root or not args.build_dir:
            parser.error('重新打包需要同时指定 --source-root 和 --build-dir')
        package(args.source_root.resolve(), args.build_dir.resolve(), args.directory.resolve())
    else:
        result = verify_bundle(args.directory)
        print('预编译 MNN 已就绪：', result['source']['commit'], 'arm64-v8a')

if __name__ == '__main__':
    main()
