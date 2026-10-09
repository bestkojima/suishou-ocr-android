"""临时 Git 仓库上的发布流程测试；不构建真实 APK，不连接 GitHub。"""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "release.py"
SPEC = importlib.util.spec_from_file_location("release_tool", MODULE_PATH)
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class SimulatedPublisher(release.Publisher):
    """Git 提交/标签/推送真实执行；构建工具和 GitHub 只记录命令。"""

    def __init__(self, root, args, failure=None):
        super().__init__(root, args)
        self.commands = []
        self.failure = failure

    def run(self, command, capture=False, allowed=(0,)):
        command = list(map(str, command))
        self.commands.append(command)
        executable = Path(command[0]).name
        output = ""
        if command[:3] == ["git", "remote", "get-url"]:
            output = "https://github.com/test-owner/ocr.git\n"
        elif command[:3] == ["gh", "auth", "status"]:
            if self.failure == "auth":
                raise release.ReleaseError("模拟登录失败")
        elif command[:3] == ["gh", "release", "create"]:
            if self.failure == "upload":
                raise release.ReleaseError("模拟上传失败")
            output = f"https://github.com/test-owner/ocr/releases/tag/{self.state['tag']}\n"
        elif command[:3] == ["git", "push", "--atomic"] and self.failure == "push":
            raise release.ReleaseError("模拟推送失败")
        elif executable in ("gradlew", "gradlew.bat"):
            if self.failure == "build":
                raise release.ReleaseError("模拟构建失败")
            for flavor in ("user", "lab"):
                path = self.root / f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(f"{flavor} apk {self.state['version']}".encode())
        elif executable in ("aapt", "aapt.exe"):
            flavor = "lab" if "app-lab-debug.apk" in command[-1] else "user"
            app_id = "cn.local.ocr.test" if flavor == "lab" else "cn.local.ocr"
            code = self.state["version_code"] + (self.failure == "metadata")
            output = f"package: name='{app_id}' versionCode='{code}' versionName='{self.state['version']}-ocr'\n"
        elif executable in ("apksigner", "apksigner.bat"):
            digest = "0" * 64 if self.failure == "signing" else release.SIGNING_SHA256
            output = f"Signer #1 certificate SHA-256 digest: {digest}\n"
        elif command[1:2] in (["tools/setup_native_deps.py"], ["tools/verify_ocr_apk.py"]):
            pass
        elif executable in ("npm", "npm.cmd"):
            pass
        else:
            return super().run(command, capture=capture, allowed=allowed)
        return subprocess.CompletedProcess(command, 0, stdout=output)


class ReleaseFlowTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.root = self.base / "work"
        self.root.mkdir()
        self.remote = self.base / "remote.git"
        subprocess.run(["git", "init", "--bare", "--quiet", str(self.remote)], check=True)
        self.git("init", "--quiet", "-b", "codex/test-release")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release-test@example.invalid")
        self.git("remote", "add", "origin", str(self.remote))
        (self.root / "app").mkdir()
        (self.root / "app/build.gradle").write_text(
            "defaultConfig { versionCode 14; versionName '0.7.7-ocr' }\n", encoding="utf-8")
        (self.root / "README.md").write_text(
            "当前版本：**0.7.7-ocr**（`versionCode=14`，预发布）。Java。\n\n"
            "0.7.7 历史功能介绍。\n\n## 下载与安装\n\n"
            "https://github.com/test-owner/ocr/releases/tag/v0.7.7\n"
            "suishou-ocr-0.7.7-arm64.apk\nsuishou-ocr-0.7.7-lab-arm64.apk\n\n"
            "## 当前能力\n\n0.7.7 的历史证据保留。\n", encoding="utf-8")
        (self.root / ".gitignore").write_text("artifacts/releases/\n**/build/\n", encoding="utf-8")
        self.git("add", "-A")
        self.git("commit", "--quiet", "-m", "baseline")
        self.baseline = self.git("rev-parse", "HEAD")
        self.environment = patch.dict(os.environ, {"ANDROID_HOME": str(self.base / "sdk")})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()

    def publisher(self, *args, failure=None):
        return SimulatedPublisher(self.root, release.parser().parse_args(args), failure=failure)

    def invoke(self, publisher):
        with contextlib.redirect_stdout(io.StringIO()):
            if publisher.args.resume:
                publisher.resume()
            else:
                publisher.prepare()

    def assert_not_uploaded(self, publisher):
        self.assertFalse(any(command[:3] == ["gh", "release", "create"] for command in publisher.commands))

    def test_build_commit_push_release_and_no_post_upload_commands(self):
        (self.root / "feature.txt").write_text("本次源码修改", encoding="utf-8")
        publisher = self.publisher("--change", "修复生成显示", "--change", "保持版面检测")
        self.invoke(publisher)
        self.assertEqual(publisher.state["version"], "0.7.8")
        self.assertEqual(publisher.state["version_code"], 15)
        self.assertTrue(publisher.state["published"])
        self.assertFalse(publisher.state["post_upload_verification"])
        source = publisher.state["source_commit"]
        self.assertNotEqual(source, self.baseline)
        self.assertEqual(self.git("rev-parse", "v0.7.8^{commit}"), source)
        remote_source = subprocess.check_output(
            ["git", "--git-dir", str(self.remote), "rev-parse", "main"], text=True).strip()
        self.assertEqual(remote_source, source)
        self.assertIn("feature.txt", self.git("ls-tree", "--name-only", "HEAD"))
        self.assertFalse(self.git("ls-files", "artifacts/releases"))
        self.assertFalse(self.git("status", "--porcelain"))
        readme = (self.root / "README.md").read_text(encoding="utf-8")
        self.assertIn("**0.7.8-ocr**（`versionCode=15`，预发布）", readme)
        self.assertIn("releases/tag/v0.7.8", readme)
        self.assertIn("0.7.7 历史功能介绍", readme)
        notes = (self.root / "docs/releases/v0.7.8.md").read_text(encoding="utf-8")
        self.assertIn("修复生成显示", notes)
        self.assertIn("保持版面检测", notes)
        checksums = publisher.assets()[2].read_text(encoding="utf-8").splitlines()
        for apk, line in zip(publisher.assets()[:2], checksums):
            self.assertEqual(line, f"{hashlib.sha256(apk.read_bytes()).hexdigest()}  {apk.name}")
        # 上传必须是整个流程中的最后一条外部命令。
        upload = publisher.commands[-1]
        self.assertEqual(upload[:4], ["gh", "release", "create", "v0.7.8"])
        self.assertEqual(upload[4:7], list(map(str, publisher.assets())))
        self.assertIn("--prerelease", upload)
        self.assertFalse(any(command[:3] in (["gh", "release", "download"], ["gh", "release", "view"])
                             or command[:2] == ["gh", "api"] for command in publisher.commands))

    def test_dry_run_is_read_only_and_offline(self):
        before = {path: path.read_bytes() for path in (self.root / "app/build.gradle", self.root / "README.md")}
        publisher = self.publisher("--change", "说明", "--dry-run")
        self.invoke(publisher)
        self.assertEqual({path: path.read_bytes() for path in before}, before)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.baseline)
        self.assertFalse((self.root / "artifacts").exists())
        self.assertTrue(all(command[0] == "git" and command[1] not in ("push", "fetch", "add", "commit", "tag")
                            for command in publisher.commands))

    def test_auth_failure_does_not_change_source(self):
        publisher = self.publisher("--change", "说明", failure="auth")
        with self.assertRaisesRegex(release.ReleaseError, "登录失败"):
            self.invoke(publisher)
        self.assertFalse(self.git("status", "--porcelain"))
        self.assertEqual(self.git("rev-parse", "HEAD"), self.baseline)
        self.assert_not_uploaded(publisher)

    def test_build_failure_never_commits_pushes_or_uploads(self):
        publisher = self.publisher("--change", "说明", failure="build")
        with self.assertRaisesRegex(release.ReleaseError, "构建失败"):
            self.invoke(publisher)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.baseline)
        self.assertFalse(self.git("tag"))
        self.assertFalse((publisher.output / "state.json").exists())
        self.assert_not_uploaded(publisher)
        # 用同一显式版本重试，不再次递增 versionCode。
        retry = self.publisher("0.7.8", "--change", "说明")
        self.invoke(retry)
        self.assertEqual(retry.state["version_code"], 15)

    def test_identity_and_signature_failure_stop_before_commit(self):
        for failure in ("metadata", "signing"):
            with self.subTest(failure=failure):
                publisher = self.publisher("0.7.8", "--change", "说明", failure=failure)
                with self.assertRaises(release.ReleaseError):
                    self.invoke(publisher)
                self.assertEqual(self.git("rev-parse", "HEAD"), self.baseline)
                self.assert_not_uploaded(publisher)

    def test_resume_upload_failure_reuses_commit_apks_and_push(self):
        publisher = self.publisher("--change", "说明", failure="upload")
        with self.assertRaisesRegex(release.ReleaseError, "上传失败"):
            self.invoke(publisher)
        saved = json.loads((publisher.output / "state.json").read_text(encoding="utf-8"))
        self.assertTrue(saved["pushed"])
        self.assertFalse(saved["published"])
        before = [path.read_bytes() for path in publisher.assets()]
        retry = self.publisher("--resume", "v0.7.8")
        self.invoke(retry)
        self.assertEqual(retry.state["source_commit"], saved["source_commit"])
        self.assertEqual([path.read_bytes() for path in retry.assets()], before)
        self.assertTrue(retry.state["published"])
        self.assertFalse(any(Path(command[0]).name in ("gradlew", "gradlew.bat")
                             or command[:2] in (["git", "commit"], ["git", "push"])
                             for command in retry.commands))
        completed = self.publisher("--resume", "v0.7.8")
        self.invoke(completed)
        self.assertEqual(completed.commands, [])

    def test_resume_push_failure_reuses_existing_tag_and_commit(self):
        publisher = self.publisher("--change", "说明", failure="push")
        with self.assertRaisesRegex(release.ReleaseError, "推送失败"):
            self.invoke(publisher)
        self.assertFalse(publisher.state["pushed"])
        self.assert_not_uploaded(publisher)
        retry = self.publisher("--resume", "v0.7.8")
        self.invoke(retry)
        self.assertEqual(retry.state["source_commit"], publisher.state["source_commit"])
        self.assertFalse(any(command[:2] == ["git", "tag"] for command in retry.commands))

    def test_different_existing_tag_is_not_overwritten(self):
        publisher = self.publisher("--change", "说明", failure="push")
        with self.assertRaises(release.ReleaseError):
            self.invoke(publisher)
        self.git("tag", "-f", "v0.7.8", self.baseline)
        retry = self.publisher("--resume", "v0.7.8")
        with self.assertRaisesRegex(release.ReleaseError, "拒绝覆盖"):
            self.invoke(retry)
        self.assert_not_uploaded(retry)

    def test_existing_tag_and_invalid_versions_are_rejected_before_changes(self):
        self.git("tag", "v0.7.7")
        for version in ("v0.7.8", "0.7.08", "0.7.6", "0.7.7", "1.0.0"):
            with self.subTest(version=version):
                publisher = self.publisher(version, "--change", "说明")
                with self.assertRaises(release.ReleaseError):
                    self.invoke(publisher)
                self.assertFalse(self.git("status", "--porcelain"))
                self.assert_not_uploaded(publisher)

    def test_stable_requires_explicit_flag_and_notes_file_is_preserved(self):
        notes = self.base / "notes with spaces.md"
        text = '正式版说明，保留 `$(command)`、反引号与换行。\n\n- 改进一\n- 改进二\n'
        notes.write_text(text, encoding="utf-8")
        publisher = self.publisher("1.0.0", "--stable", "--notes-file", str(notes))
        self.invoke(publisher)
        self.assertFalse(publisher.state["prerelease"])
        self.assertNotIn("--prerelease", publisher.commands[-1])
        self.assertEqual((self.root / "docs/releases/v1.0.0.md").read_text(encoding="utf-8"), text)

    def test_local_state_write_failure_does_not_report_successful_upload_as_failed(self):
        publisher = self.publisher("--change", "说明")
        save = publisher.save_state

        def fail_only_after_upload():
            if publisher.state["published"]:
                raise OSError("磁盘已满")
            save()

        publisher.save_state = fail_only_after_upload
        with contextlib.redirect_stderr(io.StringIO()) as output:
            self.invoke(publisher)
        self.assertTrue(publisher.state["published"])
        self.assertIn("上传已成功", output.getvalue())
        self.assertEqual(publisher.commands[-1][:3], ["gh", "release", "create"])

    def test_repository_url_parsing(self):
        for url in ("https://github.com/bestkojima/suishou-ocr-android.git",
                    "git@github.com:bestkojima/suishou-ocr-android.git",
                    "ssh://git@github.com/bestkojima/suishou-ocr-android.git",
                    "https://github.com/bestkojima/suishou-ocr-android"):
            self.assertEqual(release.github_repository(url), "bestkojima/suishou-ocr-android")


if __name__ == "__main__":
    unittest.main()
