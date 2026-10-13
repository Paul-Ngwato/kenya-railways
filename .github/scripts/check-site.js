// Static integrity checks for the Kenya Railways site. No dependencies.
// Usage: node .github/scripts/check-site.js
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..', 'railway');
const errors = [];

const read = f => fs.readFileSync(path.join(root, f), 'utf8');

// 1. Shipped files exist
for (const f of ['index.html', 'style.css', 'serve.js']) {
  if (!fs.existsSync(path.join(root, f))) errors.push('missing file: ' + f);
}
if (errors.length) fail();

const html = read('index.html');
const css = read('style.css');

// 2. Every in-page link resolves to an id in the document
const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(m => m[1]));
const anchors = [...html.matchAll(/\bhref="#([^"]+)"/g)].map(m => m[1]);
for (const a of anchors) {
  if (!ids.has(a)) errors.push('broken in-page link: #' + a);
}
if (anchors.length === 0) errors.push('no in-page navigation links found');

// 3. Referenced local assets exist on disk
for (const [, ref] of html.matchAll(/\b(?:src|href)="([^"#][^"]*)"/g)) {
  if (/^(https?:|mailto:|tel:|data:|\/\/)/.test(ref)) continue;
  const file = ref.split('?')[0].split('#')[0];
  if (!fs.existsSync(path.join(root, file))) errors.push('missing asset: ' + ref);
}

// 4. Document basics: viewport, title, table semantics
if (!/<meta[^>]+name="viewport"/.test(html)) errors.push('missing viewport meta tag');
if (!/<title>[^<]+<\/title>/.test(html)) errors.push('missing <title>');
if (!/<caption>/.test(html)) errors.push('schedule table missing <caption>');
if (!/<th[^>]+scope="col"/.test(html)) errors.push('table headers missing scope="col"');
for (const [, label] of html.matchAll(/<td>(?!<)/g)) { void label; }
const cellCount = (html.match(/<td /g) || []).length;
const labelCount = (html.match(/data-label="/g) || []).length;
if (cellCount === 0 || cellCount !== labelCount) {
  errors.push(`responsive labels missing: ${labelCount}/${cellCount} cells have data-label`);
}

// 5. Mobile-first CSS actually enhances at the documented breakpoints
for (const bp of ['600px', '900px']) {
  if (!css.includes(`min-width: ${bp}`)) errors.push('missing media query for ' + bp);
}
if (!/display:\s*grid/.test(css)) errors.push('no CSS Grid usage found');
if (!/display:\s*flex/.test(css)) errors.push('no Flexbox usage found');

// 6. No obvious secrets in shipped files
for (const f of ['index.html', 'style.css', 'serve.js', 'cdp-shot.js']) {
  const text = read(f);
  if (/sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}/.test(text)) {
    errors.push('possible secret committed in ' + f);
  }
}

if (errors.length) fail();

console.log(
  `Site checks passed: ${ids.size} ids, ${anchors.length} in-page links, ` +
  `${cellCount} labelled table cells, breakpoints 600px/900px present.`
);

function fail() {
  console.error('Site checks FAILED:\n - ' + errors.join('\n - '));
  process.exit(1);
}
