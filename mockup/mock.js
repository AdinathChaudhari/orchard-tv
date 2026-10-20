/* Orchard clickable mockup. All numbers are read from tokens.css (never duplicated here). */
(() => {
'use strict';
const Q = new URLSearchParams(location.search);
const root = document.documentElement;
const $ = (id) => document.getElementById(id);
const tk = (n) => parseFloat(getComputedStyle(root).getPropertyValue('--' + n));
/* raw token value for a given theme ('dark' | 'light'), read by flipping data-theme synchronously (no repaint, no transitions) */
function tkRaw(n, th) {
  if (root.dataset.theme === th) return getComputedStyle(root).getPropertyValue('--' + n).trim();
  const cur = root.dataset.theme; root.dataset.theme = th;
  const v = getComputedStyle(root).getPropertyValue('--' + n).trim(); root.dataset.theme = cur; return v;
}
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
  wall: pick('wall', ['sky', 'dusk', 'ember', 'fog'], 'sky'),   // Rev 6: sky (default, follows the sun) | the three static walls
  sky: (Q.get('sky') && ['night', 'dawn', 'morning', 'noon', 'afternoon', 'golden', 'horizon', 'sunrise', 'sunset', 'dusk'].includes(Q.get('sky'))) ? Q.get('sky') : 'auto',   // ?sky=auto|<keyframe>: pin the sky to one keyframe (its clock time is the default ?time)
  rainChance: (Q.get('rainchance') != null && /^\d{1,3}$/.test(Q.get('rainchance'))) ? Math.min(100, +Q.get('rainchance')) : null,   // ?rainchance=NN
  drops: null,                          // D key: null = follow the weather, true / false = forced for review
  fth: Q.get('firetv_tile') !== 'off',
  hof: pick('hof', ['ok', 'missing', 'grant'], 'ok'),
  failReset: Q.get('reset') === 'fail',
  card: pick('card', ['emblem', 'banner', 'off'], Q.get('card') === 'on' ? 'banner' : 'emblem'),   // [P4] shelf card: emblem plate (default) | banner (the banner art) | off (wash + title only); ?card=on = banner (old links)
  chip: Q.get('chip') || '',
  theme: pick('theme', ['auto', 'light', 'dark'], 'auto'),           // Rev 4: Auto (sunset) | Light | Dark
  time: (Q.get('time') && Q.get('time') !== 'live' && parseTime(Q.get('time')) != null) ? parseTime(Q.get('time')) : null,   // ?time=HH:MM simulates the city's local clock; default 21:42 (the static strip clock)
  live: Q.get('time') === 'live',
  wxOn: Q.get('weather') !== 'off',                                   // Settings -> Weather (default ON); ?weather=off
  wxNet: Q.get('weather') !== 'offline',                              // ?weather=offline: no data, chip hidden, never an error
  units: pick('units', ['auto', 'c', 'f'], 'auto'),
  city: 0,                                                            // index into CITIES; resolved in boot() from ?city= (default 'bengaluru', first in the list)
  phase: null,
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

/* ---------- Rev 4: cities, NOAA sun engine, theme phases, mock weather ----------
   Device twin: SunCalc.java (pure Java, host-tested), ThemePhase.java, WeatherClient.java. Same maths, same tokens. */
const CITIES = [
  { id: 'bengaluru', name: 'Bengaluru', cc: 'IN', lat: 12.9716, lon: 77.5946,  tz: 'Asia/Kolkata',     hi: 28, lo: 20, cond: 'clear' },   // Rev 6: mock default is a clear day so the sky shows at its best; ?wx=cloudy|rain|fog for the rest
  { id: 'newdelhi',  name: 'New Delhi', cc: 'IN', lat: 28.6139, lon: 77.2090,  tz: 'Asia/Kolkata',     hi: 31, lo: 19, cond: 'clear' },
  { id: 'mumbai',    name: 'Mumbai',    cc: 'IN', lat: 19.0760, lon: 72.8777,  tz: 'Asia/Kolkata',     hi: 33, lo: 25, cond: 'clear' },
  { id: 'pune',      name: 'Pune',      cc: 'IN', lat: 18.5204, lon: 73.8567,  tz: 'Asia/Kolkata',     hi: 31, lo: 20, cond: 'cloud' },
  { id: 'chennai',   name: 'Chennai',   cc: 'IN', lat: 13.0827, lon: 80.2707,  tz: 'Asia/Kolkata',     hi: 31, lo: 25, cond: 'rain' },
  { id: 'kolkata',   name: 'Kolkata',   cc: 'IN', lat: 22.5726, lon: 88.3639,  tz: 'Asia/Kolkata',     hi: 32, lo: 24, cond: 'cloud' },
  { id: 'hyderabad', name: 'Hyderabad', cc: 'IN', lat: 17.3850, lon: 78.4867,  tz: 'Asia/Kolkata',     hi: 31, lo: 21, cond: 'clear' },
  { id: 'dubai',     name: 'Dubai',     cc: 'AE', lat: 25.2048, lon: 55.2708,  tz: 'Asia/Dubai',       hi: 36, lo: 26, cond: 'haze' },
  { id: 'london',    name: 'London',    cc: 'GB', lat: 51.5072, lon: -0.1276,  tz: 'Europe/London',    hi: 14, lo: 8,  cond: 'rain' },
  { id: 'lisbon',    name: 'Lisbon',    cc: 'PT', lat: 38.7223, lon: -9.1393,  tz: 'Europe/Lisbon',    hi: 22, lo: 14, cond: 'clear' },
  { id: 'newyork',   name: 'New York',  cc: 'US', lat: 40.7128, lon: -74.0060, tz: 'America/New_York', hi: 16, lo: 9,  cond: 'cloud' },
  { id: 'tokyo',     name: 'Tokyo',     cc: 'JP', lat: 35.6762, lon: 139.6503, tz: 'Asia/Tokyo',       hi: 21, lo: 14, cond: 'clear' },
];
const MOCK_DATE = [2026, 10, 20];   // the strip's static date (Tue 20 Oct); ?time=live uses the city's real date, as the device does
/* y, m, d (and weekday/month names) of 'today': the city's real date when live, else the static mock date */
function todayParts() {
  if (!S.live) return { y: 2026, m: 10, d: 20, wd: 'Tue', mon: 'Oct' };
  const f = new Intl.DateTimeFormat('en-US', { timeZone: curCity().tz, year: 'numeric', month: 'numeric', day: 'numeric', weekday: 'short' });
  const o = Object.fromEntries(f.formatToParts(new Date()).map((x) => [x.type, x.value]));
  return { y: +o.year, m: +o.month, d: +o.day, wd: o.weekday, mon: new Date(Date.UTC(2000, +o.month - 1, 1)).toLocaleString('en-US', { month: 'short', timeZone: 'UTC' }) };
}

/* NOAA solar calculator (Meeus), zenith 90.833 deg. Returns minutes after local midnight, or null + polar flag. */
const Sun = (() => {
  const R = Math.PI / 180, D = 180 / Math.PI;
  const julian = (y, m, d) => { if (m <= 2) { y -= 1; m += 12; } const A = Math.floor(y / 100), B = 2 - A + Math.floor(A / 4); return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + B - 1524.5; };
  function at(jd) {   // declination (deg) and equation of time (min) at a Julian day
    const T = (jd - 2451545) / 36525;
    const L0 = (280.46646 + T * (36000.76983 + T * 0.0003032)) % 360;
    const M = 357.52911 + T * (35999.05029 - 0.0001537 * T);
    const e = 0.016708634 - T * (0.000042037 + 0.0000001267 * T);
    const C = Math.sin(M * R) * (1.914602 - T * (0.004817 + 0.000014 * T)) + Math.sin(2 * M * R) * (0.019993 - 0.000101 * T) + Math.sin(3 * M * R) * 0.000289;
    const om = 125.04 - 1934.136 * T, lam = L0 + C - 0.00569 - 0.00478 * Math.sin(om * R);
    const sec = 21.448 - T * (46.815 + T * (0.00059 - T * 0.001813)), eps0 = 23 + (26 + sec / 60) / 60, eps = eps0 + 0.00256 * Math.cos(om * R);
    const decl = Math.asin(Math.sin(eps * R) * Math.sin(lam * R)) * D;
    const y = Math.tan(eps * R / 2) ** 2;
    const eq = 4 * D * (y * Math.sin(2 * L0 * R) - 2 * e * Math.sin(M * R) + 4 * e * y * Math.sin(M * R) * Math.cos(2 * L0 * R) - 0.5 * y * y * Math.sin(4 * L0 * R) - 1.25 * e * e * Math.sin(2 * M * R));
    return { decl, eq };
  }
  function ha(lat, decl) { const c = Math.cos(90.833 * R) / (Math.cos(lat * R) * Math.cos(decl * R)) - Math.tan(lat * R) * Math.tan(decl * R); return c > 1 ? -1 : c < -1 ? 99 : Math.acos(c) * D; }   // -1 = sun never rises, 99 = never sets
  function times(lat, lon, y, m, d, tzH) {
    const jd0 = julian(y, m, d), utcOf = (min) => jd0 + (min / 1440) - tzH / 24;
    const { decl, eq } = at(utcOf(720));
    const noon0 = 720 - 4 * lon - eq + tzH * 60;
    const h0 = ha(lat, decl);
    if (h0 === -1) return { rise: null, set: null, noon: noon0, polar: 'night' };
    if (h0 === 99) return { rise: null, set: null, noon: noon0, polar: 'day' };
    let rise = noon0 - 4 * h0, set = noon0 + 4 * h0;
    const a = at(utcOf(rise)), b = at(utcOf(set));          // one refinement at the event itself
    const hr = ha(lat, a.decl), hs = ha(lat, b.decl);
    if (hr > 0 && hr < 99) rise = 720 - 4 * lon - a.eq + tzH * 60 - 4 * hr;
    if (hs > 0 && hs < 99) set = 720 - 4 * lon - b.eq + tzH * 60 + 4 * hs;
    return { rise, set, noon: noon0, polar: null };
  }
  /* Rev 6: solar elevation (deg, refraction-corrected, NOAA) and azimuth (deg clockwise from north) at a local minute */
  function pos(lat, lon, y, m, d, tzH, minute) {
    const { decl, eq } = at(julian(y, m, d) + minute / 1440 - tzH / 24);
    const tst = (((minute + eq + 4 * lon - 60 * tzH) % 1440) + 1440) % 1440, ha = tst / 4 - 180;
    const sl = Math.sin(lat * R), cl = Math.cos(lat * R), sd = Math.sin(decl * R), cd = Math.cos(decl * R);
    const cz = Math.max(-1, Math.min(1, sl * sd + cl * cd * Math.cos(ha * R))), zen = Math.acos(cz) * D, eg = 90 - zen;
    const azr = Math.acos(Math.max(-1, Math.min(1, (sl * cz - sd) / (cl * Math.sin(zen * R) || 1e-9)))) * D;
    const az = ha > 0 ? (azr + 180) % 360 : (540 - azr) % 360;
    const t = Math.tan(eg * R); let rf;
    if (eg > 85) rf = 0; else if (eg > 5) rf = 58.1 / t - 0.07 / t ** 3 + 0.000086 / t ** 5; else if (eg > -0.575) rf = 1735 + eg * (-518.2 + eg * (103.4 + eg * (-12.79 + eg * 0.711))); else rf = -20.774 / t;
    return { e: eg + rf / 3600, az, eGeo: eg };
  }
  return { times, pos };
})();
function tzOffsetH(tz, y, m, d) {   // fixed offset for that local date (noon), via Intl; the device uses java.time / TimeZone
  const f = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' });
  const p = Object.fromEntries(f.formatToParts(new Date(Date.UTC(y, m - 1, d, 12))).map((x) => [x.type, +x.value]));
  return (Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Date.UTC(y, m - 1, d, 12)) / 3600000;
}
const hhmm = (min) => { const m = ((Math.round(min) % 1440) + 1440) % 1440; return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0'); };
function parseTime(s) { const m = /^(\d{1,2}):(\d{2})$/.exec(s || ''); return m && +m[1] < 24 && +m[2] < 60 ? +m[1] * 60 + +m[2] : null; }
const smooth = (x) => { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); };
const curCity = () => CITIES[S.city];
function sunToday() { const c = curCity(), { y, m, d } = todayParts(); return Sun.times(c.lat, c.lon, y, m, d, tzOffsetH(c.tz, y, m, d)); }

/* phase at local minute t: name, p (eased 0 night .. 1 day: alpha of the day layer), g (0..1 alpha of the golden-hour warm layer) */
function phaseAt(t, sun) {
  if (sun.rise == null) return sun.polar === 'day' ? { name: 'day', p: 1, g: 0 } : { name: 'night', p: 0, g: 0 };
  const ds = sun.rise + tk('dawn-start'), dl = tk('dawn-len'), us = sun.set + tk('dusk-start'), ul = tk('dusk-len');
  const gs = sun.set - tk('golden-len'), gr = tk('golden-ramp'), gf = tk('golden-fade');
  let name, p;
  if (t < ds) { name = 'night'; p = 0; }
  else if (t < ds + dl) { name = 'dawn'; p = smooth((t - ds) / dl); }
  else if (t < us) { name = t >= gs ? 'golden' : 'day'; p = 1; }
  else if (t < us + ul) { name = 'dusk'; p = 1 - smooth((t - us) / ul); }
  else { name = 'night'; p = 0; }
  let g = 0;
  if (t >= gs && t < us) g = smooth((t - gs) / gr);
  else if (t >= us && t < us + gf) g = 1 - smooth((t - us) / gf);
  return { name, p, g };
}
function themeState() {
  const sun = sunToday();
  if (S.theme === 'light') return { name: 'light', p: 1, g: 0, sun };
  if (S.theme === 'dark') return { name: 'dark', p: 0, g: 0, sun };
  return { ...phaseAt(S.time, sun), sun };
}

/* ---------- Rev 6: the sky engine. Device twin: SkyView.java + SkyPhase.java (pure Java, host-tested), see PLAN section 9 ---------- */
let _sc = null;
function sunCtx() { const c = curCity(), t = todayParts(), k = c.id + t.y + '-' + t.m + '-' + t.d; if (!_sc || _sc.k !== k) _sc = { k, c, y: t.y, m: t.m, d: t.d, tz: tzOffsetH(c.tz, t.y, t.m, t.d) }; return _sc; }
const sunPos = (t) => { const x = sunCtx(); return Sun.pos(x.c.lat, x.c.lon, x.y, x.m, x.d, x.tz, t); };
const RISE = [['night', 'dawn', 'nd'], ['dawn', 'sunrise', 'ds'], ['sunrise', 'morning', 'sm'], ['morning', 'noon', 'mn']];
const SETT = [['noon', 'afternoon', 'na'], ['afternoon', 'golden', 'ag'], ['golden', 'horizon', 'gs'], ['horizon', 'sunset', 'gt'], ['sunset', 'dusk', 'sd'], ['dusk', 'night', 'dn']];
/* elevation (deg) + side of noon -> the two keyframes and the mix f (0 = a only). Windows come from the tokens; those above 3 deg shrink with a low-sun day (emax < 50). */
function skyAt(e, rising, emax) {
  const k = Math.min(1, Math.max(0.15, emax / tk('sky-day-k-ref'))), sc = (x) => (x > 3 ? x * k : x);
  let cur = rising ? 'night' : 'noon';
  for (const [from, to, n] of rising ? RISE : SETT) {
    const a = sc(tk('sky-' + n + '-a')), b = sc(tk('sky-' + n + '-b')), f = (e - a) / (b - a);
    if (f <= 0) return { a: from, b: from, f: 0 };
    if (f < 1) return { a: from, b: to, f: smooth(f) };
    cur = to;
  }
  return { a: cur, b: cur, f: 0 };
}
const greeting = (t) => (t >= tk('greet-night') || t < tk('greet-morning') ? 'Good night' : t >= tk('greet-evening') ? 'Good evening' : t >= tk('greet-afternoon') ? 'Good afternoon' : 'Good morning');
/* weather -> sky tint: overcast step alpha, rain dim, fog flag, sun/moon glow multiplier. Weather off / offline = clear (no data, never an error). */
function wxSky() {
  if (!(S.wxOn && S.wxNet)) return { oc: 0, rdim: 0, fog: 0, glow: 1 };
  const c = wxCond();
  return { oc: c === 'cloud' ? tk('wx-oc-cloud') : c === 'rain' ? tk('wx-oc-rain') : c === 'haze' ? tk('wx-oc-fog') : 0, rdim: c === 'rain' ? tk('wx-rain-dim') : 0, fog: c === 'haze' ? 1 : 0, glow: c === 'cloud' ? tk('wx-glow-cloud') : c === 'rain' ? tk('wx-glow-rain') : c === 'haze' ? tk('wx-glow-fog') : 1 };
}
const dropsOn = () => (S.drops != null ? S.drops : (S.wxOn && S.wxNet && wxCond() === 'rain'));
function updateRain() { $('rain').classList.toggle('on', dropsOn() && $('stage').classList.contains('sky-on')); }
const skyUrl = (k, oc) => `url(wall/sky-${k}${oc ? '-oc' : ''}.webp)`;
const lerp = (a, b, t) => a + (b - a) * t, clamp01 = (x) => Math.max(0, Math.min(1, x));
function applySky(st) {
  const on = S.wall === 'sky' && S.theme === 'auto';
  $('stage').classList.toggle('sky-on', on);
  const sun = st.sun, pos = sunPos(S.time), rising = sun.noon != null ? S.time < sun.noon : true;
  const emax = sun.noon != null ? sunPos(Math.round(sun.noon)).e : 60;
  const ks = S.sky !== 'auto' ? { a: S.sky, b: S.sky, f: 0 } : skyAt(pos.e, rising, emax);
  S.skyState = { ...ks, e: pos.e, az: pos.az, rising };
  if (!on) { root.style.removeProperty('--sf'); updateRain(); return; }
  const w = wxSky(), oc = w.oc >= 0.5;
  // hand-over: when the new pair's A is the old pair's B (a window just finished and the next began), the picture on screen is already that keyframe. Swap the layer roles with NO transition for one frame
  // (new A = old B at alpha 1, new B = the next keyframe at alpha f) instead of fading the old B out under a freshly loaded next keyframe, which flashed it for ~1 s. Time jumps and weather changes keep the 900 ms fade.
  const prev = S.skPrev, roll = !!prev && ks.a !== prev.a && ks.a === prev.b && prev.f >= 0.9;
  S.skPrev = { a: ks.a, b: ks.b, f: ks.f };
  if (roll) { $('sky').classList.add('skroll'); $('scrim').classList.add('skroll'); }
  $('skA').style.backgroundImage = skyUrl(ks.a); $('skB').style.backgroundImage = skyUrl(ks.b); $('skB').style.opacity = ks.f;
  $('skAo').style.backgroundImage = skyUrl(ks.a, 1); $('skBo').style.backgroundImage = skyUrl(ks.b, 1); $('skBo').style.opacity = ks.f;
  $('skOc').style.opacity = w.oc;
  root.style.setProperty('--wallurl', skyUrl(ks.a, oc)); root.style.setProperty('--wallurl-day', skyUrl(ks.b, oc)); root.style.setProperty('--sf', ks.f);   // glass slices sample the same pair (pre-blurred on device)
  // fog veil: each theme's veil fades to 0 AT the theme flip (the hand-over frame is mid-luminance, and a lifted sky there fails both text colours) and is full only at its own end
  const fl = tk('theme-flip-at'), fD = w.fog * tk('wx-fog-day-a') * smooth(clamp01((st.p - fl) / (1 - fl))), fN = w.fog * tk('wx-fog-night-a') * smooth(clamp01((fl - st.p) / fl));
  $('fogD').style.opacity = fD; $('fogN').style.opacity = fN;
  $('rdim').style.opacity = w.rdim;
  // rain by day: a flat slate veil (alpha only, like the fog veil) so a rainy day reads darker than a cloudy one; 0 exactly at the theme flip, full at the end of the cross-fade
  const rD = w.rdim > 0 ? tk('wx-rain-day-a') * smooth(clamp01((st.p - fl) / (1 - fl))) : 0; $('rdimD').style.opacity = rD;
  const skyRgb = (k, o) => getComputedStyle(root).getPropertyValue('--sky-top-' + k + (o ? '-oc' : '')).trim().split(',').map(Number);
  const mixRgb = (x, y, t) => x.map((v, i) => v + (y[i] - v) * t);
  const skyC = mixRgb(mixRgb(skyRgb(ks.a), skyRgb(ks.b), ks.f), mixRgb(skyRgb(ks.a, 1), skyRgb(ks.b, 1), ks.f), w.oc);   // flat top colour, as the device's scrimRgb (before the fog/dim veils)
  const skyL = (0.2126 * skyC[0] + 0.7152 * skyC[1] + 0.0722 * skyC[2]) / 255;                                              // gamma-encoded Rec.709 luma, like SkyPhase.lum
  const rainA = Math.max(tk('rain-alpha-l'), Math.min(tk('rain-alpha-l-max'), skyL - 0.305 <= 1e-6 ? tk('rain-alpha-l-max') : tk('rain-contrast-l') / (skyL - 0.305)));   // streak alpha tracks the sky so peak contrast stays level (SkyPhase.rainAlphaLight)
  root.style.setProperty('--rain-lk', (rainA * smooth(clamp01((st.p - fl) / (1 - fl)))).toFixed(3));   // the slate drops of the light theme ramp in with the day, like the veil: 0 at the theme flip (a slate streak over a mid-luminance sky costs dark text its contrast)
  // status-strip scrim: the same stack as the sky, flat top colours (tokens --sky-top-<key>[-oc])
  const top = (k, o) => 'rgb(' + getComputedStyle(root).getPropertyValue('--sky-top-' + k + (o ? '-oc' : '')).trim() + ')';
  $('scA').style.background = top(ks.a); $('scB').style.background = top(ks.b); $('scB').style.opacity = ks.f;
  $('scAo').style.background = top(ks.a, 1); $('scBo').style.background = top(ks.b, 1); $('scBo').style.opacity = ks.f;
  $('scOc').style.opacity = w.oc; $('scFogD').style.opacity = fD; $('scFogN').style.opacity = fN; $('scDim').style.opacity = w.rdim; $('scDimD').style.opacity = rD;
  if (roll) { void $('sky').offsetWidth; void $('scrim').offsetWidth; $('sky').classList.remove('skroll'); $('scrim').classList.remove('skroll'); }
  // sun glow: x by azimuth (east left .. west right via the equator-ward side), y by elevation; alpha ramps in over [e0, e1]
  const W = tk('canvas-w'), H = tk('canvas-h');
  const sz = tk('glow-sun-size'), sx = W * lerp(tk('glow-x0'), tk('glow-x1'), 0.5 + 0.5 * Math.sin((pos.az - 180) * Math.PI / 180));
  const sy = H * lerp(tk('glow-sun-y-low'), tk('glow-sun-y-top'), Math.pow(clamp01(pos.e / 70), 0.8));
  $('glowSun').style.transform = `translate(${(sx - sz / 2).toFixed(1)}px,${(sy - sz / 2).toFixed(1)}px)`;
  $('glowSun').style.opacity = (tk('glow-sun-a') * smooth((pos.e - tk('glow-sun-e0')) / (tk('glow-sun-e1') - tk('glow-sun-e0'))) * w.glow).toFixed(3);
  // moon glow: positioned by the time of night (no ephemeris: a calm arc east to west), alpha ramps in as the sun sinks past -4
  const mz = tk('glow-moon-size'); let u = 0.5;
  if (sun.rise != null) { const len = sun.rise + 1440 - sun.set; u = clamp01(((S.time >= sun.set ? S.time - sun.set : S.time + 1440 - sun.set)) / len); }
  const mx = W * lerp(tk('glow-moon-x0'), tk('glow-moon-x1'), u), my = H * lerp(tk('glow-moon-y0'), tk('glow-moon-y1'), Math.pow(Math.sin(Math.PI * u), 0.8));
  $('glowMoon').style.transform = `translate(${(mx - mz / 2).toFixed(1)}px,${(my - mz / 2).toFixed(1)}px)`;
  $('glowMoon').style.opacity = (tk('glow-moon-a') * smooth((pos.e - tk('glow-moon-e0')) / (tk('glow-moon-e1') - tk('glow-moon-e0'))) * w.glow).toFixed(3);
  updateRain();
}
let lastTheme = null;
function applyTheme(user) {
  const st = themeState(); S.phase = st;
  const light = st.p >= tk('theme-flip-at');
  $('wallday').style.opacity = st.p; $('twilight').style.opacity = tk('tw-peak') * 4 * st.p * (1 - st.p); $('golden').style.opacity = st.g; root.style.setProperty('--p', st.p); root.style.setProperty('--hbk', 1 - tk('hero-tw-cut') * 4 * st.p * (1 - st.p));   // Rev 5b: the opaque shelf backdrops average to grey mid-fade and hid the dusk layer under the hero; their alpha falls by 4p(1-p)*cut so the twilight palette reads through
  { const f = tk('theme-flip-at');   // halo cap per side of the flip, the same in every weather: a dark halo behind white text .30, a pale one behind dark text .40
    const cap = light ? tk('halo-cap-light') : tk('halo-cap'), raw = st.name === 'dawn' || st.name === 'dusk' ? 0.9 * (1 - Math.abs(st.p - f) / (st.p < f ? f : 1 - f)) : 0, ts = Math.max(0, Math.min(cap, raw));
    root.style.setProperty('--ts', ts); root.style.setProperty('--tn', cap > 0 ? Math.min(1, ts / (cap * tk('tn-ramp'))) : 0); }   // --ts = text-protection halo alpha, --tn = 0..1 how far the text colour has risen to full --text
  if (lastTheme !== null && lastTheme !== light) { root.classList.add('flip'); clearTimeout(root._fl); root._fl = setTimeout(() => root.classList.remove('flip'), 80); }
  root.dataset.theme = light ? 'light' : 'dark';
  if (lastTheme !== null && lastTheme !== light) { if (heroApp) setHero(heroApp, true); if (focusTile) setGlow(focusTile); }
  if (user && lastTheme !== null && lastTheme !== light) { const sh = $('shelf'); sh.classList.add('themefade'); clearTimeout(sh._tf); sh._tf = setTimeout(() => sh.classList.remove('themefade'), tk('theme-hero-fade-ms') + 100); }
  lastTheme = light;
  applySky(st); $('greet').textContent = greeting(S.time);
  $('clock').textContent = hhmm(S.time); { const t = todayParts(); $('date').textContent = t.wd + ' ' + t.d + ' ' + t.mon; }
  updateWx(); updateSunNote(); updateDbg();
}

/* ---- weather (mock data, no network; the device calls Open-Meteo, see PLAN Rev 4) ---- */
const GLYPH = {
  sun: '<g class="g-ray"><path d="M24 4v5M24 39v5M4 24h5M39 24h5M9.9 9.9l3.5 3.5M34.6 34.6l3.5 3.5M9.9 38.1l3.5-3.5M34.6 13.4l3.5-3.5"/></g><circle class="g-sun" cx="24" cy="24" r="10.5"/><circle class="g-core" cx="21.5" cy="21.5" r="5.5" opacity=".7"/>',
  moon: '<path class="g-moon" d="M35 31.5A15 15 0 1 1 19.5 8.5 12 12 0 0 0 35 31.5Z"/>',
  cloud: '<path class="g-cloud" d="M13 36H34A7.5 7.5 0 0 0 35.5 21.2 11 11 0 0 0 14.2 19.4 8.9 8.9 0 0 0 13 36Z"/>',
  rain: '<path class="g-cloud" d="M13 31H34A7 7 0 0 0 35.4 17.2 10.5 10.5 0 0 0 14.2 15.6 8.7 8.7 0 0 0 13 31Z"/><path class="g-rain" d="M17 36l-2.4 6M25 36l-2.4 6M33 36l-2.4 6"/>',
  haze: '<circle class="g-sun" cx="24" cy="18" r="9.5" opacity=".85"/><path class="g-haze" d="M8 31h32M12 37h24M17 43h14"/>',
};
function wxCond() { const m = { clear: 'clear', cloudy: 'cloud', cloud: 'cloud', rain: 'rain', fog: 'haze', haze: 'haze' }[S.wxq != null ? S.wxq : Q.get('wx')]; return m || curCity().cond; }   // ?wx=clear|cloudy|rain|fog
/* mock Open-Meteo: hourly precipitation_probability for today. Raining now = the current hour carries the chance; the 16:00-19:00 window carries it when rain is the story (wx=rain or ?rainchance=). */
const rainPct = () => (S.rainChance != null ? S.rainChance : wxCond() === 'rain' ? 70 : 5);
function popAt(h) {
  const story = wxCond() === 'rain' || S.rainChance != null;
  if (wxCond() === 'rain' && h === Math.floor(S.time / 60)) return rainPct();
  return story && h >= 16 && h < 20 ? rainPct() : 5;
}
function rainLikelyHour() { for (let h = Math.floor(S.time / 60) + 1; h < 24; h++) if (popAt(h) >= tk('rain-likely-pct')) return h; return null; }   // first UPCOMING hour today (after the current one: a rain that is already falling is the chip's 'Rain 70%', never announced as a forecast) with chance >= 40
function wxNow() {
  const c = curCity(), sun = S.phase.sun, t = S.time;
  const f = 0.5 + 0.5 * Math.cos(2 * Math.PI * (t - 900) / 1440), c0 = c.lo + (c.hi - c.lo) * f;   // warmest 15:00, coolest 03:00
  const day = sun.rise != null ? t >= sun.rise && t < sun.set : sun.polar === 'day', cond = wxCond();
  return { c: c0, hi: c.hi, lo: c.lo, cond, day, raining: cond === 'rain', pct: rainPct(), glyph: cond === 'clear' ? (day ? 'sun' : 'moon') : cond === 'haze' && !day ? 'moon' : cond };
}
const useF = () => S.units === 'f' || (S.units === 'auto' && curCity().cc === 'US');
const degs = (c) => Math.round(useF() ? c * 9 / 5 + 32 : c) + '°';
function updateWx() {
  const on = S.wxOn && S.wxNet && S.screen !== 'safe';
  $('wx').classList.toggle('on', on);
  if (!on) return;
  const w = wxNow(), sun = S.phase.sun;
  $('wxg').innerHTML = GLYPH[w.glyph]; $('wxt').textContent = degs(w.c);
  $('wxhl').textContent = 'H ' + degs(w.hi) + '  L ' + degs(w.lo);
  $('wxr').textContent = w.raining ? '· Rain ' + w.pct + '%' : '';   // Rev 6: '24° · Rain 70%' while it is raining now
  const aft = sun.rise != null && (S.time >= sun.set || S.time < sun.rise);   // after sunset (or before dawn): the next event is the sunrise
  const rl = rainLikelyHour();
  $('wxc').textContent = rl != null ? 'Rain likely from ' + hhmm(rl * 60) : curCity().name + (sun.set != null ? (aft ? ' · Sunrise ' + hhmm(sun.rise) : ' · Sunset ' + hhmm(sun.set)) : '');
}
const PHASE_LABEL = { night: 'Night', dawn: 'Dawn', day: 'Day', golden: 'Golden hour', dusk: 'Dusk', light: 'Light (fixed)', dark: 'Dark (fixed)' };
function updateSunNote() {
  const sun = S.phase.sun, c = curCity();
  const el = $('sunnote');
  el.innerHTML = sun.rise != null ? `Today in <b>${c.name}</b><br>Sunrise <b>${hhmm(sun.rise)}</b> · Sunset <b>${hhmm(sun.set)}</b><br>Now: <b>${PHASE_LABEL[S.phase.name]}</b> · showing ${root.dataset.theme === 'light' ? 'Light' : 'Dark'}` : `Today in <b>${c.name}</b>: the sun does not ${sun.polar === 'day' ? 'set' : 'rise'}.`;
  $('citynote').innerHTML = `Now: <b>${c.name}</b><br>Sunset <b>${sun.set != null ? hhmm(sun.set) : '–'}</b> · ${hhmm(S.time)} local`;
}
/* debug bar */
/* Rev 6 presets: the clock time at which each sky keyframe holds (found by scanning today's elevation curve), plus the elevation each is read at */
const SKY_KEYS = ['night', 'dawn', 'morning', 'noon', 'afternoon', 'golden', 'sunset', 'dusk'];
const KEY_AT = { dawn: [-6.7, true], sunrise: [-2.4, true], morning: [4, true], afternoon: [18, false], golden: [3, false], horizon: [-2.5, false], sunset: [-5.2, false], dusk: [-11, false], night: [-25, false] };   // hold elevations (inside each keyframe's own window-free hold)
function keyTime(key, sun) {
  if (key === 'noon') return Math.round(sun.noon);
  const [te, rising] = KEY_AT[key]; let best = null, bd = 1e9;
  for (let t = 0; t < 1440; t++) { if ((t < sun.noon) !== rising) continue; const d = Math.abs(sunPos(t).e - te); if (d < bd) { bd = d; best = t; } }
  return best;
}
const dbgPresets = () => { const s = sunToday(); return ['dawn', 'morning', 'noon', 'golden', 'sunset', 'dusk', 'night'].map((k) => [k, keyTime(k, s)]); };
let dbgI = -1;
function updateDbg() {
  $('dbgrange').value = S.time;
  const st = S.phase, sun = st.sun;
  const sk = S.skyState;
  $('dbgphase').textContent = `${hhmm(S.time)}  ${PHASE_LABEL[st.name]}  ·  day ${Math.round(st.p * 100)}%  ·  ${root.dataset.theme}  ·  sun ${sk ? sk.e.toFixed(1) : '?'}° az ${sk ? Math.round(sk.az) : '?'}°  ·  sky ${sk ? (sk.b !== sk.a ? sk.a + '>' + sk.b + ' ' + Math.round(sk.f * 100) + '%' : sk.a) : '-'}${S.sky !== 'auto' ? ' (pinned)' : ''}  ·  ${wxCond()}`;
  $('dbgsun').textContent = sun.rise != null ? `rise ${hhmm(sun.rise)}  ·  set ${hhmm(sun.set)}  ·  T: next preset${dbgI >= 0 ? ' (' + dbgPresets()[dbgI][0] + ')' : ''}  ·  D: rain drops` : 'polar';
  const m = $('dbgm'); m.innerHTML = ''; [sun.rise, sun.set].forEach((x) => { if (x != null) { const i = mk('i', null, m); i.style.left = (x / 1439 * 100) + '%'; } });
}
function setTime(min) { S.time = Math.max(0, Math.min(1439, Math.round(min))); applyTheme(); }
function cycleTime() { const p = dbgPresets(); dbgI = (dbgI + 1) % p.length; $('dbg').classList.add('on'); S.sky = 'auto'; setTime(p[dbgI][1]); }

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
  // Rev 4: the backdrop is built twice (dark + light token sets) and the light one is cross-faded over the dark one by p (CSS: .hb-l opacity var(--p))
  const bls = [], washes = [];
  ['dark', 'light'].forEach((th) => {
    const hbg = mk('div', 'hbg ' + (th === 'dark' ? 'hb-d' : 'hb-l'), layer);
    ['bg', 'hero-leak-alpha', 'hero-dim-left', 'hero-dim-right', 'hero-dim-bottom', 'hero-bl-alpha'].forEach((n) => hbg.style.setProperty('--' + n, tkRaw(n, th)));
    // 1. colour wash: radial from the app's vivid colour (L capped) centred behind the card, to bg
    const wash = mk('div', 'wash', hbg); washes.push([wash, th]);
    // 2. full-bleed pre-blurred banner (hero=blur), filled once we know which file really loaded
    if (S.hero === 'blur') bls.push(mk('div', 'bl', hbg));
    mk('div', 'leak', hbg);
    mk('div', 'dim', hbg);
  });
  const paintWash = () => washes.forEach(([wash, th]) => {
    const T = (n) => parseFloat(tkRaw(n, th));
    const c0 = tone(v, T('hero-lum'), T('hero-sat-max')), c1 = tone(v, T('hero-wash-mid-lum'), T('hero-sat-max'));
    wash.style.background = `radial-gradient(${T('hero-wash-rx') * 100}% ${T('hero-wash-ry') * 100}% at ${T('hero-wash-x') * 100}% ${T('hero-wash-y') * 100}%, ${c0} 0%, ${c1} ${T('hero-wash-mid') * 100}%, var(--bg) 100%)`;
  });
  paintWash();
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
    if (!src) { bls.forEach((x) => x.remove()); return; }
    // near-white banners would bloom grey: keep the vivid-colour glow instead of the blurred banner
    if (!app.white) { g.style.background = ''; g.style.backgroundImage = `url(${src})`; g.style.opacity = ''; }
    bls.forEach((x) => { x.style.backgroundImage = `url(${src})`; });
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
function textW(el) {   // measured text width in canvas px (Range rect / stage scale)
  const r = document.createRange(); r.selectNodeContents(el); const k = $('stage').getBoundingClientRect().width / tk('canvas-w');
  return r.getBoundingClientRect().width / (k || 1);
}
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
  const lb = $('flabel'); lb.textContent = t.app.name; lb.style.setProperty('--fl-w', textW(lb) + 'px');
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
function setList(items, idx) { list.items.forEach((e) => e.classList.remove('focused')); list = { items, idx }; items.forEach((e, i) => e.classList.toggle('focused', i === idx));
  { const h = $('sshint'); if (h) h.classList.toggle('on', S.screen === 'settings' && !!items[idx] && items[idx]._k === 'saver'); }
  if (S.screen === 'settings') listScroll('swin', 'slist', idx, items.length); else if (S.screen === 'city') listScroll('cwin', 'clist', idx, items.length); }
/* scrolling lists (Settings 15 rows, City 12 rows): minimal scroll that keeps the focused row and one neighbour in view; translate only */
function listScroll(winId, lstId, idx, n) {
  const win = $(winId), lst = $(lstId), P = tk('list-row-h') + tk('list-row-gap'), view = tk('list-view-h'), total = n * P - tk('list-row-gap');
  let off = +(lst.dataset.off || 0);
  const top = idx * P, bot = top + tk('list-row-h'), padT = idx > 0 ? P : 0, padB = idx < n - 1 ? P : 0;
  if (top - padT < off) off = top - padT;
  if (bot + padB > off + view) off = bot + padB - view;
  off = Math.max(0, Math.min(Math.max(0, total - view), off));
  lst.dataset.off = off; lst.style.transform = `translateY(${-off}px)`;
  win.classList.toggle('top', off <= 0); win.classList.toggle('bottom', off >= total - view - 0.5);
}
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
  else if (i === 2) openDream('panel');
  else toast('Opens Fire TV Settings (mock)');
}
function setVeil(kind) { const v = $('veil'); v.className = kind ? 'on ' + kind : ''; }

/* ---------- Rev 5: screensaver preview (ClockDream). Device twin: ClockDream.java, one custom View, see PLAN Rev 5 ---------- */
const DREAM_SHIFTS = [[0, 0], [12, -6], [-12, 6], [6, 12], [-6, -12], [12, 10], [-10, -4], [4, -10], [-4, 10], [10, 2], [-12, -10], [8, -12]];   // all inside +-12 px = the 24 px box; device: same table, index = minute-of-epoch % 12
const dreamFixed = Q.has('dreamshift') || Q.has('dreamdim');   // a pinned preview (shots, tests): no minute tick, no dim timer
let dreamT = 0, dreamDimT = 0, dreamPrev = 'home', dreamIdx = 0;
function dreamLine() {
  const t = todayParts(); let s = t.wd + ' ' + t.d + ' ' + t.mon;
  if (S.wxOn && S.wxNet) {   // weather part hidden when weather is off or offline
    const w = wxNow(), rl = rainLikelyHour();
    s += ' · ' + degs(w.c) + (w.raining ? ' · Rain ' + w.pct + '%' : rl != null ? ' · Rain from ' + hhmm(rl * 60) : ' ' + curCity().name);   // Rev 6: 'Tue 20 Oct · 24° · Rain 70%' when it rains now, '... · Rain from 16:00' when it is coming
  }
  return s;
}
function drawDream() {
  const live = S.live ? (() => { const c = curCity(), p = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: c.tz, hourCycle: 'h23', hour: 'numeric', minute: 'numeric' }).formatToParts(new Date()).map((x) => [x.type, +x.value])); return p.hour * 60 + p.minute; })() : null;
  if (live != null) S.time = live;
  $('dtime').innerHTML = hhmm(S.time).replace(/\d/g, '<i>$&</i>').replace(':', '<b>:</b>');   // one fixed-width cell per glyph, proportional figure centred in it (no tabular-one foot slab, no jump)
  $('dline').textContent = dreamLine();
  const [dx, dy] = DREAM_SHIFTS[((dreamIdx % DREAM_SHIFTS.length) + DREAM_SHIFTS.length) % DREAM_SHIFTS.length];
  const g = $('dgroup'); g.style.setProperty('--dx', dx); g.style.setProperty('--dy', dy);
}
function dreamTick() { dreamIdx = Math.floor(Date.now() / 60000); drawDream(); dreamT = setTimeout(dreamTick, 60000 - (Date.now() % 60000) + 20); }   // postDelayed to the next minute boundary: once a minute, no Choreographer
function openDream(from) {
  dreamPrev = from || S.screen; S.screen = 'dream'; setScreenAttr(); clearTimeout(idleT);
  const g = $('dgroup'); g.classList.add('nofade'); g.classList.toggle('dim', Q.get('dreamdim') === '1'); void g.offsetWidth; g.classList.remove('nofade');
  dreamIdx = Q.has('dreamshift') ? (parseInt(Q.get('dreamshift'), 10) || 0) : Math.floor(Date.now() / 60000);
  drawDream(); $('dream').classList.add('open');
  if (!dreamFixed) { dreamT = setTimeout(dreamTick, 60000 - (Date.now() % 60000) + 20); dreamDimT = setTimeout(() => $('dgroup').classList.add('dim'), tk('dream-dim-min') * 60000); }
}
function closeDream() {
  clearTimeout(dreamT); clearTimeout(dreamDimT); $('dream').classList.remove('open'); $('dgroup').classList.remove('dim');
  S.screen = dreamPrev; setScreenAttr(); if (S.screen === 'home') wake();
}
$('dream').addEventListener('click', () => { if (S.screen === 'dream') closeDream(); });   // any key (or a click) wakes: exactly like the real screensaver

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
const UNITS_LABEL = { auto: 'Auto', c: '°C', f: '°F' };
const SET_ROWS = [
  { k: 'theme',  label: 'Theme',              get: () => ({ auto: 'Auto (sunset)', light: 'Light', dark: 'Dark' })[S.theme], raw: () => S.theme, opts: ['auto', 'light', 'dark'], set: (v) => { S.theme = v; applyTheme(true); } },
  { k: 'weather',label: 'Weather',            tog: () => S.wxOn, flip: () => { S.wxOn = !S.wxOn; updateWx(); } },
  { k: 'units',  label: 'Units',              get: () => UNITS_LABEL[S.units] + (S.units === 'auto' ? (useF() ? ' (°F)' : ' (°C)') : ''), raw: () => S.units, opts: ['auto', 'c', 'f'], set: (v) => { S.units = v; updateWx(); } },
  { k: 'city',   label: 'City',               val: () => curCity().name, go: () => showCities() },
  { k: 'saver',  label: 'Screensaver',        val: () => 'Preview', go: () => openDream('settings') },
  { k: 'cols',   label: 'Columns',            get: () => String(S.cols), opts: ['5', '6'], set: (v) => { S.cols = +v; S.r = 0; S.c = 0; S.wantC = 0; buildHome(); } },
  { k: 'clock',  label: 'Clock',              tog: () => S.clock, flip: () => { S.clock = !S.clock; applyChrome(); } },
  { k: 'card',   label: 'Shelf card',         get: () => S.card, opts: ['emblem', 'banner', 'off'], set: (v) => { S.card = v; applyChrome(); setHero(heroApp, true); } },
  { k: 'hero',   label: 'Hero style',         get: () => S.hero, opts: ['wash', 'blur'], set: (v) => { S.hero = v; setHero(heroApp, true); } },
  { k: 'art',    label: 'Tile art',           get: () => S.art, opts: ['real', 'placeholder'], set: (v) => { S.art = v; buildHome(); setHero(heroApp, true); } },
  { k: 'labels', label: 'Tile labels',        get: () => (S.labels === 'always' ? 'Always' : 'Focused only'), opts: ['focus', 'always'], raw: () => S.labels, set: (v) => { S.labels = v; applyChrome(); } },
  { k: 'motion', label: 'Reduced motion',     tog: () => S.reduced, flip: () => setReduced(!S.reduced) },
  { k: 'wall',   label: 'Wallpaper',          get: () => S.wall, opts: ['sky', 'dusk', 'ember', 'fog'], set: (v) => { S.wall = v; applyChrome(); applyTheme(); } },
  { k: 'fth',    label: 'Fire TV Home tile',  tog: () => S.fth, flip: () => { S.fth = !S.fth; buildHome(); } },
  { k: 'reset',  label: 'Return to Fire TV…', go: () => openSheet('settings') },
  { k: 'safe',   label: 'Safe mode preview',  go: () => showSafe('settings') },
];
function buildSettings() {
  const host = $('slist'); host.innerHTML = '';
  SET_ROWS.forEach((d) => {
    const row = mk('div', 'row', host); row._k = d.k; mk('span', null, row, d.label); const val = mk('span', 'val', row); d.rowEl = row; d.valEl = val;
  });
  refreshSettings();
}
function refreshSettings() {
  SET_ROWS.forEach((d) => {
    d.valEl.innerHTML = '';
    if (d.tog) mk('span', 'tog' + (d.tog() ? ' on' : ''), d.valEl);
    else if (d.val) d.valEl.textContent = d.val() + '  ›';
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

/* ---------- City list (Settings -> City): D-pad list, Select sets it, Back returns ---------- */
let cityRows = [];
const CHECK = '<svg class="ck" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
function buildCities() {
  const host = $('clist'); host.innerHTML = ''; cityRows = [];
  const { y, m, d } = todayParts();
  CITIES.forEach((c, i) => {
    const row = mk('div', 'row', host), lab = mk('span', 'lab', row); mk('span', null, lab, c.name);
    const val = mk('span', 'val', row); const sun = Sun.times(c.lat, c.lon, y, m, d, tzOffsetH(c.tz, y, m, d));
    row._c = i; row._sun = sun; row._val = val; cityRows.push(row);
  });
  refreshCities();
}
function refreshCities() {
  cityRows.forEach((row, i) => { row._val.innerHTML = ''; if (i === S.city) row._val.insertAdjacentHTML('beforeend', CHECK); mk('span', 'dim', row._val, 'Sunset ' + hhmm(row._sun.set)); });
}
function showCities() {
  S.screen = 'city'; setScreenAttr(); S._settingsIdx = list.idx;
  $('settings').classList.remove('open'); $('cities').classList.add('open');
  setList(cityRows, S.city);
}
function pickCity() { S.city = list.idx; refreshCities(); applyTheme(); hideCities(); }   // Select sets and returns; the Settings City row value confirms (no toast)
function hideCities() { $('cities').classList.remove('open'); S.screen = 'settings'; $('settings').classList.add('open'); showSettings(true); refreshSettings(); }

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
  const legacy = S.wall === 'sky' ? 'dusk' : S.wall;   // Rev 6: 'sky' follows the sun in Theme = Auto; a fixed Light / Dark theme keeps the Rev 4 static walls (a fixed Light on a night sky would put dark text on navy)
  const w = $('wall'); w.style.backgroundImage = `url(wall/${legacy}.webp)`;
  root.style.setProperty('--wallurl', `url(wall/${legacy}.webp)`); root.style.setProperty('--wallurl-day', 'url(wall/day.webp)'); $('wallday').style.backgroundImage = 'url(wall/day.webp)';   // every glass slice samples the (pre-blurred) wallpaper only
  document.body.classList.toggle('card-off', S.card === 'off');
  const tint = { dusk: ['#2B2E5C', '#15161C'], ember: ['#4A1418', '#0B0B0D'], fog: ['#3A4452', '#0C0D10'] }[legacy];
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
    case 'city': hideCities(); break;
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
    case 'city': pickCity(); break;
    case 'safe': if (list.idx === 0) toast('Opens Amazon’s home (mock)'); else hideSafe(); break;
  }
}
function onDir(dx, dy) {
  switch (S.screen) {
    case 'home': moveHome(dx, dy); break;
    case 'panel': case 'sheet': if (dx) moveList(dx, true); else if (dy < 0 && S.screen === 'panel') {} break;
    case 'settings': if (dy) moveList(dy, false); else changeSetting(dx); break;
    case 'city': if (dy) moveList(dy, false); break;
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
  if (S.screen === 'dream') { if (!e.repeat) closeDream(); e.preventDefault(); return; }   // any key wakes the screensaver; the key itself is consumed
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
  else if (k === 't' || k === 'T') { if (!e.repeat) { if (e.shiftKey) $('dbg').classList.remove('on'); else cycleTime(); } }
  else if (k === 'r' || k === 'R') { if (!e.repeat) setReduced(!S.reduced); }
  else if (k === 'd' || k === 'D') { if (!e.repeat) { S.drops = !dropsOn(); updateRain(); toast('Rain drops ' + (S.drops ? 'on' : 'off') + ' (review)'); } }
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
  S.city = Math.max(0, CITIES.findIndex((c) => c.id === (Q.get('city') || 'bengaluru')));
  if (S.time == null) S.time = S.sky !== 'auto' ? keyTime(S.sky, sunToday()) : tk('time-default');
  S.live = S.live || false;
  if (S.live) { const c = curCity(), p = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: c.tz, hourCycle: 'h23', hour: 'numeric', minute: 'numeric' }).formatToParts(new Date()).map((x) => [x.type, +x.value])); S.time = p.hour * 60 + p.minute; }
  fit(); applyChrome(); buildPanel(); buildCities(); buildSettings(); applyTheme(); buildHome();
  $('dbgrange').addEventListener('input', (e) => { setTime(+e.target.value); e.target.blur(); });
  if (Q.get('debug') === '1') $('dbg').classList.add('on');
  if (S.live) setInterval(() => { const c = curCity(), p = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: c.tz, hourCycle: 'h23', hour: 'numeric', minute: 'numeric' }).formatToParts(new Date()).map((x) => [x.type, +x.value])); setTime(p.hour * 60 + p.minute); }, tk('theme-tick-s') * 1000);
  setTimeout(() => root.classList.remove('boot'), 120);
  const want = Q.get('screen') || 'home';
  const fo = (Q.get('focus') || '').split(',').map(Number);   // [P4] ?focus=R,C: open with that tile focused (instant, normal setFocus path)
  if (fo.length === 2 && fo.every(Number.isInteger) && rows[fo[0]] && rows[fo[0]][fo[1]]) { S.wantC = fo[1]; setFocus(fo[0], fo[1], true); }
  if (want === 'panel') openPanel();
  else if (want === 'reset') openSheet('home');
  else if (want === 'settings') showSettings();
  else if (want === 'city') { showSettings(); list.idx = SET_ROWS.findIndex((d) => d.k === 'city'); showCities(); }
  else if (want === 'safe') showSafe(null);
  else if (want === 'dream') openDream('home');
  setScreenAttr(); wake();
  window.__orchard = { S, setTime, setWx: (v) => { S.wxq = v; applyTheme(); }, skyReady: () => Promise.all(['skA', 'skB', 'skAo', 'skBo'].map((id) => { const i = new Image(); i.src = $(id).style.backgroundImage.slice(5, -2); return i.decode().catch(() => {}); })), sunPos, skyAt, keyTime, greeting, wxSky, rainLikelyHour, popAt, get focus() { return document.body.dataset.focus; }, Sun, CITIES, sunToday, phaseAt, hhmm, tzOffsetH };
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { document.body.dataset.fonts = 'ready'; });
}
/* a fresh clone has no banners-real/ (git-ignored): probe once and default to the placeholders so the console stays clean */
if (S.art === 'real' && !Q.get('art') && location.protocol !== 'file:' && window.ORCHARD_REAL_ART !== true) {
  fetch('banners-real/manifest.json', { method: 'HEAD' }).then((r) => { if (!r.ok) S.art = 'placeholder'; }, () => { S.art = 'placeholder'; }).then(boot);
} else boot();
})();
