import { execFileSync } from 'node:child_process';

const output = execFileSync('git', ['diff', '--name-only', 'origin/main'], {
  encoding: 'utf8',
});

const hasChangeset = output
  .split('\n')
  .some((file) => file.startsWith('.changeset/') && file.endsWith('.md') && file !== '.changeset/README.md');

if (!hasChangeset) {
  console.error('This PR needs a changeset.');
  console.error('Run: npx changeset add');
  console.error('If no release is needed, run: npx changeset add --empty');
  process.exit(1);
}
