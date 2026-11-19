"""Offline Git integration tests for the fork's synchronization workflow."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/sync-upstream.py'
spec = importlib.util.spec_from_file_location('sync_upstream', SCRIPT)
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class SynchronizationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q', '-b', 'upstream')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.write('eqwalizer/checker.scala', ''.join(f'line {i}\n' for i in range(20)))
        self.write('eqwalizer/remove.txt', 'removed upstream\n')
        self.write('eqwalizer/data.bin', b'\0old\xff')
        self.commit('baseline')
        self.old = self.git('rev-parse', 'HEAD').strip()
        self.tree = self.git('rev-parse', 'HEAD:eqwalizer').strip()
        self.write('eqwalizer/checker.scala', 'upstream change\n' + ''.join(f'line {i}\n' for i in range(1, 20)))
        self.write('eqwalizer/new.scala', 'new upstream\n')
        self.write('eqwalizer/data.bin', b'\0new\xfe')
        (self.root / 'eqwalizer/remove.txt').unlink()
        self.write('crates/erlang_service/src/lib.rs', 'protocol change\n')
        self.write('docs/news.md', 'release\n')
        self.commit('candidate')
        self.new = self.git('rev-parse', 'HEAD').strip()
        self.git('checkout', '-q', '--orphan', 'integration', self.old)
        self.git('rm', '-q', '-rf', '.')
        self.write('checker.scala', ''.join(f'line {i}\n' for i in range(19)) + 'fork change\n')
        self.write('remove.txt', 'removed upstream\n')
        self.write('data.bin', b'\0old\xff')
        self.write('fork-only.scala', 'custom behavior\n')
        self.baseline = dict(schema_version=1, repository=str(self.root), commit=self.old,
                             subtree='eqwalizer', tree=self.tree, release='test',
                             tested_elp=dict(commit=self.old, release='test', otp='27'))
        self.write('upstream.json', json.dumps(self.baseline))
        self.write('scripts/sync-upstream.py', SCRIPT.read_bytes())
        self.commit('fork snapshot')

    def git(self, *args):
        return sync.git(self.root, *args).decode()

    def write(self, path, data):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data.encode() if isinstance(data, str) else data)

    def commit(self, message):
        self.git('add', '.')
        self.git('commit', '-qm', message)

    def cli(self, *args):
        return subprocess.run(['python3', str(self.root / 'scripts/sync-upstream.py'), *args],
                              capture_output=True, text=True)

    def test_report_overlap_and_outside_changes(self):
        report, patch = sync.review(self.root, self.baseline, self.new)
        self.assertEqual(report['overlapping_files'], ['checker.scala'])
        self.assertEqual(report['subtree_changes'], ['checker.scala', 'data.bin', 'new.scala', 'remove.txt'])
        self.assertEqual(report['integration_changes'], ['crates/erlang_service/src/lib.rs'])
        self.assertEqual(report['other_outside_changes'], ['docs/news.md'])
        self.assertIn(b'GIT binary patch', patch)

    def test_identical_subtree(self):
        report, patch = sync.review(self.root, self.baseline, self.old)
        self.assertTrue(report['subtree_unchanged'])
        self.assertEqual(patch, b'')

    def test_external_only_change_keeps_subtree_unchanged(self):
        self.git('checkout', '-q', '--detach', self.old)
        self.write('crates/erlang_service/src/lib.rs', 'parser only\n')
        self.commit('external integration change')
        candidate = self.git('rev-parse', 'HEAD').strip()
        self.git('checkout', '-q', 'integration')
        report, patch = sync.review(self.root, self.baseline, candidate)
        self.assertTrue(report['subtree_unchanged'])
        self.assertEqual(patch, b'')
        self.assertEqual(report['integration_changes'], ['crates/erlang_service/src/lib.rs'])

    def test_explicit_fetch_preserves_checkout_and_remote_configuration(self):
        before = self.git('rev-parse', 'HEAD')
        result = self.cli(self.new, '--fetch', '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['candidate_commit'], self.new)
        self.assertEqual(self.git('rev-parse', 'HEAD'), before)
        self.assertEqual(self.git('remote'), '')
        self.assertEqual(self.git('status', '--porcelain'), '')

    def test_three_way_application_preserves_fork_and_imports_binary_deletion(self):
        result = self.cli(self.new, '--apply')
        self.assertEqual(result.returncode, 0, result.stderr)
        checker = (self.root / 'checker.scala').read_text()
        self.assertTrue(checker.startswith('upstream change\n'))
        self.assertTrue(checker.endswith('fork change\n'))
        self.assertEqual((self.root / 'fork-only.scala').read_text(), 'custom behavior\n')
        self.assertFalse((self.root / 'remove.txt').exists())
        self.assertEqual((self.root / 'data.bin').read_bytes(), b'\0new\xfe')
        self.assertFalse((self.root / 'crates').exists())
        self.assertEqual(json.loads((self.root / 'upstream.json').read_text()), self.baseline)

    def test_conflicts_remain_available_for_review(self):
        self.write('checker.scala', 'conflicting fork\n' + ''.join(f'line {i}\n' for i in range(1, 20)))
        self.commit('conflicting fork')
        result = self.cli(self.new, '--apply')
        self.assertEqual(result.returncode, 1)
        self.assertIn('checker.scala', self.git('diff', '--name-only', '--diff-filter=U'))

    def test_apply_refuses_dirty_or_main_branch(self):
        self.write('untracked.txt', 'preserve me')
        self.assertEqual(self.cli(self.new, '--apply').returncode, 1)
        (self.root / 'untracked.txt').unlink()
        self.git('branch', '-m', 'main')
        self.assertEqual(self.cli(self.new, '--apply').returncode, 1)
        self.assertFalse((self.root / 'new.scala').exists())

    def test_bad_baseline_and_patch_overwrite_are_rejected(self):
        bad = dict(self.baseline, tree='0' * 40)
        with self.assertRaisesRegex(RuntimeError, 'tree does not match'):
            sync.review(self.root, bad, self.new)
        patch = self.root / 'saved.patch'
        patch.write_text('do not overwrite')
        self.assertEqual(self.cli(self.new, '--patch', str(patch)).returncode, 1)
        self.assertEqual(patch.read_text(), 'do not overwrite')


if __name__ == '__main__':
    unittest.main()
