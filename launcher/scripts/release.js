// Usage: node scripts/release.js 0.2.0
//    or: npm run release 0.2.0

const { execSync } = require('child_process');
const fs   = require('fs');
const path = require('path');

const version = process.argv[2];
if (!version || !/^\d+\.\d+\.\d+$/.test(version)) {
  console.error('Usage: npm run release <version>   e.g. npm run release 0.2.0');
  process.exit(1);
}

const pkgPath = path.join(__dirname, '..', 'package.json');
const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
const old = pkg.version;
pkg.version = version;
fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n');
console.log(`Bumped version ${old} → ${version}`);

const run = (cmd) => { console.log(`> ${cmd}`); execSync(cmd, { stdio: 'inherit' }); };

run('git add -A');
run(`git commit -m "v${version}"`);
run('git push');
run(`git tag v${version}`);
run(`git push origin v${version}`);

console.log(`\nDone! GitHub Actions is now building the release.`);
console.log(`Watch it at: https://github.com/sagewilliamjunk-png/Fox-Client/actions`);
