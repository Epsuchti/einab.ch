// Production build that stamps the release number (server/deploy/version.txt) into the app.
// It is shown in the footer and sent with support requests, so admins know which release a user had.
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

let release = 'dev';
try {
  release = readFileSync(new URL('../../server/deploy/version.txt', import.meta.url), 'utf8').trim() || 'dev';
} catch {
  // No version file (e.g. a client-only checkout): keep "dev".
}

const ngCli = createRequire(import.meta.url).resolve('@angular/cli/bin/ng.js');
const result = spawnSync(process.execPath, [ngCli, 'build', '--define', `EINAB_RELEASE=${JSON.stringify(release)}`, ...process.argv.slice(2)], { stdio: 'inherit' });
process.exit(result.status ?? 1);
