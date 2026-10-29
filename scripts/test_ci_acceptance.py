#!/usr/bin/env python3
"""Deterministic selection, execution-gate and workflow-wiring regressions."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import ci_acceptance as ci


ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
CORE_JOBS = {
    "repository-checks", "script-tests", "windows-script-tests", "java-linux", "java17-web",
    "java-windows", "desktop-smoke", "analysis-provenance-ui", "engine-process",
}
WORKFLOW_FILES = {
    "fox-search": "fox-search.yml",
    "b11-speed-notice": "b11-speed-notice.yml",
    "native-windows": "move-focus-acceptance.yml",
    "sgf-cpu-acceptance": "acceptance-integration.yml",
}


def results(required="true", result="success"):
    return {
        "repository-checks": {"result": "success", "outputs": {"acceptance_required": required}},
        **{job: {"result": result} for job in ci.ACCEPTANCE_JOBS},
    }


class SelectionTest(unittest.TestCase):
    def test_only_conventional_prose_is_exempt(self):
        self.assertTrue(ci.documentation_only(["README.md", "docs/deep/guide.md", "docs/guide.rst"]))
        for path in (
            "pom.xml", "src/main/resources/katago-assets.json", "src/test/resources/input.sgf",
            "src/main/java/example/Worker.java", "src/test/java/example/WorkerTest.java",
            "src/main/resources/README.md", ".github/workflows/ci.yml",
            ".github/rulesets/main-ci.json", "scripts/ci_acceptance.py",
            "scripts/new-build-helper.py", "docs/generator.py", "docs/KG_MODEL_VERIFICATION.json",
            "packaging/launcher.cfg", "theme/default/theme.txt", "unknown/new-contract",
        ):
            with self.subTest(path=path):
                self.assertFalse(ci.documentation_only(["README.md", path]))

    def test_no_changed_files_need_no_supplemental_execution(self):
        self.assertTrue(ci.documentation_only([]))

    def test_main_and_merge_queue_run_all_acceptance(self):
        with mock.patch.object(ci, "changed_paths", side_effect=AssertionError("must not diff")):
            for event in ("push", "merge_group"):
                self.assertTrue(ci.acceptance_required(event, {}, ROOT))

    def test_unsupported_event_fails_instead_of_skipping(self):
        with self.assertRaises(ValueError):
            ci.acceptance_required("workflow_dispatch", {}, ROOT)

    def test_pull_request_selection_uses_the_complete_diff(self):
        with mock.patch.object(ci, "changed_paths", return_value=["docs/guide.md"]):
            self.assertFalse(ci.acceptance_required("pull_request", {}, ROOT))
        with mock.patch.object(ci, "changed_paths", return_value=["pom.xml"]):
            self.assertTrue(ci.acceptance_required("pull_request", {}, ROOT))

    def test_missing_or_injected_shas_are_rejected_before_git(self):
        events = [{}, {"pull_request": None}]
        for value in (None, [], "--output=foo", "a" * 39, "a" * 40 + ";echo bad"):
            events.append({"pull_request": {"base": {"sha": value}, "head": {"sha": "b" * 40}}})
        with mock.patch.object(ci.subprocess, "run") as run:
            for event in events:
                with self.subTest(event=event), self.assertRaises(ValueError):
                    ci.changed_paths(event, ROOT)
            run.assert_not_called()

    def test_git_failure_never_becomes_documentation_only(self):
        event = {"pull_request": {"base": {"sha": "a" * 40}, "head": {"sha": "b" * 40}}}
        with mock.patch.object(ci.subprocess, "run", side_effect=subprocess.CalledProcessError(128, "git")):
            with self.assertRaises(subprocess.CalledProcessError):
                ci.acceptance_required("pull_request", event, ROOT)

    def test_nul_paths_preserve_whitespace_and_newlines(self):
        event = {"pull_request": {"base": {"sha": "a" * 40}, "head": {"sha": "b" * 40}}}
        output = b"docs/a b.md\0src/main/java/line\nbreak.java\0"
        with mock.patch.object(ci.subprocess, "run", return_value=mock.Mock(stdout=output)) as run:
            self.assertEqual(ci.changed_paths(event, ROOT), ["docs/a b.md", "src/main/java/line\nbreak.java"])
            self.assertIn("--no-renames", run.call_args.args[0])
            self.assertIn("-z", run.call_args.args[0])
            self.assertTrue(run.call_args.kwargs["check"])


class ExecutionGateTest(unittest.TestCase):
    def test_selected_lanes_must_all_pass(self):
        ci.require_acceptance(results())

    def test_selected_failures_cancellations_skips_missing_and_unknown_fail(self):
        for job in ci.ACCEPTANCE_JOBS:
            for status in ("failure", "cancelled", "skipped", "neutral", "", None, "in_progress"):
                with self.subTest(job=job, status=status):
                    value = results()
                    value[job]["result"] = status
                    with self.assertRaises(ValueError):
                        ci.require_acceptance(value)
            value = results()
            del value[job]
            with self.subTest(job=job, status="absent"), self.assertRaises(ValueError):
                ci.require_acceptance(value)

    def test_explicit_documentation_plan_allows_skip_or_extra_success(self):
        ci.require_acceptance(results("false", "skipped"))
        ci.require_acceptance(results("false", "success"))

    def test_unselected_failure_is_not_hidden(self):
        for status in ("failure", "cancelled", "neutral", "", None):
            value = results("false", "skipped")
            value[ci.ACCEPTANCE_JOBS[0]]["result"] = status
            with self.subTest(status=status), self.assertRaises(ValueError):
                ci.require_acceptance(value)

    def test_failed_or_missing_plan_fails_closed(self):
        for status in ("failure", "cancelled", "skipped", "", None):
            value = results("false", "skipped")
            value["repository-checks"]["result"] = status
            with self.subTest(status=status), self.assertRaises(ValueError):
                ci.require_acceptance(value)
        for value in ({}, {"repository-checks": None}, {"repository-checks": {"result": "success"}}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                ci.require_acceptance(value)

    def test_invalid_output_does_not_authorize_skip(self):
        for flag in ("", "False", "True", False, True, 0, 1, None):
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                ci.require_acceptance(results(flag, "skipped"))

    def test_missing_required_flag_fails(self):
        value = results("false", "skipped")
        value["repository-checks"]["outputs"] = {}
        with self.assertRaises(ValueError):
            ci.require_acceptance(value)


class GitDiffTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "-q", "--initial-branch=main")
        self.git("config", "user.name", "CI Policy Test")
        self.git("config", "user.email", "ci-policy@example.invalid")
        self.git("config", "core.autocrlf", "false")
        self.write("src/main/java/Old.java", "class Old {}\n")
        self.write("README.md", "base\n")
        self.base = self.commit()

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE).stdout.decode().strip()

    def write(self, name, text):
        target = self.repo / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def commit(self):
        self.git("add", "--all")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def event(self, head, base=None):
        return {"pull_request": {"base": {"sha": base or self.base}, "head": {"sha": head}}}

    def test_documentation_commit_skips(self):
        self.write("docs/한국어 문서.md", "documentation\n")
        self.assertFalse(ci.acceptance_required("pull_request", self.event(self.commit()), self.repo))

    def test_deleted_code_requires_acceptance(self):
        (self.repo / "src/main/java/Old.java").unlink()
        self.assertTrue(ci.acceptance_required("pull_request", self.event(self.commit()), self.repo))

    def test_rename_from_code_into_documentation_cannot_skip(self):
        (self.repo / "docs").mkdir()
        self.git("mv", "src/main/java/Old.java", "docs/Old.md")
        event = self.event(self.commit())
        self.assertEqual(set(ci.changed_paths(event, self.repo)), {"src/main/java/Old.java", "docs/Old.md"})
        self.assertTrue(ci.acceptance_required("pull_request", event, self.repo))

    def test_code_after_more_than_300_documentation_paths_is_not_truncated(self):
        for index in range(350):
            self.write(f"docs/{index:03}.md", "docs\n")
        self.write("src/main/resources/katago-assets.json", "{}\n")
        event = self.event(self.commit())
        self.assertEqual(len(ci.changed_paths(event, self.repo)), 351)
        self.assertTrue(ci.acceptance_required("pull_request", event, self.repo))

    def test_three_dot_excludes_unrelated_base_only_changes(self):
        self.git("checkout", "-qb", "topic")
        self.write("docs/new.md", "docs\n")
        head = self.commit()
        self.git("checkout", "-q", "main")
        self.write("src/main/java/BaseOnly.java", "class BaseOnly {}\n")
        base = self.commit()
        self.assertEqual(ci.changed_paths(self.event(head, base), self.repo), ["docs/new.md"])

    def test_missing_commit_raises_instead_of_creating_a_skip_plan(self):
        with self.assertRaises(subprocess.CalledProcessError):
            ci.acceptance_required("pull_request", self.event("f" * 40), self.repo)


class CliTest(unittest.TestCase):
    def run_cli(self, command, **values):
        environment = dict(os.environ, **values)
        return subprocess.run([sys.executable, str(ROOT / "scripts/ci_acceptance.py"), command],
                              cwd=ROOT, env=environment, capture_output=True, text=True)

    def test_valid_and_invalid_gate_exit_codes(self):
        self.assertEqual(self.run_cli("check", NEEDS_JSON=json.dumps(results())).returncode, 0)
        for payload in (results("true", "skipped"), {}, [], None):
            with self.subTest(payload=payload):
                self.assertNotEqual(self.run_cli("check", NEEDS_JSON=json.dumps(payload)).returncode, 0)
        self.assertNotEqual(self.run_cli("check", NEEDS_JSON="not json").returncode, 0)

    def test_plan_writes_one_boolean_output(self):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            output = Path(directory) / "output"
            event.write_text("{}", encoding="utf-8")
            result = self.run_cli("plan", GITHUB_EVENT_NAME="push", GITHUB_EVENT_PATH=str(event),
                                  GITHUB_OUTPUT=str(output))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(output.read_text(), "acceptance_required=true\n")

    def test_failed_plan_does_not_emit_false(self):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            output = Path(directory) / "output"
            event.write_text("{}", encoding="utf-8")
            result = self.run_cli("plan", GITHUB_EVENT_NAME="pull_request", GITHUB_EVENT_PATH=str(event),
                                  GITHUB_OUTPUT=str(output))
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(output.exists())


class WorkflowContractTest(unittest.TestCase):
    def test_aggregate_is_always_scheduled_and_depends_on_every_job(self):
        text = (WORKFLOWS / "ci.yml").read_text()
        jobs = set(re.findall(r"^  ([a-z0-9-]+):$", text.split("\njobs:\n", 1)[1], re.M))
        gate = text.split("\n  ci-required:\n", 1)[1]
        self.assertIn("if: ${{ always() }}", gate)
        dependencies = set(re.search(r"needs: \[([^\]]+)\]", gate).group(1).split(", "))
        self.assertEqual(dependencies, jobs - {"ci-required"})
        self.assertTrue(CORE_JOBS <= dependencies)
        self.assertTrue(set(ci.ACCEPTANCE_JOBS) <= dependencies)
        self.assertIn("from scripts.run_local_ci import require_successful_job_results", gate)
        self.assertIn("NEEDS_JSON: ${{ toJSON(needs) }}", gate)
        self.assertIn("python3 scripts/ci_acceptance.py check", gate)

    def test_top_level_ci_is_unfiltered_and_covers_merge_queue(self):
        text = (WORKFLOWS / "ci.yml").read_text()
        triggers = text.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertIn("  pull_request:\n", triggers)
        self.assertIn("  merge_group:\n", triggers)
        self.assertNotIn("paths:", triggers)
        self.assertNotIn("paths-ignore:", triggers)
        self.assertNotIn("pull_request_target", triggers)
        self.assertIn("fetch-depth: 0", text)
        self.assertEqual(text.count("-m unittest discover -s scripts -p test_ci_acceptance.py"), 2)

    def test_reusable_calls_match_the_gate_and_have_no_duplicate_pr_trigger(self):
        text = (WORKFLOWS / "ci.yml").read_text()
        for job, name in WORKFLOW_FILES.items():
            with self.subTest(job=job):
                call = text.split(f"\n  {job}:\n", 1)[1].split("\n\n", 1)[0]
                self.assertIn("needs: repository-checks", call)
                self.assertIn("if: ${{ needs.repository-checks.outputs.acceptance_required == 'true' }}", call)
                self.assertIn(f"uses: ./.github/workflows/{name}", call)
                self.assertNotIn("continue-on-error", call)
                child = (WORKFLOWS / name).read_text()
                triggers = child.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
                self.assertEqual(triggers.strip(), "workflow_call:\n  workflow_dispatch:")
                self.assertIn("${{ github.event_name }}-${{ github.ref }}", child)
                self.assertIn("contents: read", child)
                self.assertNotIn("secrets: inherit", text)
                self.assertNotIn("continue-on-error", child)

    def test_ruleset_requires_the_existing_app_owned_check_on_latest_main(self):
        ruleset = json.loads((ROOT / ".github/rulesets/main-ci.json").read_text())
        self.assertEqual(ruleset["target"], "branch")
        self.assertEqual(ruleset["enforcement"], "active")
        self.assertEqual(ruleset["bypass_actors"], [])
        self.assertEqual(ruleset["conditions"]["ref_name"], {"include": ["refs/heads/main"], "exclude": []})
        self.assertEqual(len(ruleset["rules"]), 2)
        pr_rule, rule = ruleset["rules"]
        self.assertEqual(pr_rule["type"], "pull_request")
        self.assertEqual(pr_rule["parameters"]["required_approving_review_count"], 0)
        self.assertFalse(pr_rule["parameters"]["require_code_owner_review"])
        self.assertEqual(rule["type"], "required_status_checks")
        self.assertTrue(rule["parameters"]["strict_required_status_checks_policy"])
        self.assertFalse(rule["parameters"]["do_not_enforce_on_create"])
        self.assertEqual(rule["parameters"]["required_status_checks"],
                         [{"context": "ci-required", "integration_id": 15368}])


if __name__ == "__main__":
    unittest.main()
