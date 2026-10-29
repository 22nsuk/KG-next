#!/usr/bin/env python3
"""Regression coverage for scoped CI execution and the required-check contract."""

from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import ci_merge_gate as gate

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = {
    "fox-search": "fox-search.yml",
    "b11-speed-notice": "b11-speed-notice.yml",
    "native-focus": "move-focus-acceptance.yml",
    "acceptance-integration": "acceptance-integration.yml",
}
CORE_JOBS = {
    "repository-checks", "script-tests", "windows-script-tests", "java-linux",
    "java17-web", "java-windows", "desktop-smoke", "analysis-provenance-ui", "engine-process",
}


def needs_for(selected: dict[str, bool]) -> dict:
    needs = {job: {"result": "success", "outputs": {}} for job in CORE_JOBS}
    needs["change-scope"] = {
        "result": "success",
        "outputs": {job: str(required).lower() for job, required in selected.items()},
    }
    needs.update({job: {"result": "success" if required else "skipped", "outputs": {}}
                  for job, required in selected.items()})
    return needs


def job_blocks(text: str) -> dict[str, str]:
    jobs = text.split("\njobs:\n", 1)[1]
    chunks = re.split(r"^  ([a-z0-9-]+):\n", jobs, flags=re.MULTILINE)
    return dict(zip(chunks[1::2], chunks[2::2]))


class ScopeTest(unittest.TestCase):
    def test_documentation_only_does_not_run_supplemental_checks(self):
        for paths in ([], ["README.md", "docs/CI_MERGE_GATE.md", "docs/설명.md"]):
            with self.subTest(paths=paths):
                self.assertEqual(dict.fromkeys(WORKFLOWS, False), gate.required_supplemental_jobs(paths))

    def test_shared_gate_build_and_runner_changes_select_every_lane(self):
        for path in gate.COMMON_PATHS:
            with self.subTest(path=path):
                sample = path.replace("**", "sub/config")
                self.assertTrue(all(gate.required_supplemental_jobs([sample]).values()))

    def test_each_supplemental_workflow_selects_its_own_lane(self):
        for job, filename in WORKFLOWS.items():
            with self.subTest(job=job):
                selected = gate.required_supplemental_jobs([f".github/workflows/{filename}"])
                self.assertTrue(selected[job])

    def test_all_registered_globs_select_their_lane(self):
        for job, patterns in gate.SUPPLEMENTAL_PATHS.items():
            for pattern in patterns:
                with self.subTest(job=job, pattern=pattern):
                    sample = pattern.replace("**", "nested/path").replace("*", "Sample")
                    self.assertTrue(gate.required_supplemental_jobs([sample])[job])

    def test_catalog_requires_both_real_engine_acceptance_paths(self):
        selected = gate.required_supplemental_jobs(["src/main/resources/katago-assets.json"])
        self.assertTrue(selected["native-focus"])
        self.assertTrue(selected["acceptance-integration"])

    def test_fox_and_b11_keep_their_existing_ui_and_locale_triggers(self):
        cases = {
            "src/main/java/featurecat/lizzie/gui/LizzieFrame.java": ("fox-search", "b11-speed-notice", "native-focus"),
            "src/main/resources/l10n/DisplayStrings_ko.properties": ("fox-search", "b11-speed-notice"),
            "src/test/java/featurecat/lizzie/gui/FoxKifuDownloadAccessibilityTest.java": ("fox-search",),
            "src/test/java/featurecat/lizzie/gui/B11SpeedNoticeDesktopTest.java": ("b11-speed-notice",),
            "src/test/resources/featurecat/lizzie/gui/d3-snapshot.sgf": ("acceptance-integration",),
        }
        for path, required in cases.items():
            with self.subTest(path=path):
                selected = gate.required_supplemental_jobs([path])
                for job in required:
                    self.assertTrue(selected[job])

    def test_main_push_and_merge_queue_verify_the_complete_tree(self):
        with mock.patch.object(gate.subprocess, "check_output") as git:
            for event in ("push", "merge_group"):
                self.assertEqual(dict.fromkeys(WORKFLOWS, True), gate.plan_for_event(event))
            git.assert_not_called()

    def test_unknown_event_is_not_treated_as_unrelated(self):
        with self.assertRaises(ValueError):
            gate.plan_for_event("workflow_dispatch")

    def test_diff_failure_is_not_treated_as_no_changes(self):
        with mock.patch.object(gate.subprocess, "check_output", side_effect=[
            b"aaaa bbbb\n", subprocess.CalledProcessError(128, "git diff")
        ]):
            with self.assertRaises(subprocess.CalledProcessError):
                gate.plan_for_event("pull_request")

    def test_nul_paths_do_not_split_newlines_or_decode_git_quoting(self):
        path = "src/main/java/featurecat/lizzie/gui/한글\n설정.java"
        with mock.patch.object(gate.subprocess, "check_output", side_effect=[
            b"aaaa bbbb\n", path.encode() + b"\0README.md\0"
        ]) as git:
            self.assertTrue(gate.plan_for_event("pull_request")["fox-search"])
            self.assertIn("--no-renames", git.call_args.args[0])
            self.assertIn("-z", git.call_args.args[0])


class MergeDiffTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "CI gate fixture")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.autocrlf", "false")
        self.write("README.md", "base\n")
        self.commit()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.STDOUT)

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-qm", "fixture")

    def finish_merge(self):
        self.commit()
        self.git("checkout", "-q", "main")
        self.git("merge", "-q", "--no-ff", "--no-edit", "topic")
        return gate.plan_for_event("pull_request", self.root)

    def test_late_file_beyond_api_diff_limit_is_still_selected(self):
        self.git("checkout", "-qb", "topic")
        for index in range(350):
            self.write(f"docs/{index:04}.md", "documentation\n")
        self.write("src/main/resources/katago-assets.json", "{}\n")
        selected = self.finish_merge()
        self.assertTrue(selected["native-focus"])
        self.assertTrue(selected["acceptance-integration"])

    def test_rename_out_of_a_scope_includes_its_old_path(self):
        old = "src/main/java/featurecat/lizzie/analysis/Leelaz.java"
        self.write(old, "fixture\n")
        self.commit()
        self.git("checkout", "-qb", "topic")
        (self.root / old).rename(self.root / "archived.txt")
        selected = self.finish_merge()
        self.assertTrue(selected["b11-speed-notice"])
        self.assertTrue(selected["native-focus"])

    def test_deleted_catalog_still_selects_acceptance(self):
        self.write("src/main/resources/katago-assets.json", "{}\n")
        self.commit()
        self.git("checkout", "-qb", "topic")
        (self.root / "src/main/resources/katago-assets.json").unlink()
        self.assertTrue(self.finish_merge()["acceptance-integration"])

    def test_scope_uses_the_tested_merges_first_parent_not_old_branch_history(self):
        self.git("checkout", "-qb", "topic")
        self.write("docs/notes.md", "PR documentation\n")
        self.commit()
        self.git("checkout", "-q", "main")
        self.write("src/main/resources/katago-assets.json", "{}\n")
        self.commit()
        self.git("merge", "-q", "--no-ff", "--no-edit", "topic")
        self.assertFalse(any(gate.plan_for_event("pull_request", self.root).values()))

    def test_head_checkout_without_merge_parents_cannot_claim_no_changes(self):
        with self.assertRaisesRegex(ValueError, "two-parent merge"):
            gate.plan_for_event("pull_request", self.root)


class ResultTest(unittest.TestCase):
    def setUp(self):
        self.selected = dict.fromkeys(WORKFLOWS, True)
        self.needs = needs_for(self.selected)

    def test_selected_success_is_accepted(self):
        messages = gate.verify_supplemental_results(self.needs)
        self.assertEqual(4, len(messages))
        self.assertTrue(all("executed successfully" in message for message in messages))

    def test_documentation_skip_is_explicitly_not_execution(self):
        messages = gate.verify_supplemental_results(needs_for(dict.fromkeys(WORKFLOWS, False)))
        self.assertTrue(all("not applicable (not executed)" in message for message in messages))

    def test_mixed_applicability_requires_only_selected_execution(self):
        selected = gate.required_supplemental_jobs(["src/main/resources/katago-assets.json"])
        self.assertEqual(4, len(gate.verify_supplemental_results(needs_for(selected))))

    def test_each_selected_lane_rejects_every_non_success_state(self):
        for job in WORKFLOWS:
            for result in ("failure", "cancelled", "skipped", "neutral", "timed_out", "", None):
                with self.subTest(job=job, result=result):
                    needs = copy.deepcopy(self.needs)
                    needs[job]["result"] = result
                    with self.assertRaisesRegex(ValueError, job):
                        gate.verify_supplemental_results(needs)

    def test_missing_selected_lane_is_not_success(self):
        for job in WORKFLOWS:
            with self.subTest(job=job):
                needs = copy.deepcopy(self.needs)
                del needs[job]
                with self.assertRaisesRegex(ValueError, job):
                    gate.verify_supplemental_results(needs)

    def test_failure_of_a_not_applicable_lane_is_not_silently_ignored(self):
        needs = needs_for(dict.fromkeys(WORKFLOWS, False))
        needs["fox-search"]["result"] = "failure"
        with self.assertRaises(ValueError):
            gate.verify_supplemental_results(needs)

    def test_failed_skipped_cancelled_or_missing_scope_blocks(self):
        for result in ("failure", "skipped", "cancelled", None):
            with self.subTest(result=result):
                needs = copy.deepcopy(self.needs)
                needs["change-scope"]["result"] = result
                with self.assertRaisesRegex(ValueError, "applicability is unknown"):
                    gate.verify_supplemental_results(needs)
        del self.needs["change-scope"]
        with self.assertRaises(ValueError):
            gate.verify_supplemental_results(self.needs)

    def test_missing_or_unrecognized_scope_output_blocks(self):
        for invalid in (None, {}, {"native-focus": "false"}, {**self.needs["change-scope"]["outputs"], "extra": "false"}):
            with self.subTest(invalid=invalid):
                needs = copy.deepcopy(self.needs)
                needs["change-scope"]["outputs"] = invalid
                with self.assertRaises(ValueError):
                    gate.verify_supplemental_results(needs)

    def test_only_literal_true_or_false_are_accepted(self):
        for invalid in (True, False, "", "TRUE", "0", "null", None):
            with self.subTest(invalid=invalid):
                needs = copy.deepcopy(self.needs)
                needs["change-scope"]["outputs"]["native-focus"] = invalid
                with self.assertRaises(ValueError):
                    gate.verify_supplemental_results(needs)

    def test_malformed_needs_is_rejected(self):
        for needs in (None, [], "success", {}):
            with self.subTest(needs=needs):
                with self.assertRaises(ValueError):
                    gate.verify_supplemental_results(needs)

    def test_cli_exits_nonzero_for_failure_and_invalid_json(self):
        env = os.environ.copy()
        for payload, expected in ((json.dumps(self.needs), 0), ("{broken", 1)):
            env["NEEDS_JSON"] = payload
            completed = subprocess.run([sys.executable, str(ROOT / "scripts/ci_merge_gate.py"), "check"],
                                       env=env, capture_output=True, text=True)
            self.assertEqual(expected, completed.returncode, completed.stderr)
        self.needs["native-focus"]["result"] = "failure"
        env["NEEDS_JSON"] = json.dumps(self.needs)
        completed = subprocess.run([sys.executable, str(ROOT / "scripts/ci_merge_gate.py"), "check"],
                                   env=env, capture_output=True, text=True)
        self.assertEqual(1, completed.returncode)
        self.assertIn("native-focus", completed.stderr)

    def test_cli_plan_writes_all_outputs_only_after_a_valid_plan(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            command = [sys.executable, str(ROOT / "scripts/ci_merge_gate.py"), "plan",
                       "--event", "unknown", "--output", str(output)]
            failed = subprocess.run(command, cwd=directory, capture_output=True, text=True)
            self.assertEqual(1, failed.returncode)
            self.assertFalse(output.exists())
            command[4] = "push"
            completed = subprocess.run(command, cwd=directory, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual({f"{job}=true" for job in WORKFLOWS}, set(output.read_text().splitlines()))


class WorkflowContractTest(unittest.TestCase):
    def setUp(self):
        self.text = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.jobs = job_blocks(self.text)

    def test_ci_always_starts_for_pr_and_merge_queue_without_path_filter(self):
        events = self.text.split("\non:\n", 1)[1].split("\npermissions:\n", 1)[0]
        self.assertIn("  pull_request:\n", events)
        self.assertIn("  merge_group:\n", events)
        self.assertNotIn("paths:", events)
        self.assertNotIn("paths-ignore:", events)

    def test_required_job_waits_for_every_core_scope_and_called_job(self):
        required = self.jobs["ci-required"]
        self.assertIn("if: ${{ always() }}", required)
        names = set(re.search(r"needs: \[([^\]]+)\]", required).group(1).replace(" ", "").split(","))
        self.assertEqual(set(self.jobs) - {"ci-required"}, names)
        self.assertEqual(CORE_JOBS | {"change-scope"} | set(WORKFLOWS), names)
        for job in CORE_JOBS:
            self.assertIn("${{ needs." + job + ".result }}", required)
        self.assertIn("require_successful_job_results(", required)
        self.assertIn("NEEDS_JSON: ${{ toJSON(needs) }}", required)
        self.assertIn("run: python3 scripts/ci_merge_gate.py check", required)
        self.assertNotIn("continue-on-error:", required)

    def test_scopes_gate_same_revision_reusable_workflows(self):
        for job, filename in WORKFLOWS.items():
            with self.subTest(job=job):
                self.assertIn(job, self.jobs)
                block = self.jobs[job]
                self.assertIn("needs: change-scope", block)
                self.assertIn("if: ${{ needs.change-scope.outputs." + job + " == 'true' }}", block)
                self.assertIn(f"uses: ./.github/workflows/{filename}", block)
                self.assertNotIn("secrets:", block)
                self.assertNotIn("continue-on-error:", block)

    def test_scope_checkout_uses_default_merge_ref_with_both_parents(self):
        self.assertIn("change-scope", self.jobs)
        scope = self.jobs["change-scope"]
        self.assertIn("fetch-depth: 2", scope)
        self.assertIn("persist-credentials: false", scope)
        self.assertNotIn("ref:", scope)
        for job in WORKFLOWS:
            self.assertIn(f"{job}: ${{{{ steps.scope.outputs.{job} }}}}", scope)
        self.assertIn('plan --event "$CI_EVENT" --output "$GITHUB_OUTPUT"', scope)

    def test_callees_do_not_also_start_independent_pr_runs(self):
        for filename in WORKFLOWS.values():
            with self.subTest(filename=filename):
                text = (ROOT / ".github/workflows" / filename).read_text(encoding="utf-8")
                events = text.split("\non:\n", 1)[1].split("\npermissions:\n", 1)[0]
                self.assertIn("  workflow_call:\n", events)
                self.assertIn("  workflow_dispatch:\n", events)
                self.assertNotIn("pull_request:", events)
                self.assertNotIn("workflow_run:", events)
                self.assertIn("${{ github.workflow }}-${{ github.ref }}", text)
                self.assertNotIn("continue-on-error:", text)

    def test_active_main_only_ruleset_requires_expected_app_and_current_base(self):
        data = json.loads((ROOT / ".github/rulesets/main-ci.json").read_text(encoding="utf-8"))
        self.assertEqual("branch", data["target"])
        self.assertEqual("active", data["enforcement"])
        self.assertEqual([], data["bypass_actors"])
        self.assertEqual({"ref_name": {"include": ["refs/heads/main"], "exclude": []}}, data["conditions"])
        rules = {rule["type"]: rule["parameters"] for rule in data["rules"]}
        self.assertEqual({"required_status_checks", "pull_request"}, set(rules))
        self.assertEqual(0, rules["pull_request"]["required_approving_review_count"])
        status = rules["required_status_checks"]
        self.assertIs(status["strict_required_status_checks_policy"], True)
        self.assertIs(status["do_not_enforce_on_create"], False)
        self.assertEqual([{"context": "ci-required", "integration_id": 15368}], status["required_status_checks"])


if __name__ == "__main__":
    unittest.main()
