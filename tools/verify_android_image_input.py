"""通过实际 JNI/C ABI 检查 Android 规范 PNG 输入；Linux 受控后端，非设备推理。"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT.parent / "docprase"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, default=ROOT / "verification/android-image-decode")
parser.add_argument("--production", action="store_true", help="另行运行同源生产模型，使用真实验证图片的 ARGB PNG；非设备推理")
parser.add_argument("--library", type=Path, help="指定同源 C ABI 动态库，可使用 Android 流式适配构建")
parser.add_argument("--require-stream", action="store_true", help="通过实际 JNI 在 run 返回前读取真实正文，需生产流式库")
parser.add_argument("--threads", type=int, choices=[1,2,4], help="生产推理 CPU 线程数")
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)

with tempfile.TemporaryDirectory(prefix="android-ocr-image-") as temporary:
    work = Path(temporary)
    jdk = Path(shutil.which("javac")).resolve().parents[1]
    library = args.library.resolve() if args.library else ENGINE / "build/linux-current" / ("libdococr_c.so" if args.production else "libdococr_c_test.so")
    native_stream = "android_ocr_run_streaming" in subprocess.check_output(["nm", "-D", str(library)], text=True)
    if args.require_stream and (not args.production or not native_stream):
        raise SystemExit("--require-stream 需要包含流式适配的真实模型动态库")
    stream_sources = [] if native_stream else [str(ROOT / "app/src/main/cpp/android_recognition_stream.cpp")]
    subprocess.run([
        "g++", "-std=c++17", "-shared", "-fPIC", str(ROOT / "app/src/main/cpp/dococr_jni.cpp"), *stream_sources,
        "-I" + str(jdk / "include"), "-I" + str(jdk / "include/linux"),
        "-I" + str(ENGINE / "include"), "-I" + str(ENGINE / "third_party/stb"),
        "-I" + str(ENGINE / "src"), "-I" + str(ROOT.parent / "MNN/include"),
        "-I" + str(ROOT.parent / "MNN/transformers/llm/engine/include"),
        "-L" + str(library.parent), "-Wl,-rpath," + str(library.parent),
        "-l:" + library.name, "-o", str(work / "libdococr_jni.so"),
    ], check=True)
    probe = work / "ImageDecodeProbe.java"
    probe.write_text('''package cn.local.ocr;
import java.nio.file.*;
public class ImageDecodeProbe {
 public static void main(String[] args)throws Exception {
  long engine=NativeOcr.create(Files.readString(Path.of(args[1])),args[0]);
  try {
   for(int i=2;i<args.length;i++) {
    long job=NativeOcr.jobCreate(engine);
    try {
     int code;
     if(Boolean.getBoolean("ocr.requireStream")) {
      String imagePath=args[i];
      java.util.concurrent.CompletableFuture<Integer> pending=java.util.concurrent.CompletableFuture.supplyAsync(()->{
       try{return NativeOcr.run(job,imagePath);}catch(Exception e){throw new RuntimeException(e);}
      });
      boolean observed=false;
      while(!pending.isDone()) {
       String live=NativeOcr.status(job);
       if(!pending.isDone()&&live.contains("\\\"raw\\\":\\\"")&&!live.contains("\\\"raw\\\":\\\"\\\"")&&live.contains("\\\"done\\\":false")) {
        observed=true;System.out.println("LIVE_STATUS "+live);
       }
       Thread.sleep(150);
      }
      code=pending.get();if(!observed)throw new AssertionError("实际 JNI 必须在同步 run 返回前读取模型正文");
     } else code=NativeOcr.run(job,args[i]);
     String state=NativeOcr.status(job);
     System.out.println(Path.of(args[i]).getFileName()+" run="+code+" status="+state);
     if(Path.of(args[i]).getFileName().toString().equals("broken.png")) {
      if(code!=5||!state.contains("input_error"))throw new AssertionError("损坏 PNG 应仍报告输入错误");
      continue;
     }
     if(code!=0)throw new AssertionError("PNG 应可识别，实际 run="+code);
     NativeOcr.export(job,args[i]+".out");
    } finally {NativeOcr.jobDestroy(job);}
   }
  } finally {NativeOcr.destroy(engine);}
 }
}''')
    subprocess.run(["javac", "-d", str(work), str(ROOT / "app/src/main/java/cn/local/ocr/NativeOcr.java"), str(probe)], check=True)

    rgb = Image.new("RGB", (2, 2))
    rgb.putdata([(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255)])
    rgb.save(work / "rgb.png")
    rgb.convert("RGBA").save(work / "android-argb.png")
    # 固定期望来自把透明内容放在白纸上的结果，不重算适配实现。
    alpha = Image.new("RGBA", (2, 2))
    alpha.putdata([(255, 0, 0, 128), (0, 255, 0, 0), (0, 0, 255, 255), (0, 0, 0, 128)])
    alpha.save(work / "transparent.png")
    white = Image.new("RGB", (2, 2))
    white.putdata([(255, 127, 127), (255, 255, 255), (0, 0, 255), (127, 127, 127)])
    white.save(work / "white-paper.png")
    gray = Image.new("LA", (2, 2))
    gray.putdata([(0, 255), (128, 255), (0, 0), (0, 128)])
    gray.save(work / "gray-alpha.png")
    expected_gray = Image.new("RGB", (2, 2))
    expected_gray.putdata([(0, 0, 0), (128, 128, 128), (255, 255, 255), (127, 127, 127)])
    expected_gray.save(work / "gray-paper.png")
    (work / "broken.png").write_bytes((work / "android-argb.png").read_bytes()[:40])

    config = json.loads((ENGINE / "configs/fixture-plan.example.json").read_text())
    config["backend"] = "fixture:sample"
    for model in config["models"].values():
        model["root"] = str(ENGINE / "tests/fixtures")
    (work / "config.json").write_text(json.dumps(config))
    names = ["rgb", "android-argb", "transparent", "white-paper", "gray-alpha", "gray-paper", "broken", "rgb"]
    if args.production:
        with Image.open(ROOT / "verification/real-ocr/source.png") as source:
            source.convert("RGBA").save(work / "production-argb.png")
        config = json.loads((ROOT / "app/src/main/assets/ocr/config.json").read_text())
        if args.threads is not None: config["platform"]["threads"] = args.threads
        config["models"]["layout"]["root"] = str(ENGINE / "models/doclayout")
        config["models"]["recognition"]["root"] = str(ENGINE / "models/ovis")
        (work / "config.json").write_text(json.dumps(config))
        names = ["production-argb"]
    result = subprocess.run([
        "java", "-Docr.requireStream=" + str(args.require_stream).lower(), "-Djava.library.path=" + str(work), "-cp", str(work),
        "cn.local.ocr.ImageDecodeProbe", str(work), str(work / "config.json"), *[str(work / (name + ".png")) for name in names],
    ], capture_output=True, text=True)
    (output / "jni.log").write_text(result.stdout + result.stderr)
    print(result.stdout, end="")
    print(result.stderr, end="")
    if result.returncode:
        raise SystemExit(result.returncode)
    stream_evidence = None
    if args.require_stream:
        snapshots = [json.loads(line[len("LIVE_STATUS "):]) for line in result.stdout.splitlines() if line.startswith("LIVE_STATUS ")]
        partial = [state for state in snapshots if any(region['raw'] and not region['done'] for region in state['stream']['regions'])]
        assert partial, "实际 JNI 必须在区域生成结束前读取正文，不能只读取之前已完成区域"
        stream_evidence = {"capturedSnapshots": len(snapshots), "partialRegionSnapshots": len(partial), "beforeRunReturned": True}
        (output / "stream-snapshots.json").write_text(json.dumps(snapshots, ensure_ascii=False, indent=2)+"\n")
    if args.production:
        directory = work / "production-argb.png.out"
        document = json.loads((directory / "document.json").read_text())
        assert document["pages"][0]["blocks"], "生产模型应产生实际内容"
        assert "离线文档识别验证" in (directory / "document.md").read_text()
        shutil.copytree(directory, output / "output", dirs_exist_ok=True)
        shutil.copyfile(work / "production-argb.png", output / "source.png")
        shutil.copyfile(work / "config.json", output / "config.json")
        (output / "result.json").write_text(json.dumps({
            "environment": "Linux JVM → 实际 App JNI → 同源生产 C ABI 与真实模型，非 Android 设备",
            "input": "verification/real-ocr/source.png 转为 Android 同型 RGBA PNG，本次重新推理",
            "status": document["status"], "blocks": len(document["pages"][0]["blocks"]), "streamEvidence": stream_evidence,
        }, ensure_ascii=False, indent=2) + "\n")
        print("PASS RGBA PNG 经修复后 JNI 实际生产推理与导出")
        raise SystemExit(0)
    for actual, expected in [("android-argb", "rgb"), ("transparent", "white-paper"), ("gray-alpha", "gray-paper")]:
        actual_dir, expected_dir = work / (actual + ".png.out"), work / (expected + ".png.out")
        assert (actual_dir / "document.json").read_bytes() == (expected_dir / "document.json").read_bytes(), actual
        for asset in (expected_dir / "assets").rglob("*"):
            if asset.is_file():
                assert (actual_dir / asset.relative_to(expected_dir)).read_bytes() == asset.read_bytes(), actual
    (output / "result.json").write_text(json.dumps({
        "environment": "Linux JVM → 实际 App JNI → 同源 C ABI，受控识别后端，非 Android 模型推理",
        "passed": ["RGB PNG", "Android ARGB 规范 PNG", "RGBA 透明内容以白纸合成", "灰度 Alpha 以白纸合成", "原始 RGB 内容与资源保持一致", "损坏输入仍失败且同引擎可以重试"],
    }, ensure_ascii=False, indent=2) + "\n")
    print("PASS PNG 通道兼容及实际输出像素一致性")
