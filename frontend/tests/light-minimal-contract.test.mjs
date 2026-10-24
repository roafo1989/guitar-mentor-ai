import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const file = relative => readFile(new URL(relative, import.meta.url), 'utf8');

test('#148 theme is opt-in and scoped to the shell', async () => {
  const [app, tokens, css, entry] = await Promise.all([
    file('../src/App.vue'), file('../src/assets/design-tokens.css'),
    file('../src/assets/light-minimal.css'), file('../src/main.ts'),
  ]);
  assert.match(app, /import\.meta\.env\?\.VITE_GUITAR_MENTOR_LIGHT_MINIMAL === 'true'/);
  assert.match(app, /:data-ui="lightMinimal \? 'light-minimal' : undefined"/);
  assert.match(tokens, /\.app-shell\[data-ui="light-minimal"\]/);
  assert.match(css, /\.app-shell\[data-ui="light-minimal"\]/);
  assert.ok(!css.includes('.app-shell.light-minimal'), 'old unscoped experimental selector must not be used');
  assert.ok(entry.indexOf('design-tokens.css') < entry.indexOf('main.css'), 'tokens load before component CSS');
});

test('#148 navigation discloses real routes and remains keyboard-operable', async () => {
  const [app, router] = await Promise.all([file('../src/App.vue'), file('../src/router/index.ts')]);
  assert.match(app, /<RouterLink to="\/" class="brand"/, 'brand remains Home link');
  assert.doesNotMatch(app, /to="\/" @click="closeMenu\(\)"/, 'Home is not duplicated in header navigation');
  for (const path of ['/profile', '/onboarding', '/diagnostics']) {
    assert.ok(router.includes("path: '" + path + "'"), 'real router path: ' + path);
    assert.ok(app.includes('to="' + path + '"'), 'navigable header route: ' + path);
  }
  assert.doesNotMatch(app, /to="\/library"/);
  assert.match(app, /:aria-expanded="menuOpen"/);
  assert.match(app, /:aria-controls="menuId"/);
  assert.match(app, /event\.key (?:===|!==) 'Escape'/);
  assert.match(app, /closeMenu\(true\)/);
  assert.match(app, /@click="closeMenu\(\)"/);
  assert.match(app, /stopMetronomeForAudioCapture\(\)/);
  assert.match(app, /await logout\(/);
});
