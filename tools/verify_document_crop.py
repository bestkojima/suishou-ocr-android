"""在 Linux OpenCV Java 运行库上验证生产边界检测/透视代码；不代替 Android 真机验收。"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--opencv-jar', type=Path, required=True, help='带 Linux 原生库的 org.openpnp:opencv:4.9.0-0 JAR')
parser.add_argument('--image', type=Path, default=ROOT / 'verification/real-ocr/source.png')
parser.add_argument('--output', type=Path, default=ROOT / 'verification/document-crop/algorithm')
args = parser.parse_args()
jar = args.opencv_jar.resolve()
args.output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='document-crop-java-') as temporary:
    sources = [ROOT / 'app/src/main/java/cn/local/ocr' / name for name in ['CropGeometry.java', 'DocumentBoundaryDetector.java']]
    sources.append(ROOT / 'app/src/test/host/DocumentCropProbe.java')
    subprocess.run(['javac', '-cp', str(jar), '-d', temporary, *map(str, sources)], check=True)
    subprocess.run(['java', '-cp', str(jar) + ':' + temporary, 'cn.local.ocr.DocumentCropProbe', str(args.image.resolve()), str(args.output.resolve())], check=True)
(args.output / 'runtime-sha256.txt').write_text(hashlib.sha256(jar.read_bytes()).hexdigest() + '  ' + jar.name + '\n')
