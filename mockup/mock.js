/* Orchard clickable mockup. All numbers are read from tokens.css (never duplicated here). */
(() => {
'use strict';
const Q = new URLSearchParams(location.search);
const root = document.documentElement;
const $ = (id) => document.getElementById(id);
const tk = (n) => parseFloat(getComputedStyle(root).getPropertyValue('--' + n));
const mk = (tag, cls, parent, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; if (parent) parent.appendChild(e); return e; };
const SVGNS = 'http://www.w3.org/2000/svg';
const svg = (inner, vb) => { const s = document.createElementNS(SVGNS, 'svg'); s.setAttribute('viewBox', vb || '0 0 48 48'); s.innerHTML = inner; return s; };

/* ---------- catalogue (names real; art = real banners pulled from the Stick, or placeholders) ----------
   color = vivid colour of the PLACEHOLDER banner (banners/colors.json); vivid = of the REAL banner (banners-real/manifest.json).
   real = a real banner file exists in banners-real/ (git-ignored, local only). banner = a placeholder banner exists. */
const APPS = [
  { id: 'netflix',   name: 'Netflix',     color: '#b0070f', vivid: '#e71c25', banner: true,  real: true,  white: true,  sub: 'Opened 2 h ago', dock: 1 },
  { id: 'jiohotstar',name: 'JioHotstar',  color: '#1b2a6b', vivid: '#0a8bfc', banner: true,  real: true,  sub: 'Opened yesterday', dock: 2 },
  { id: 'youtube',   name: 'YouTube',     color: '#d81f26', vivid: '#ff0b0b', banner: true,  real: true,  white: true,  sub: 'Opened 5 h ago', dock: 3 },
  { id: 'appletv',   name: 'Apple TV',    color: '#151516', vivid: '#987beb', banner: true,  real: true,  dark: true, sub: 'Opened 3 days ago', dock: 4 },
  { id: 'spotify',   name: 'Spotify',     color: '#1db954', vivid: '#1dc057', banner: true,  real: true,  dark: true, sub: 'Opened 1 h ago', dock: 5 },
  { id: 'drivecast', name: 'Drivecast',   color: '#2f6fed', vivid: '#387ceb', banner: true,  real: true,  sub: 'Opened this morning', dock: 6 },
  { id: 'sonyliv',   name: 'SonyLIV',     color: '#14161c', vivid: '#d6980f', banner: true,  real: true,  sub: 'Never opened' },
  { id: 'jiotv',     name: 'JioTV+',      color: '#c2185b', vivid: '#a91113', banner: true,  real: true,  sub: 'Opened last week' },
  { id: 'zee5',      name: 'ZEE5',        color: '#5b1fa8', vivid: '#6e7390', banner: true,  real: true,  dark: true, sub: 'Never opened' },   // manifest says #888888 (no saturated pixel); a cool slate reads better
  { id: 'vlc',       name: 'VLC',         color: '#f57c00', vivid: '#fe8904', banner: true,  real: true,  sub: 'Opened 4 days ago' },
  { id: 'downloader',name: 'Downloader',  color: '#F2994A', vivid: '#F2994A', banner: false, real: false, sub: 'Opened last month' },
  { id: 'files',     name: 'Files',       color: '#3FAE7A', vivid: '#3FAE7A', banner: false, real: false, sub: 'Never opened' },
  { id: 'hof',       name: 'Home on Fire',color: '#E5654D', vivid: '#b71717', banner: false, real: true,  sub: 'Opened last week' },
];
const FTH = { id: 'fth', name: 'Fire TV Home', color: '#5B6477', vivid: '#5B6477', fth: true, sub: "Opens Amazon's home screen" };

/* ---------- state ---------- */
const pick = (k, ok, d) => (ok.includes(Q.get(k)) ? Q.get(k) : d);
const S = {
  screen: 'home',                       // home | panel | sheet | settings | safe
  cols: Q.get('cols') === '5' ? 5 : 6,
  clock: Q.get('clock') !== 'off',
  hero: pick('hero', ['wash', 'blur'], 'wash'),   // wash = backlit card on a radial colour field (default); blur = plus the full-bleed pre-blurred banner
  art: pick('art', ['real', 'placeholder'], (window.ORCHARD_REAL_ART === false && location.protocol === 'file:') ? 'placeholder' : 'real'),   // real banners by default; a fresh clone (real-art.js = false) opened from file:// starts on the placeholders, ?art=real overrides
  labels: pick('labels', ['focus', 'always'], 'focus'),
  reduced: Q.get('motion') === 'reduced' || matchMedia('(prefers-reduced-motion: reduce)').matches,
  wall: pick('wall', ['dusk', 'ember', 'fog'], 'dusk'),
  fth: Q.get('firetv_tile') !== 'off',
  hof: pick('hof', ['ok', 'missing', 'grant'], 'ok'),
  failReset: Q.get('reset') === 'fail',
  card: pick('card', ['emblem', 'banner', 'off'], Q.get('card') === 'on' ? 'banner' : 'emblem'),   // [P4] shelf card: emblem plate (default) | banner (the banner art) | off (wash + title only); ?card=on = banner (old links)
  chip: Q.get('chip') || '',
  r: 0, c: 0, wantC: 0,
  sheetFrom: 'home', safeFrom: null,
};

/* ---------- colour helpers ---------- */
const hex2rgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const lin = (v) => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
const lum = ([r, g, b]) => 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
function rgb2hsl([r, g, b]) { r /= 255; g /= 255; b /= 255; const mx = Math.max(r, g, b), mn = Math.min(r, g, b); let h = 0, s = 0; const l = (mx + mn) / 2;
  if (mx !== mn) { const d = mx - mn; s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn); h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4; h /= 6; } return [h, s, l]; }
function hsl2rgb([h, s, l]) { const f = (p, q, t) => { if (t < 0) t += 1; if (t > 1) t -= 1; return t < 1 / 6 ? p + (q - p) * 6 * t : t < 1 / 2 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p; };
  if (s === 0) return [l * 255, l * 255, l * 255]; const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q; return [f(p, q, h + 1 / 3) * 255, f(p, q, h) * 255, f(p, q, h - 1 / 3) * 255]; }
/* same hue/saturation, HSL lightness = target ("lum" in the tokens means HSL lightness) */
function tone(hex, target, satMax) { const [h, s0] = rgb2hsl(hex2rgb(hex)); const s = satMax != null ? Math.min(s0, satMax) : s0;
  const [r, g, b] = hsl2rgb([h, s, target]); return `rgb(${Math.round(r)},${Math.round(g)},${Math.round(b)})`; }

/* ---------- art (banner image with CSS fallback, never a drawn logo) ---------- */
const vivid = (app) => (S.art === 'real' ? app.vivid : app.color) || app.color;
/* the colour that matches the file that actually loaded (null = none) */
const vividFor = (app, src) => (src && src.indexOf('banners-real/') === 0 ? app.vivid : app.color) || app.color;
/* candidate files, in order: real banner -> placeholder banner -> nothing (icon-only fallback) */
function artFiles(app) {
  const f = [];
  if (S.art === 'real' && app.real) f.push('banners-real/' + app.id + '.png');
  if (app.banner) f.push('banners/' + app.id + '.png');
  return f;
}
/* icon-only back plate: gradient + halo + the icon (used for apps with no banner, and when every banner file fails to load) */
function iconFace(art, app, v) {
  v = v || vivid(app);
  art.classList.add('icon'); art.classList.remove('ok');
  const halo = mk('div', 'halo', art);
  halo.style.background = `radial-gradient(90% 110% at 18% 0%, ${tone(v, tk('tile-fallback-halo-lum')).replace('rgb(', 'rgba(').replace(')', ',' + tk('tile-fallback-halo-alpha') + ')')}, rgba(0,0,0,0) 70%)`;
  const ic = mk('div', 'ic', art, app.name[0]);
  ic.style.background = `linear-gradient(180deg, ${tone(v, tk('tile-fallback-icon-lum-a'))}, ${tone(v, tk('tile-fallback-icon-lum-b'))})`;
}
/* onsrc(src|null) reports the file that actually loaded (null = none did, icon-only fallback) */
function makeFace(app, cls, onsrc) {
  const face = mk('div', 'face' + (cls ? ' ' + cls : ''));
  const art = mk('div', 'art', face);
  if (app.fth) {
    art.classList.add('fth');
    art.appendChild(svg('<path d="M6 22 L24 7 L42 22 M10 19 V40 H20 V28 H28 V40 H38 V19"/>'));
    mk('div', 'wm', art, app.name);
    return face;
  }
  const v = vivid(app);
  // back plate: vertical gradient + soft halo top-left (two shaders on device)
  art.style.background = `linear-gradient(180deg, ${tone(v, tk('tile-fallback-lum-a'))}, ${tone(v, tk('tile-fallback-lum-b'))})`;
  const files = artFiles(app);
  if (files.length) {
    const wm = mk('div', 'wm', art, app.name);
    const img = new Image(); img.alt = '';
    img.onload = () => {
      art.classList.add('ok');
      const src = img.getAttribute('src');
      // a placeholder loaded although real art was wanted: the plate takes the placeholder colour too
      if (S.art === 'real' && src.indexOf('banners-real/') !== 0) art.style.background = `linear-gradient(180deg, ${tone(app.color, tk('tile-fallback-lum-a'))}, ${tone(app.color, tk('tile-fallback-lum-b'))})`;
      if (onsrc) onsrc(src);
    };
    img.onerror = () => {
      const n = files.shift();
      if (n) img.src = n;
      else { img.remove(); wm.remove(); iconFace(art, app, app.color); if (onsrc) onsrc(null); }   // every file failed, so the real colour is unknown: use the placeholder colour   // every file failed: the designed icon-only fallback
    };
    img.src = files.shift();
    art.appendChild(img);
  } else {
    iconFace(art, app);
    if (onsrc) onsrc(null);
  }
  return face;
}

/* ---------- home model ---------- */
let rows = [];          // rows[r] = [{app, el, x, y}]
let focusTile = null;
function buildHome() {
  root.dataset.cols = String(S.cols);
  const COLS = tk('cols'), TW = tk('tile-w'), PITCH = tk('col-pitch'), RP = tk('row-pitch'), X0 = tk('safe-x'), Y0 = tk('dock-tile-y');
  const dock = APPS.filter((a) => a.dock && a.dock <= COLS).sort((a, b) => a.dock - b.dock);
  const rest = APPS.filter((a) => !dock.includes(a)).sort((a, b) => a.name.localeCompare(b.name, 'en', { sensitivity: 'base' }));
  if (S.fth) rest.push(FTH);                          // the last tile
  const all = [dock]; for (let i = 0; i < rest.length; i += COLS) all.push(rest.slice(i, i + COLS));
  const host = $('tiles'); host.innerHTML = ''; rows = [];
  all.forEach((list, r) => {
    rows[r] = list.map((app, c) => {
      const x = X0 + c * PITCH, y = Y0 + r * RP;
      const el = mk('div', 'tile', host); el.style.left = x + 'px'; el.style.top = y + 'px';
      el.setAttribute('aria-label', app.name); el.title = app.name;
      const rec = { app, el, x, y, v: vivid(app) };
      el.appendChild(makeFace(app, null, (src) => { rec.v = vividFor(app, src); if (focusTile === rec) setGlow(rec); }));
      mk('div', 'gloss', el.firstChild);
      mk('div', 'lbl', el, app.name);
      return rec;
    });
  });
  focusTile = null;
  S.r = Math.min(S.r, rows.length - 1); S.c = Math.min(S.c, rows[S.r].length - 1);
  if (S.screen === 'home') setFocus(S.r, S.c, true);
}

/* ---------- hero (crossfade 280 ms after 120 ms debounce) ---------- */
let heroSide = 0, heroApp = null, heroTimer = 0;
function fillHero(layer, app) {
  layer.innerHTML = '';
  let v = vivid(app);
  const hbg = mk('div', 'hbg', layer);
  // 1. colour wash: radial from the app's vivid colour (L capped) centred behind the card, to bg
  const wash = mk('div', 'wash', hbg);
  const paintWash = () => {
    const c0 = tone(v, tk('hero-lum'), tk('hero-sat-max')), c1 = tone(v, tk('hero-wash-mid-lum'), tk('hero-sat-max'));
    wash.style.background = `radial-gradient(${tk('hero-wash-rx') * 100}% ${tk('hero-wash-ry') * 100}% at ${tk('hero-wash-x') * 100}% ${tk('hero-wash-y') * 100}%, ${c0} 0%, ${c1} ${tk('hero-wash-mid') * 100}%, var(--bg) 100%)`;
  };
  paintWash();
  // 2. full-bleed pre-blurred banner (hero=blur), filled once we know which file really loaded
  const bl = S.hero === 'blur' ? mk('div', 'bl', hbg) : null;
  mk('div', 'leak', hbg);
  mk('div', 'dim', hbg);
  // 3. the card, backlit by its own blurred art. Flat vivid-colour glow until a banner loads (and for icon-only apps / near-white banners)
  const g = mk('div', 'hglow', layer);
  const flat = () => { g.style.backgroundImage = 'none'; g.style.background = tone(v, tk('hero-glow-flat-lum')); g.style.opacity = app.white ? tk('hero-glow-alpha') : tk('hero-glow-flat-alpha'); };
  flat();
  const art = mk('div', 'hart', layer);
  if (app.white) art.classList.add('white');
  if (S.card === 'emblem' && !app.fth) {
    // [P4] emblem plate: gradient + halo + the app icon, in the card geometry. The mockup has no real app icons, so the icon is the same
    // stand-in the icon-only tile fallback draws (the app's initial on its gradient), scaled to --hero-emblem-icon.
    art.classList.add('emblem');
    const sat = tk('hero-sat-max'), rgba = (c, a) => c.replace('rgb(', 'rgba(').replace(')', ',' + a + ')');
    const plate = mk('div', 'plate', art);
    plate.style.background = `linear-gradient(180deg, ${tone(v, tk('hero-emblem-lum-a'), sat)}, ${tone(v, tk('hero-emblem-lum-b'), sat)})`;
    mk('div', 'halo', plate).style.background = `radial-gradient(90% 110% at 18% 0%, ${rgba(tone(v, tk('hero-emblem-halo-lum'), sat), tk('hero-emblem-halo-alpha'))}, rgba(0,0,0,0) 70%)`;
    const ic = mk('div', 'eic', plate, app.name[0]);
    ic.style.background = `linear-gradient(180deg, ${tone(v, tk('tile-fallback-icon-lum-a'))}, ${tone(v, tk('tile-fallback-icon-lum-b'))})`;
    g.style.backgroundImage = 'none'; g.style.background = tone(v, tk('hero-glow-flat-lum')); g.style.opacity = tk('hero-emblem-glow-alpha');
  } else {
  art.appendChild(makeFace(app, null, (src) => {
    const v2 = vividFor(app, src);
    if (v2 !== v) { v = v2; paintWash(); if (!g.style.backgroundImage || g.style.backgroundImage === 'none') flat(); }
    if (!src) { if (bl) bl.remove(); return; }
    // near-white banners would bloom grey: keep the vivid-colour glow instead of the blurred banner
    if (!app.white) { g.style.background = ''; g.style.backgroundImage = `url(${src})`; g.style.opacity = ''; }
    if (bl) bl.style.backgroundImage = `url(${src})`;
  }));
  }
  mk('div', 'htitle', layer, app.name);
  mk('div', 'hsub', layer, app.sub);
}
function setHero(app, instant) {
  if (heroApp === app && !instant) return;
  heroApp = app;
  const apply = () => {
    const next = $(heroSide ? 'hlA' : 'hlB'), cur = $(heroSide ? 'hlB' : 'hlA');
    fillHero(next, app); next.classList.add('on'); cur.classList.remove('on'); heroSide ^= 1;
  };
  clearTimeout(heroTimer);
  if (instant) apply(); else heroTimer = setTimeout(apply, tk('hero-debounce-ms'));
}

/* ---------- focus, shadow, label, scroll ---------- */
let swayT1 = 0, swayT2 = 0;
function stopSway() { clearTimeout(swayT1); clearTimeout(swayT2); document.querySelectorAll('.tile.sway').forEach((t) => t.classList.remove('sway')); }
function startSwaySoon() {
  stopSway(); if (S.reduced) return;
  swayT1 = setTimeout(() => {
    if (!focusTile || S.screen === 'settings' || S.screen === 'safe') return;
    focusTile.el.classList.add('sway');
    // 3 cycles (18 s) or 20 s, whichever first; the CSS animation itself ends after 3 cycles
    swayT2 = setTimeout(stopSway, Math.min(tk('sway-period-ms') * tk('sway-max-cycles'), tk('sway-max-ms')));
  }, tk('sway-start-ms'));
}
function scaleNow() { return S.reduced ? tk('reduced-scale') : tk('focus-scale'); }
function placeLabel() {
  if (!focusTile) return; const TH = tk('tile-h');
  const sc = scaleNow() + (document.body.classList.contains('idle') && !S.reduced ? tk('idle-scale-add') : 0);
  $('flabel').style.top = (focusTile.y + TH + TH * (sc - 1) / 2 + tk('label-gap')) + 'px';
}
function blurHome() { if (focusTile) focusTile.el.classList.remove('focused'); $('shadow').classList.remove('on'); $('flabel').classList.remove('show'); stopSway(); }
/* ambient glow colour: dark-art tiles get a lighter, stronger glow so focus reads beyond scale alone */
function setGlow(t) {
  const L = t.app.dark ? tk('glow-dark-lum') : tk('glow-lum'), A = t.app.dark ? tk('glow-dark-alpha') : tk('glow-alpha');
  $('shadow').style.setProperty('--glow', tone(t.v || vivid(t.app), L).replace('rgb(', 'rgba(').replace(')', ',' + A + ')'));
}
const scrollY = (r) => (r === 0 ? 0 : tk('dock-tile-y') + tk('row-pitch') * r - tk('grid-anchor-y'));
function setFocus(r, c, instant) {
  const t = rows[r][c];
  if (focusTile && focusTile !== t) focusTile.el.classList.remove('focused');
  focusTile = t; S.r = r; S.c = c;
  document.querySelectorAll('.btn.focused,.row.focused,.ptile.focused').forEach((e) => e.classList.remove('focused'));
  t.el.classList.add('focused');
  const sh = $('shadow'); sh.style.transition = 'none'; sh.classList.remove('on');
  sh.style.setProperty('--sx', t.x + 'px'); sh.style.setProperty('--sy', t.y + 'px');
  setGlow(t);
  void sh.offsetWidth; if (!instant) sh.style.transition = ''; sh.classList.add('on');
  if (instant) { void sh.offsetWidth; sh.style.transition = ''; }
  // label sits under the SCALED tile: bottom + (scale-1)*h/2 + 16
  const TW = tk('tile-w'), TH = tk('tile-h');
  const lb = $('flabel'); lb.textContent = t.app.name;
  lb.style.left = (t.x + TW / 2) + 'px';
  placeLabel();
  lb.classList.remove('show'); void lb.offsetWidth; lb.classList.add('show');
  const y = scrollY(r);   // dock row stays at 0; grid row r>=1 lands its top on --grid-anchor-y (no dead band)
  $('scroller').style.transform = `translateY(${-y}px)`; $('scrim').classList.toggle('on', y > 0);
  rows.forEach((row, i) => row.forEach((x) => x.el.classList.toggle('off', i < r - 1)));
  $('dockband').classList.toggle('off', r > 1); $('home').classList.toggle('scrolled', r >= 1);
  setHero(t.app, instant);
  document.body.dataset.focus = t.app.name; document.body.dataset.pos = r + ',' + c;
  startSwaySoon();
}
function bump(el, dx, dy) {
  if (S.reduced || !el.animate) return; const b = tk('bump-px');
  el.animate([{ translate: '0 0' }, { translate: `${dx * b}px ${dy * b}px` }, { translate: '0 0' }], { duration: tk('bump-ms'), easing: 'ease-out' });
}
function moveHome(dx, dy) {
  let r = S.r, c = S.c;
  if (dx) { c += dx; if (c < 0 || c >= rows[r].length) return bump(focusTile.el, dx, 0); S.wantC = c; }
  if (dy) { r += dy; if (r < 0 || r >= rows.length) return bump(focusTile.el, 0, dy); c = Math.min(S.wantC, rows[r].length - 1); }
  setFocus(r, c);
}
function selectHome() {
  const t = focusTile, el = t.el;
  const go = () => {
    if (t.app.fth) openSheet('home'); else toast('Launch ' + t.app.name + ' (mock)');
  };
  if (S.reduced) return go();
  el.classList.add('pressed'); setTimeout(() => { el.classList.remove('pressed'); go(); }, tk('press-ms'));
}

/* ---------- idle dim ---------- */
let idleT = 0;
function wake() { clearTimeout(idleT); document.body.classList.remove('idle'); placeLabel(); if (!S.reduced) idleT = setTimeout(() => { if (S.screen === 'home') { document.body.classList.add('idle'); placeLabel(); } }, tk('idle-ms')); }

/* ---------- toast ---------- */
let toastT = 0;
function toast(msg) { const t = $('toast'); t.textContent = msg; t.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('on'), 2200); }

/* ---------- generic focus list for panel / sheet / settings / safe ---------- */
let list = { items: [], idx: 0 };
function setList(items, idx) { list.items.forEach((e) => e.classList.remove('focused')); list = { items, idx }; items.forEach((e, i) => e.classList.toggle('focused', i === idx)); }
function moveList(d, horizontal) {
  const n = list.items.length, i = list.idx + d;
  if (i < 0 || i >= n) return bump(list.items[list.idx], horizontal ? d : 0, horizontal ? 0 : d);
  setList(list.items, i);
}

/* ---------- Quick Panel ---------- */
const GEAR = (() => { let d = ''; for (let i = 0; i < 32; i++) { const a = i * Math.PI / 16 - Math.PI / 32; const r = (i % 4 < 2) ? 17 : 13; d += (i ? 'L' : 'M') + (24 + r * Math.cos(a)).toFixed(2) + ' ' + (24 + r * Math.sin(a)).toFixed(2); } return `<path d="${d}Z"/><circle cx="24" cy="24" r="6"/>`; })();
const HOUSE = '<path d="M6 22 L24 7 L42 22 M10 19 V40 H20 V28 H28 V40 H38 V19"/>';
const MOON = '<path d="M33 30 A14 14 0 1 1 20 11 A11 11 0 0 0 33 30 Z"/>';
const TVSET = '<rect x="6" y="9" width="36" height="25" rx="4"/><path d="M16 41 H32 M24 34 V41"/><circle cx="24" cy="21.5" r="4"/>';
function panelTiles() {
  const t2 = S.hof === 'missing' ? ['Turn Home redirect back on', HOUSE] : S.hof === 'grant' ? ['Return to Fire TV', HOUSE, 'needs adb grant'] : ['Return to Fire TV', HOUSE];
  return [['Settings', GEAR], t2, ['Screensaver', MOON], ['Fire TV Settings', TVSET]];
}
function buildPanel() {
  const p = $('panel'); p.querySelectorAll('.ptile').forEach((e) => e.remove());
  p.style.setProperty('--gx', tk('panel-x')); p.style.setProperty('--gy', tk('panel-y'));
  const gap = tk('panel-gap'), w = tk('panel-tile'), x0 = tk('panel-tile-x') - tk('panel-x');
  panelTiles().forEach(([label, ico, sub], i) => {
    const t = mk('div', 'ptile rim'); t.style.left = (x0 + i * (w + gap)) + 'px'; t.appendChild(svg(ico)); mk('div', 'pl', t, label); if (sub) mk('div', 'pl2', t, sub); t.title = label + (sub ? ' (' + sub + ')' : ''); p.insertBefore(t, $('phint'));
  });
}
function openPanel() {
  blurHome();
  S.screen = 'panel'; setScreenAttr(); $('panel').classList.add('open'); setVeil('panel');
  setList([...$('panel').querySelectorAll('.ptile')], 0);
  document.body.classList.remove('idle');
}
function closePanel(toScreen) { $('panel').classList.remove('open'); list.items.forEach((e) => e.classList.remove('focused')); if (toScreen !== false) { S.screen = 'home'; setScreenAttr(); setVeil(null); setFocus(S.r, S.c, true); } }
function selectPanel() {
  const i = list.idx;
  if (i === 0) { closePanel(false); setVeil(null); showSettings(); }
  else if (i === 1) { closePanel(false); openSheet('panel'); }
  else if (i === 2) toast('Screensaver (ClockDream) is not part of the mockup');
  else toast('Opens Fire TV Settings (mock)');
}
function setVeil(kind) { const v = $('veil'); v.className = kind ? 'on ' + kind : ''; }

/* ---------- Reset confirm sheet ---------- */
function openSheet(from) {
  blurHome();
  if (from === 'settings' && list.items[list.idx]) { list.items[list.idx].classList.remove('focused'); S._settingsIdx = list.idx; }
  S.sheetFrom = from; S.screen = 'sheet'; setScreenAttr(); setVeil('sheet');
  const sh = $('sheet'); sh.innerHTML = '';
  mk('div', 'slice', sh).appendChild(mk('div')); mk('div', 'fill', sh);
  if (S.failReset) {
    mk('h2', null, sh, 'Return to Fire TV home?');
    mk('p', 'body', sh, 'Could not switch the redirect off. Hold Home on the remote for Amazon’s home screen.');
    const row = mk('div', 'btnrow', sh); const ok = mk('div', 'btn rim', row, 'OK');
    sh.classList.add('open'); placeSheetSlice(); setList([ok], 0); ok.onclick = null; sh._actions = [closeSheet];
  } else {
    mk('h2', null, sh, 'Return to Fire TV home?');
    mk('p', 'body', sh, 'Home will open Amazon’s home screen until you switch Orchard back on (Apps row → Orchard → Options).');
    mk('p', 'keeps', sh, 'Keeps: Home on Fire, Orchard and your other apps. For a fully stock Stick run launcher.sh factory-stock on the Mac.');
    const row = mk('div', 'btnrow', sh); const cancel = mk('div', 'btn rim', row, 'Cancel'); const go = mk('div', 'btn rim', row, 'Return to Fire TV');
    sh.classList.add('open'); placeSheetSlice(); setList([cancel, go], 0);   // Cancel focused by default
    sh._actions = [closeSheet, () => { closeSheet(); toast('Redirect switched off (mock): Fire TV home would open'); }];
  }
}
function placeSheetSlice() {   // layout box (not the animated transform): centred on the canvas
  const sh = $('sheet');
  sh.style.setProperty('--gx', (tk('canvas-w') - sh.offsetWidth) / 2); sh.style.setProperty('--gy', (tk('canvas-h') - sh.offsetHeight) / 2);
}
function closeSheet() {
  $('sheet').classList.remove('open');
  $('sheet').querySelectorAll('.focused').forEach((e) => e.classList.remove('focused'));
  const from = S.sheetFrom;
  if (from === 'settings') { S.screen = 'settings'; setVeil(null); showSettings(true); }
  else { S.screen = 'home'; setVeil(null); setFocus(S.r, S.c, true); }
  setScreenAttr();
}

/* ---------- Settings ---------- */
const SET_ROWS = [
  { k: 'cols',   label: 'Columns',            get: () => String(S.cols), opts: ['5', '6'], set: (v) => { S.cols = +v; S.r = 0; S.c = 0; S.wantC = 0; buildHome(); } },
  { k: 'clock',  label: 'Clock',              tog: () => S.clock, flip: () => { S.clock = !S.clock; applyChrome(); } },
  { k: 'card',   label: 'Shelf card',         get: () => S.card, opts: ['emblem', 'banner', 'off'], set: (v) => { S.card = v; applyChrome(); setHero(heroApp, true); } },
  { k: 'hero',   label: 'Hero style',         get: () => S.hero, opts: ['wash', 'blur'], set: (v) => { S.hero = v; setHero(heroApp, true); } },
  { k: 'art',    label: 'Tile art',           get: () => S.art, opts: ['real', 'placeholder'], set: (v) => { S.art = v; buildHome(); setHero(heroApp, true); } },
  { k: 'labels', label: 'Tile labels',        get: () => (S.labels === 'always' ? 'Always' : 'Focused only'), opts: ['focus', 'always'], raw: () => S.labels, set: (v) => { S.labels = v; applyChrome(); } },
  { k: 'motion', label: 'Reduced motion',     tog: () => S.reduced, flip: () => setReduced(!S.reduced) },
  { k: 'wall',   label: 'Wallpaper',          get: () => S.wall, opts: ['dusk', 'ember', 'fog'], set: (v) => { S.wall = v; applyChrome(); } },
  { k: 'fth',    label: 'Fire TV Home tile',  tog: () => S.fth, flip: () => { S.fth = !S.fth; buildHome(); } },
  { k: 'reset',  label: 'Return to Fire TV…', go: () => openSheet('settings') },
  { k: 'safe',   label: 'Safe mode preview',  go: () => showSafe('settings') },
];
function buildSettings() {
  const host = $('slist'); host.innerHTML = '';
  SET_ROWS.forEach((d) => {
    const row = mk('div', 'row', host); mk('span', null, row, d.label); const val = mk('span', 'val', row); d.rowEl = row; d.valEl = val;
  });
  refreshSettings();
}
function refreshSettings() {
  SET_ROWS.forEach((d) => {
    d.valEl.innerHTML = '';
    if (d.tog) mk('span', 'tog' + (d.tog() ? ' on' : ''), d.valEl);
    else if (d.get) d.valEl.textContent = '‹  ' + d.get() + '  ›';
    else d.valEl.textContent = '›';
  });
}
function showSettings(keepIdx) {
  setVeil(null); blurHome(); S.screen = 'settings'; setScreenAttr(); stopSway();
  $('home').classList.add('hidden'); $('settings').classList.add('open'); $('safe').classList.remove('open');
  $('scrim').classList.remove('on'); $('strip').style.display = '';
  setList(SET_ROWS.map((d) => d.rowEl), keepIdx ? list.idx || 0 : 0);
  if (keepIdx && S._settingsIdx != null) setList(SET_ROWS.map((d) => d.rowEl), S._settingsIdx);
}
function changeSetting(dir) {
  const d = SET_ROWS[list.idx]; S._settingsIdx = list.idx;
  if (d.go) { if (dir === 0) d.go(); return; }
  if (d.tog) { if (dir === 0 || true) d.flip(); }
  else { const cur = d.raw ? d.raw() : d.get(); const o = d.opts; let i = o.indexOf(cur); i = dir === 0 ? (i + 1) % o.length : Math.max(0, Math.min(o.length - 1, i + dir)); if (o[i] === cur && dir !== 0) return bump(d.rowEl, dir, 0); d.set(o[i]); }
  refreshSettings();
}
function hideSettings() {
  $('settings').classList.remove('open'); $('home').classList.remove('hidden');
  S.screen = 'home'; setScreenAttr(); setVeil(null); applyChrome(); setFocus(S.r, S.c, true);
  $('scroller').style.transform = `translateY(${-scrollY(S.r)}px)`; $('scrim').classList.toggle('on', S.r > 0);
  startSwaySoon(); wake();
}

/* ---------- Safe mode ---------- */
function showSafe(from) {
  blurHome(); S.screen = 'safe'; S.safeFrom = from; setScreenAttr(); stopSway();
  const w = $('safewrap'); w.innerHTML = '';
  w.appendChild(svg('<path d="M24 5 L41 12 V24 C41 34 33 41 24 44 C15 41 7 34 7 24 V12 Z"/><path d="M24 17 V27 M24 33 V33.5"/>')).setAttribute('class', 'glyph');
  mk('h1', null, w, 'Safe mode');
  mk('p', null, w, 'Orchard stopped three times in a row, so it is showing this plain screen. Nothing is broken: you can always go back to Amazon’s home.');
  mk('p', 'small', w, 'Safe mode clears itself after Orchard runs for a minute without a crash.');
  const b = mk('div', 'btns', w); const a = mk('div', 'btn wide rim', b, 'Open Fire TV Home'); const t = mk('div', 'btn wide rim', b, 'Try Orchard again');
  $('home').classList.add('hidden'); $('settings').classList.remove('open'); $('safe').classList.add('open');
  setList([a, t], 0); applyChrome();
  $('chip').textContent = 'Safe mode'; $('chip').classList.add('on');
}
function hideSafe() {
  $('safe').classList.remove('open'); applyChrome(); list.items.forEach((e) => e.classList.remove('focused'));
  if (S.safeFrom === 'settings') showSettings(true); else { $('home').classList.remove('hidden'); S.screen = 'home'; setScreenAttr(); setFocus(S.r, S.c, true); }
}

/* ---------- chrome / chip / stage ---------- */
function applyChrome() {
  document.body.classList.toggle('reduced', S.reduced);
  document.body.classList.toggle('labels-always', S.labels === 'always');
  $('clockwrap').classList.toggle('off', !S.clock);
  const w = $('wall'); w.style.backgroundImage = `url(wall/${S.wall}.webp)`;
  root.style.setProperty('--wallurl', `url(wall/${S.wall}.webp)`);   // every glass slice samples the (pre-blurred) wallpaper only
  document.body.classList.toggle('card-off', S.card === 'off');
  const tint = { dusk: ['#2B2E5C', '#15161C'], ember: ['#4A1418', '#0B0B0D'], fog: ['#3A4452', '#0C0D10'] }[S.wall];
  w.style.backgroundColor = tint[1]; w.dataset.wall = S.wall;
  let chipText = S.chip; if (S.screen === 'safe') chipText = 'Safe mode';
  $('chip').textContent = chipText; $('chip').classList.toggle('on', !!chipText);
}
function setReduced(v) { S.reduced = v; stopSway(); clearTimeout(idleT); document.body.classList.remove('idle'); applyChrome(); if (S.screen === 'home') setFocus(S.r, S.c, true); else if (list.items.length) setList(list.items, list.idx); toast(v ? 'Reduced motion on' : 'Reduced motion off'); wake(); }
function setScreenAttr() { $('toast').classList.remove('on'); document.body.dataset.screen = S.screen; $('stage').dataset.screen = S.screen; }
function fit() { const k = Math.min(innerWidth / tk('canvas-w'), innerHeight / tk('canvas-h')); $('stage').style.transform = `scale(${k})`; }
addEventListener('resize', fit);

/* ---------- keys ---------- */
let menuDown = null, menuHoldT = 0;
function onBack() {
  switch (S.screen) {
    case 'panel': closePanel(); break;
    case 'sheet': closeSheet(); break;
    case 'settings': hideSettings(); break;
    case 'safe': hideSafe(); break;
    default:   // home: scroll to top + focus dock 1, consumed (never leaves the launcher)
      if (S.r !== 0 || S.c !== 0) { S.wantC = 0; setFocus(0, 0); } else bump(focusTile.el, -1, 0);
  }
}
function onSelect() {
  switch (S.screen) {
    case 'home': selectHome(); break;
    case 'panel': selectPanel(); break;
    case 'sheet': $('sheet')._actions[list.idx](); break;
    case 'settings': changeSetting(0); break;
    case 'safe': if (list.idx === 0) toast('Opens Amazon’s home (mock)'); else hideSafe(); break;
  }
}
function onDir(dx, dy) {
  switch (S.screen) {
    case 'home': moveHome(dx, dy); break;
    case 'panel': case 'sheet': if (dx) moveList(dx, true); else if (dy < 0 && S.screen === 'panel') {} break;
    case 'settings': if (dy) moveList(dy, false); else changeSetting(dx); break;
    case 'safe': if (dy) moveList(dy, false); break;
  }
}
function menuShort() {
  if (S.screen === 'home') openPanel();
  else if (S.screen === 'panel') closePanel();
  else if (S.screen === 'sheet') closeSheet();
}
addEventListener('keydown', (e) => {
  if (e.metaKey || e.ctrlKey || e.altKey) return;
  const k = e.key; let handled = true; wake();
  if (k === 'ArrowLeft') onDir(-1, 0);
  else if (k === 'ArrowRight') onDir(1, 0);
  else if (k === 'ArrowUp') onDir(0, -1);
  else if (k === 'ArrowDown') onDir(0, 1);
  else if (k === 'Enter' || k === ' ') { if (!e.repeat) onSelect(); }
  else if (k === 'Escape' || k === 'Backspace') { if (!e.repeat) onBack(); }
  else if (k === 'm' || k === 'M') {
    if (e.repeat) { if (menuDown) menuDown.repeat = true; }
    else if (!menuDown) {
      menuDown = { t: performance.now(), repeat: false };
      clearTimeout(menuHoldT);
      menuHoldT = setTimeout(() => { if (menuDown) toast('Long Menu: Home on Fire’s own screen opens, not Orchard’s panel'); }, tk('menu-hoof-ms'));
    }
  }
  else if (k === 'r' || k === 'R') { if (!e.repeat) setReduced(!S.reduced); }
  else if (k === '?' || k === '/') $('keys').classList.toggle('on');
  else handled = false;
  if (handled) e.preventDefault();
});
addEventListener('keyup', (e) => {
  if (e.key !== 'm' && e.key !== 'M') return;
  clearTimeout(menuHoldT);
  // Menu opens the panel on UP only: no repeat seen and held < 450 ms
  if (menuDown && !menuDown.repeat && performance.now() - menuDown.t < tk('menu-max-tap-ms')) menuShort();
  menuDown = null; wake();
});
addEventListener('blur', () => { menuDown = null; clearTimeout(menuHoldT); });   // a DOWN that never gets its UP is forgotten

/* ---------- boot ---------- */
function boot() {
  fit(); applyChrome(); buildPanel(); buildSettings(); buildHome();
  const want = Q.get('screen') || 'home';
  const fo = (Q.get('focus') || '').split(',').map(Number);   // [P4] ?focus=R,C: open with that tile focused (instant, normal setFocus path)
  if (fo.length === 2 && fo.every(Number.isInteger) && rows[fo[0]] && rows[fo[0]][fo[1]]) { S.wantC = fo[1]; setFocus(fo[0], fo[1], true); }
  if (want === 'panel') openPanel();
  else if (want === 'reset') openSheet('home');
  else if (want === 'settings') showSettings();
  else if (want === 'safe') showSafe(null);
  setScreenAttr(); wake();
  window.__orchard = { S, get focus() { return document.body.dataset.focus; } };
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { document.body.dataset.fonts = 'ready'; });
}
/* a fresh clone has no banners-real/ (git-ignored): probe once and default to the placeholders so the console stays clean */
if (S.art === 'real' && !Q.get('art') && location.protocol !== 'file:' && window.ORCHARD_REAL_ART !== true) {
  fetch('banners-real/manifest.json', { method: 'HEAD' }).then((r) => { if (!r.ok) S.art = 'placeholder'; }, () => { S.art = 'placeholder'; }).then(boot);
} else boot();
})();
