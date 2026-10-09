#!/usr/bin/env python3
"""一键构建、提交、推送并发布两版 APK；上传成功后不下载复核。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\Z")
SIGNING_SHA256 = "d1f75d2fcad2ee6b10351eb0f1a4da6b94c7069a0f8cadcce57d771c30242f33"
GRADLE_TASKS = [
    ":app:testUserDebugUnitTest", ":app:testLabDebugUnitTest",
    ":app:lintUserDebug", ":app:lintLabDebug",
    ":app:assembleUserDebug", ":app:assembleLabDebug",
]


class ReleaseError(Exception):
    pass


def version_tuple(value):
    if not VERSION.fullmatch(value):
        raise ReleaseError("版本格式应为 0.7.8 这样的三段数字，不带 v 或 -ocr。")
    return tuple(map(int, value.split(".")))


def github_repository(url):
    match = re.fullmatch(r"(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)([^/]+/[^/]+?)(?:\.git)?/?", url)
    if not match:
        raise ReleaseError("发布远端必须是 github.com 仓库的 HTTPS 或 SSH 地址。")
    return match.group(1)


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("version", nargs="?", help="目标版本；省略时递增当前 patch，例如 0.7.7 → 0.7.8")
    notes = result.add_mutually_exclusive_group()
    notes.add_argument("--change", action="append", help="本次改进，可重复传入；自动生成中文发布说明")
    notes.add_argument("--notes-file", type=Path, help="使用已有 UTF-8 Markdown 发布说明")
    result.add_argument("--remote", default="origin", help="Git 远端，默认 origin")
    result.add_argument("--branch", default="main", help="代码推送目标分支，默认 main")
    result.add_argument("--stable", action="store_true", help="明确发布正式版；默认始终为预发布")
    result.add_argument("--dry-run", action="store_true", help="只展示计划，不改文件、不构建、不提交、不联网")
    result.add_argument("--resume", metavar="TAG", help="从本地状态继续，例如 v0.7.8；不重复构建或提交")
    return result


class Publisher:
    def __init__(self, root, args):
        self.root = Path(root).resolve()
        self.args = args
        self.stage = "准备"
        self.output = None
        self.state = None

    def run(self, command, capture=False, allowed=(0,)):
        result = subprocess.run(
            [str(item) for item in command], cwd=self.root, text=True,
            encoding="utf-8", errors="replace",
            stdout=subprocess.PIPE if capture else None,
            env={**os.environ, "PYTHONUTF8": "1"},
        )
        if result.returncode not in allowed:
            raise ReleaseError(f"命令失败（退出码 {result.returncode}）：{' '.join(map(str, command))}")
        return result

    def git(self, *arguments, allowed=(0,)):
        return self.run(["git", *arguments], capture=True, allowed=allowed)

    def save_state(self):
        temporary = self.output / "state.json.tmp"
        temporary.write_text(json.dumps(self.state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(self.output / "state.json")

    def preflight(self):
        if not self.git("symbolic-ref", "--quiet", "--short", "HEAD", allowed=(0, 1)).stdout.strip():
            raise ReleaseError("当前处于 detached HEAD；请先切换到工作分支。")
        if self.git("diff", "--name-only", "--diff-filter=U").stdout.strip():
            raise ReleaseError("还有未解决的合并冲突。")
        self.git("check-ref-format", f"refs/heads/{self.args.branch}")
        url = self.git("remote", "get-url", "--push", self.args.remote).stdout.strip()
        return github_repository(url)

    def prepare(self):
        repository = self.preflight()
        gradle_path = self.root / "app/build.gradle"
        gradle = gradle_path.read_text(encoding="utf-8")
        current = re.search(r"\bversionName\s+['\"]([\d.]+)-ocr['\"]", gradle)
        code = re.search(r"\bversionCode\s+(\d+)", gradle)
        if not current or not code:
            raise ReleaseError("无法从 app/build.gradle 读取 versionName/versionCode。")
        old_version = current.group(1)
        old_numbers = version_tuple(old_version)
        version = self.args.version or f"{old_numbers[0]}.{old_numbers[1]}.{old_numbers[2] + 1}"
        numbers = version_tuple(version)
        if numbers < old_numbers:
            raise ReleaseError("目标版本不能小于当前源码版本。")
        if numbers[0] >= 1 and not self.args.stable:
            raise ReleaseError("1.0.0 及以上须通过 --stable 明确宣布正式版；默认继续 0.x 预发布。")
        version_code = int(code.group(1)) + (numbers > old_numbers)
        tag = f"v{version}"
        self.output = self.root / "artifacts/releases" / tag
        if (self.output / "state.json").exists():
            raise ReleaseError(f"已有发布状态，请使用 --resume {tag}。")
        if self.git("show-ref", "--verify", "--quiet", f"refs/tags/{tag}", allowed=(0, 1)).returncode == 0:
            raise ReleaseError(f"标签 {tag} 已存在，请选用新版本；已有标签不会被改动。")
        if self.args.notes_file:
            notes = self.args.notes_file.resolve().read_text(encoding="utf-8").strip()
        elif self.args.change:
            changes = [item.strip() for item in self.args.change]
            if not all(changes):
                raise ReleaseError("--change 不能是空白。")
            notes = self.make_notes(version, changes)
        else:
            raise ReleaseError("请提供 --change 改进说明或 --notes-file 发布说明。")
        if not notes:
            raise ReleaseError("发布说明不能为空。")
        self.state = {
            "version": version, "version_code": version_code, "tag": tag,
            "repository": repository, "remote": self.args.remote, "branch": self.args.branch,
            "title": f"随手识别 {version}" + ("" if self.args.stable else "（预发布）"),
            "prerelease": not self.args.stable, "source_commit": None,
            "pushed": False, "published": False, "post_upload_verification": False,
        }
        self.show_plan()
        if self.args.dry_run:
            return
        self.stage = "发布前准备"
        self.run(["gh", "auth", "status"])
        self.output.mkdir(parents=True, exist_ok=True)
        # 发布状态与 APK 都是本地产物，不能被 git add -A 加入源码提交。
        ignored = self.git("check-ignore", "--quiet", str(self.output / "state.json"), allowed=(0, 1))
        if ignored.returncode != 0:
            raise ReleaseError("请在 .gitignore 保留 artifacts/releases/，避免提交发布状态和安装包。")
        gradle = re.sub(r"\bversionName\s+['\"][\d.]+-ocr['\"]", f"versionName '{version}-ocr'", gradle, count=1)
        gradle = re.sub(r"\bversionCode\s+\d+", f"versionCode {version_code}", gradle, count=1)
        gradle_path.write_text(gradle, encoding="utf-8")
        self.update_readme(old_version, version, version_code)
        notes_path = self.root / "docs/releases" / f"{tag}.md"
        notes_path.parent.mkdir(parents=True, exist_ok=True)
        notes_path.write_text(notes + "\n", encoding="utf-8")
        (self.output / "notes.md").write_text(notes + "\n", encoding="utf-8")
        self.build()
        self.package()
        self.stage = "提交源码"
        self.git("add", "-A")
        if self.git("diff", "--cached", "--quiet", allowed=(0, 1)).returncode:
            self.git("commit", "-m", f"release: 发布 {tag} 并记录改进")
        self.state["source_commit"] = self.git("rev-parse", "HEAD").stdout.strip()
        self.save_state()
        self.publish()

    @staticmethod
    def make_notes(version, changes):
        return (
            f"随手识别 {version} 本次改进：\n\n"
            + "\n".join(f"- {change}" for change in changes)
            + f"\n\n安装包：`suishou-ocr-{version}-arm64.apk`（普通版）和 "
            f"`suishou-ocr-{version}-lab-arm64.apk`（测试版），附 `SHA256SUMS.txt`。"
            "需要 Android 8.0+ / arm64-v8a；APK 不包含模型。"
            "两版沿用原应用标识与 Debug 签名，原生引擎使用 Release／-O3；use_mmap=false。\n\n"
            "发布前执行两版 JVM 测试、lint 和原生 APK 静态检查。"
            "这些检查不能代替真机验收；Titan_1 性能与内存仍待实测，不能据此宣称准确率通过验收。"
            "本次功能效果、真机结果和已知问题请在改进说明中写明；未明确修复的问题沿用 bugdoc.md。\n"
        )

    def show_plan(self):
        state = self.state
        print(f"目标：{state['repository']} / {state['tag']} / "
              f"{'预发布' if state['prerelease'] else '正式版'}；versionCode={state['version_code']}", flush=True)
        print(f"构建两版 → 提交项目全部未忽略改动 → 推送 {state['remote']}/{state['branch']} 和标签 → 上传 3 项资产", flush=True)
        print("上传成功后结束；不查询远端资产、不下载、不进行上传后 SHA 对比。", flush=True)

    def update_readme(self, old_version, version, version_code):
        path = self.root / "README.md"
        readme = path.read_text(encoding="utf-8")
        kind = "预发布" if self.state["prerelease"] else "正式版"
        readme = re.sub(r"当前版本：\*\*[^\n]+?（`versionCode=\d+`，[^）]+）。",
                        f"当前版本：**{version}-ocr**（`versionCode={version_code}`，{kind}）。", readme, count=1)
        # 仅更新下载段落；旧版本功能介绍及实测证据保持原日期和版本。
        start = readme.index("## 下载与安装")
        end = readme.index("## 当前能力", start)
        downloads = readme[start:end].replace(f"/tag/v{old_version}", f"/tag/v{version}")
        downloads = downloads.replace(f"suishou-ocr-{old_version}-", f"suishou-ocr-{version}-")
        path.write_text(readme[:start] + downloads + readme[end:], encoding="utf-8")

    def build(self):
        self.stage = "构建与本地检查"
        mnn = self.root / "native/MNN"
        if not mnn.exists() and (self.root.parent / "MNN/CMakeLists.txt").exists():
            mnn = self.root.parent / "MNN"
        self.run([sys.executable, "tools/setup_native_deps.py", "--destination", mnn])
        if not (self.root / "node_modules/.package-lock.json").exists():
            self.run(["npm.cmd" if os.name == "nt" else "npm", "ci"])
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        self.run([wrapper, *GRADLE_TASKS])
        self.run([
            sys.executable, "tools/verify_ocr_apk.py", "--require-native-release",
            "--require-document-crop", "--output", self.output / "apk-checks.json",
        ])
        self.check_identity()

    def check_identity(self):
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        properties = self.root / "local.properties"
        if not sdk and properties.exists():
            match = re.search(r"^sdk\.dir=(.+)$", properties.read_text(encoding="utf-8"), re.MULTILINE)
            if match:
                sdk = match.group(1).strip().replace("\\\\", "\\").replace("\\:", ":")
        if not sdk:
            raise ReleaseError("请在 local.properties 或 ANDROID_HOME 设置 SDK 路径。")
        tools = Path(sdk) / "build-tools/35.0.0"
        aapt = tools / ("aapt.exe" if os.name == "nt" else "aapt")
        signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
        report = []
        for flavor, application_id in (("user", "cn.local.ocr"), ("lab", "cn.local.ocr.test")):
            apk = self.root / f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"
            metadata = self.run([aapt, "dump", "badging", apk], capture=True).stdout
            identity = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", metadata, re.MULTILINE)
            expected = (application_id, str(self.state["version_code"]), f"{self.state['version']}-ocr")
            if not identity or identity.groups() != expected:
                raise ReleaseError(f"{flavor} 安装包的应用标识或版本与本次发布不同。")
            signature = self.run([signer, "verify", "--print-certs", apk], capture=True).stdout
            digest = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)", signature)
            if not digest or digest.group(1).lower() != SIGNING_SHA256:
                raise ReleaseError(f"{flavor} 签名与已发布版本不同；请使用原 debug.keystore 重新构建。")
            report.append({"flavor": flavor, "application_id": application_id,
                           "version_name": expected[2], "version_code": self.state["version_code"],
                           "certificate_sha256": digest.group(1).lower()})
        (self.output / "identity-checks.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def assets(self):
        version = self.state["version"]
        return [self.output / f"suishou-ocr-{version}-arm64.apk",
                self.output / f"suishou-ocr-{version}-lab-arm64.apk",
                self.output / "SHA256SUMS.txt"]

    def package(self):
        self.stage = "整理安装包"
        apks = self.assets()[:2]
        for flavor, target in zip(("user", "lab"), apks):
            source = self.root / f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"
            if not source.is_file() or source.stat().st_size == 0:
                raise ReleaseError(f"构建没有生成有效安装包：{source}")
            shutil.copy2(source, target)
        sums = []
        for apk in apks:
            digest = hashlib.sha256()
            with apk.open("rb") as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(block)
            sums.append(f"{digest.hexdigest()}  {apk.name}")
        self.assets()[2].write_text("\n".join(sums) + "\n", encoding="utf-8")

    def resume(self):
        if self.args.version or self.args.change or self.args.notes_file or self.args.stable:
            raise ReleaseError("--resume 使用已保存的版本与说明，不能同时修改版本、说明或发布类型。")
        if not re.fullmatch(r"v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", self.args.resume):
            raise ReleaseError("--resume 格式应为 v0.7.8。")
        self.output = self.root / "artifacts/releases" / self.args.resume
        path = self.output / "state.json"
        if not path.is_file():
            raise ReleaseError("没有可继续的提交状态；请修正问题后用相同版本重新运行原命令。")
        self.state = json.loads(path.read_text(encoding="utf-8"))
        if self.state["tag"] != self.args.resume or not self.state["source_commit"]:
            raise ReleaseError("本地发布状态不完整。")
        self.show_plan()
        if self.args.dry_run:
            print("续传将使用保存的提交、安装包和发布说明，不构建、不提交。")
            return
        if not self.state["published"]:
            self.run(["gh", "auth", "status"])
        self.publish()

    def publish(self):
        state = self.state
        if state["published"]:
            print(f"此前已上传成功：{state['url']}；不再查询或下载。", flush=True)
            return
        for path in [*self.assets(), self.output / "notes.md"]:
            if not path.is_file() or not path.stat().st_size:
                raise ReleaseError(f"发布文件缺失或为空：{path}")
        self.stage = "创建标签"
        tag_ref = f"refs/tags/{state['tag']}"
        if self.git("show-ref", "--verify", "--quiet", tag_ref, allowed=(0, 1)).returncode == 0:
            tagged_commit = self.git("rev-parse", f"{tag_ref}^{{commit}}").stdout.strip()
            if tagged_commit != state["source_commit"]:
                raise ReleaseError("已有标签对应不同提交，拒绝覆盖。")
        else:
            self.git("tag", "-a", state["tag"], state["source_commit"], "-m", state["title"])
        if not state["pushed"]:
            self.stage = "推送代码与标签"
            repository = github_repository(self.git("remote", "get-url", "--push", state["remote"]).stdout.strip())
            if repository != state["repository"]:
                raise ReleaseError("远端仓库与原发布状态不同。")
            self.run(["git", "push", "--atomic", state["remote"],
                      f"{state['source_commit']}:refs/heads/{state['branch']}", tag_ref])
            state["pushed"] = True
            self.save_state()
        self.stage = "上传发布"
        command = ["gh", "release", "create", state["tag"], *self.assets(),
                   "--repo", state["repository"], "--verify-tag", "--title", state["title"],
                   "--notes-file", self.output / "notes.md"]
        if state["prerelease"]:
            command.append("--prerelease")
        result = self.run(command, capture=True)
        state["published"] = True
        state["url"] = result.stdout.strip() or f"https://github.com/{state['repository']}/releases/tag/{state['tag']}"
        print(f"发布成功：{state['url']}\n普通版、测试版和 SHA256SUMS.txt 已上传。", flush=True)
        try:
            self.save_state()
        except OSError as error:
            print(f"上传已成功，但本地状态保存失败：{error}；请勿再次续传同一版本。", file=sys.stderr)


def main(argv=None):
    args = parser().parse_args(argv)
    publisher = Publisher(ROOT, args)
    try:
        if args.resume:
            publisher.resume()
        else:
            publisher.prepare()
    except (ReleaseError, OSError, ValueError, KeyError) as error:
        print(f"\n停止于「{publisher.stage}」：{error}", file=sys.stderr)
        if publisher.output and (publisher.output / "state.json").exists():
            print(f"可继续：python3 tools/release.py --resume {publisher.output.name}", file=sys.stderr)
        else:
            print("修正后用相同版本重试；已修改的源码和已生成的安装包保留。", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n已中断；源码、安装包和发布状态保留。", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
