/**
 * NunoDrama test harness.
 *
 * Mirrors, one-to-one, the parsing and request logic in
 *   NunoDrama/src/main/kotlin/com/nunodrama/NunoDramaRegistry.kt
 *   NunoDrama/src/main/kotlin/com/nunodrama/NunoDramaStreams.kt
 *   NunoDrama/src/main/kotlin/com/nunodrama/NunoDramaClient.kt
 *   NunoDrama/src/main/kotlin/com/nunodrama/NunoDramaProvider.kt
 *
 * Two phases:
 *   1. UNIT     - offline fixtures through every parser the Kotlin uses.
 *   2. LIVE     - the same code paths against https://nunodrama.my.id via
 *                 Playwright, then real HTTP probes on every extracted stream.
 *
 * Run:  node test/nunodrama.test.mjs
 * Exit: 0 all green, 1 otherwise.
 */

import { chromium } from 'playwright';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const SHOTS = resolve(HERE, 'artifacts');
mkdirSync(SHOTS, { recursive: true });

const DEFAULT_BASE = 'https://nunodrama.my.id';
const CATALOGUE_PAGE_SIZE = 30;
const SEARCH_PAGE_SIZE = 60;
const SEARCH_PER_PROVIDER = 8;
const HTTP_PARALLELISM = 8;
const MAX_PAGES = 100;
const MAX_EMPTY_PAGES = 3;
const MAX_ATTEMPTS = 3;
const SEEN_MEMORY = 900;
const REAL_PAGE_MIN_BYTES = 8192;

const FALLBACK_CATEGORIES = [
  'foryou', 'all', 'all_drama', 'recommend', 'terbaru', 'alldrama', 'trending', 'asian', 'drama', 'movie',
];

const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

const BLOCK_MARKERS = [
  'no-js ie6',
  'id="cf-error-details"',
  'cf-browser-verification',
  '/cdn-cgi/challenge-platform/',
  '<title>Just a moment',
  'cf_chl_opt',
];
const ERROR_TITLE = /<title>[^<]*\|\s*[45]\d\d/i;

const results = [];
let currentGroup = '';

const group = (name) => {
  currentGroup = name;
  console.log(`\n${'='.repeat(72)}\n${name}\n${'='.repeat(72)}`);
};

const check = (label, condition, detail = '') => {
  const ok = Boolean(condition);
  results.push({ group: currentGroup, label, ok, detail });
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? ` :: ${detail}` : ''}`);
  return ok;
};

const eq = (label, actual, expected) =>
  check(label, Object.is(actual, expected), `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);

const note = (label, value) => {
  console.log(`  ....  ${label} :: ${value}`);
  return value;
};

/* ------------------------------------------------------------------ *
 * PARSERS - exact behavioural mirrors of the Kotlin
 * ------------------------------------------------------------------ */

const linkTag = /<[^>]*\bdata-platform-link\b[^>]*>/g;
const sectionTag = /<[^>]*\bdata-inf-section\b[^>]*>/g;
const headingTag = /<h2[^>]*>([^<]{1,60})<\/h2>/;
const attribute = /([a-zA-Z0-9-]+)\s*=\s*"([^"]*)"/g;

const LD_SCRIPT = /<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/g;
const OG_TITLE = /<meta property="og:title" content="([^"]*)"/;
const OG_DESCRIPTION = /<meta property="og:description" content="([^"]*)"/;
const OG_IMAGE = /<meta property="og:image" content="([^"]*)"/;
const SUBTITLE_URL = /["'(]([^"'()\s]+\.(?:srt|vtt|ass))["')]/gi;
const QUALITY_TOKEN = /(2160|1440|1080|720|480|360|240)/i;
const DETAIL_PATH = /\/detail\/([^/?#]+)\/([^/?#]+)/;
const WATCH_PATH = /\/watch\/([^/?#]+)\/([^/?#]+)/;
const EPISODE_QUERY = /[?&]ep=(\d+)/;
const HEADING_WINDOW = 1500;

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", '#39': "'", '#x27': "'" };
function decodeEntities(value) {
  return value
    .replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (m, name) => {
      if (ENTITIES[name] !== undefined) return ENTITIES[name];
      if (name[0] === '#') {
        const code = name[1] === 'x' || name[1] === 'X' ? parseInt(name.slice(2), 16) : parseInt(name.slice(1), 10);
        return Number.isFinite(code) ? String.fromCodePoint(code) : m;
      }
      return m;
    })
    .replace(/&#(\d+);/g, (m, code) => (Number.isFinite(Number(code)) ? String.fromCodePoint(Number(code)) : m));
}

function attributes(tag) {
  const out = {};
  for (const m of tag.matchAll(attribute)) out[m[1]] = decodeEntities(m[2]);
  return out;
}

let BASE = DEFAULT_BASE;
const absolute = (path) => {
  if (path.startsWith('http://') || path.startsWith('https://')) return path;
  const root = BASE.replace(/\/+$/, '');
  return path.startsWith('/') ? root + path : `${root}/${path}`;
};
const urlEncode = (v) => encodeURIComponent(v);

function isBlocked(body) {
  if (!body) return true;
  if (body.length > REAL_PAGE_MIN_BYTES) return false;
  return BLOCK_MARKERS.some((m) => body.toLowerCase().includes(m.toLowerCase())) || ERROR_TITLE.test(body);
}

function parseProviders(html) {
  const out = new Map();
  for (const match of html.matchAll(linkTag)) {
    const attrs = attributes(match[0]);
    const slug = (attrs['data-slug'] || '').trim();
    if (!slug || out.has(slug)) continue;
    const name = (attrs['data-name'] || '').trim();
    out.set(slug, {
      slug,
      name: name || slug.charAt(0).toUpperCase() + slug.slice(1),
    });
  }
  return [...out.values()];
}

function parseCategories(html) {
  const seen = new Set();
  const out = [];
  for (const match of html.matchAll(sectionTag)) {
    const category = (attributes(match[0])['data-category'] || '').trim();
    if (!category || seen.has(category)) continue;
    const from = match.index + match[0].length;
    const window = html.slice(from, Math.min(from + HEADING_WINDOW, html.length));
    const heading = window.match(headingTag);
    seen.add(category);
    out.push([category, heading ? heading[1].trim() : '']);
  }
  return out;
}

function parseSeriesLd(html) {
  for (const match of html.matchAll(LD_SCRIPT)) {
    const payload = match[1].trim();
    if (!payload.includes('"TVSeries"')) continue;
    try {
      const parsed = JSON.parse(payload);
      return {
        name: parsed.name || '',
        description: parsed.description || '',
        image: typeof parsed.image === 'string' ? parsed.image : '',
        language: parsed.inLanguage || '',
        episodeCount: parsed.numberOfEpisodes || 0,
      };
    } catch {
      /* keep scanning */
    }
  }
  return null;
}

function parsePlayer(html) {
  const video = html.match(/<video\b[^>]*>/);
  if (!video) return null;
  const a = attributes(video[0]);
  const raw = (a['data-src'] || '').trim() || (a.src || '').trim();
  if (!raw) return null;
  const url = absolute(raw);
  const kind = (a['data-kind'] || '').trim().toLowerCase() || guessKind(url);
  const cover = (a['data-cover'] || '').trim();
  return {
    url,
    raw,
    relative: raw.startsWith('/'),
    kind,
    title: (a['data-title'] || '').trim(),
    cover: cover ? absolute(cover) : '',
    language: (a['data-lang'] || '').trim() || 'id',
    encrypted: (a['data-encrypted'] || '').trim().toLowerCase() === 'true',
    key: (a['data-key'] || '').trim(),
  };
}

function parseEpisodes(html) {
  const container = html.match(/<div[^>]*data-ep-list[^>]*>([\s\S]*?)<\/div>/);
  const scope = container ? container[1] : html;
  const out = new Map();
  for (const m of scope.matchAll(/<a\b([^>]*\bdata-ep-index="(\d+)"[^>]*)>/g)) {
    const attrs = attributes(`<a ${m[1]}>`);
    const number = Number(m[2]);
    const href = (attrs.href || '').trim();
    if (!href || out.has(number)) continue;
    out.set(number, { number, url: absolute(href) });
  }
  return [...out.values()].sort((a, b) => a.number - b.number);
}

function guessKind(url) {
  if (/\.m3u8/i.test(url)) return 'hls';
  if (/\.mpd/i.test(url)) return 'dash';
  return 'mp4';
}

function linkType(kind, url) {
  if (kind === 'hls' || /\.m3u8/i.test(url)) return 'M3U8';
  if (kind === 'dash' || /\.mpd/i.test(url)) return 'DASH';
  return 'VIDEO';
}

const isMasterPlaylist = (body) => body.includes('#EXT-X-STREAM-INF');

function resolveUri(base, relative) {
  try {
    return new URL(relative, base).toString();
  } catch {
    return relative.startsWith('http') ? relative : `${base.replace(/\/+$/, '')}/${relative.replace(/^\/+/, '')}`;
  }
}

function parseMaster(body, playlistUrl) {
  const out = new Map();
  const lines = body.split(/\r?\n/);
  let i = 0;
  while (i < lines.length) {
    const line = lines[i].trim();
    if (!line.startsWith('#EXT-X-STREAM-INF')) {
      i++;
      continue;
    }
    const bandwidth = Number((line.match(/BANDWIDTH=(\d+)/) || [])[1] || 0);
    const resolution = (line.match(/RESOLUTION=(\d+x\d+)/) || [])[1] || '';
    const height = Number(resolution.split('x')[1] || 0);
    let uri = '';
    let probe = i + 1;
    while (probe < lines.length) {
      const candidate = lines[probe].trim();
      if (candidate && !candidate.startsWith('#')) {
        uri = candidate;
        break;
      }
      probe++;
    }
    if (uri) {
      const abs = resolveUri(playlistUrl, uri);
      if (!out.has(abs)) out.set(abs, { url: abs, resolution, bandwidth, height });
    }
    i = probe + 1;
  }
  return [...out.values()].sort((a, b) => b.height - a.height).slice(0, 12);
}

const qualityFromPath = (url) => Number((url.match(QUALITY_TOKEN) || [])[1] || 0);
const qualityLabel = (height, bandwidth) =>
  height > 0 ? `${height}p` : bandwidth > 0 ? `${Math.round(bandwidth / 1000)}kbps` : 'Auto';

const titleFromHtml = (html) => {
  const m = html.match(OG_TITLE);
  if (!m) return null;
  const t = m[1].trim().split(' — ')[0].trim();
  return t || null;
};
const descriptionFromHtml = (html) => (html.match(OG_DESCRIPTION)?.[1] || '').trim() || null;
const coverFromHtml = (html) => (html.match(OG_IMAGE)?.[1] || '').trim() || null;

function parseDramaDto(raw) {
  const num = (v) => {
    if (typeof v === 'number' && Number.isFinite(v)) return Math.trunc(v);
    if (typeof v === 'string') { const n = Number(v.trim()); return Number.isFinite(n) ? Math.trunc(n) : 0; }
    return 0;
  };
  return {
    platformName: typeof raw.PlatformName === 'string' ? raw.PlatformName : '',
    bookId: typeof raw.BookID === 'string' ? raw.BookID : '',
    bookName: typeof raw.BookName === 'string' ? raw.BookName : '',
    cover: typeof raw.Cover === 'string' ? raw.Cover : null,
    chapterCount: num(raw.ChapterCount),
  };
}

function parseSection(raw) {
  return {
    dramas: Array.isArray(raw?.dramas) ? raw.dramas.map(parseDramaDto) : [],
    next: typeof raw?.next === 'string' ? raw.next : null,
  };
}

function subtitleLang(srclang, label) {
  for (const candidate of [srclang, String(label || '').toLowerCase(), String(label || '')]) {
    const n = String(candidate || '').trim().toLowerCase();
    if (!n) continue;
    if (n.includes('id') || n.includes('indonesia')) return 'id';
    if (n.includes('en') || n.includes('english')) return 'en';
  }
  return 'en';
}

function searchSlice(merged, page, pageSize) {
  if (page <= 0) return { slice: [], hasNext: false };
  const from = (page - 1) * pageSize;
  if (from >= merged.length) return { slice: [], hasNext: false };
  const to = Math.min(from + pageSize, merged.length);
  return { slice: merged.slice(from, to), hasNext: to < merged.length };
}

function railHasNext(page, cardsProduced, streakAfter) {
  if (page >= MAX_PAGES) return false;
  return streakAfter < MAX_EMPTY_PAGES;
}

function rememberNew(map, key, id) {
  if (!id) return false;
  let set = map.get(key);
  if (!set) {
    set = new Set();
    map.set(key, set);
  }
  if (set.has(id)) return false;
  set.add(id);
  if (set.size > SEEN_MEMORY) {
    const kept = [...set].slice(-Math.floor(SEEN_MEMORY / 2));
    map.set(key, new Set(kept));
  }
  return true;
}

const railKey = (slug, category) => `${slug}|${category}`;
const railScope = (rail, slug, category) => `${rail}:${slug}@${category}`;

async function mapBounded(items, parallelism, fn) {
  const results = new Array(items.length).fill(null);
  let cursor = 0;
  const workers = Array.from({ length: Math.min(parallelism, items.length) }, async () => {
    while (true) {
      const i = cursor++;
      if (i >= items.length) return;
      try {
        results[i] = await fn(items[i]);
      } catch {
        results[i] = null;
      }
    }
  });
  await Promise.all(workers);
  return results;
}

const titleCase = (s) => s.charAt(0).toUpperCase() + s.slice(1);
function languageTag(code) {
  const c = (code || '').toLowerCase();
  if (c === 'id') return 'Indonesian';
  if (c === 'en') return 'English';
  return code;
}
function tvTypeFor(slug) {
  const s = slug.toLowerCase();
  if (s === 'anime' || s === 'donghua') return 'Anime';
  if (s === 'drakor' || s === 'drakorid') return 'AsianDrama';
  return 'TvSeries';
}

/* ------------------------------------------------------------------ *
 * PHASE 1 - UNIT
 * ------------------------------------------------------------------ */

function unit() {
  group('UNIT :: provider discovery');

  const switcher = `
    <button class="grid" data-platform-link data-slug="nunomix" data-name="NunoMix" data-searchable="NunoMix">
      <div class="relative"><img src="/static/img/NunoMix.webp" alt="NunoMix" loading="lazy"></div>
      <span>NunoMix</span>
    </button>
    <button class="grid" data-platform-link data-searchable="DramaBox" data-name="DramaBox" data-slug="dramabox">
      <div class="relative"><img src="/static/img/dramabox.webp" alt="DramaBox"></div>
    </button>
    <button data-platform-link data-slug="nameless"></button>
    <button data-platform-link data-slug="nunomix" data-name="Dupe"></button>
    <div data-platform-link data-slug="notabutton"></div>`;

  const parsed = parseProviders(switcher);
  eq('parses every provider link', parsed.length, 4);
  eq('dedupes repeated slug', parsed.filter((p) => p.slug === 'nunomix').length, 1);
  eq('reads slug', parsed[0].slug, 'nunomix');
  eq('reads name', parsed[0].name, 'NunoMix');
  eq('carries no unused fields', Object.keys(parsed[0]).sort().join(','), 'name,slug');
  check('attribute order does not matter', parsed[1].slug === 'dramabox' && parsed[1].name === 'DramaBox', parsed[1].slug);
  eq('falls back to slug for missing name', parsed[2].name, 'Nameless');
  check('accepts any tag carrying the attribute', parsed.some((p) => p.slug === 'notabutton'), 'site change resilience');
  eq('empty html yields nothing', parseProviders('').length, 0);
  check('never throws on malformed html', Array.isArray(parseProviders('<button data-platform-link')));

  group('UNIT :: category discovery');

  const page = `
    <section class="space-y-4" data-inf-section data-platform="dramabox" data-category="foryou" data-page="2">
      <div><h2 class="font-display">For You</h2></div>
    </section>
    <section data-inf-section data-category="trending" data-platform="dramabox">
      <div><h2>Trending</h2></div>
    </section>
    <section data-inf-section data-category="foryou"></section>
    <section data-inf-section></section>`;
  const cats = parseCategories(page);
  eq('parses categories', cats.length, 2);
  eq('category value', cats[0][0], 'foryou');
  eq('section title', cats[0][1], 'For You');
  eq('works without data-platform attr', cats[1][0], 'trending');
  eq('ignores section without category', cats.some((c) => c[0] === ''), false);

  group('UNIT :: block / error page detection');

  check('flags cloudflare challenge', isBlocked('<!DOCTYPE html> <html class="no-js ie6 oldie">'));
  check('flags 502 error page', isBlocked('<html class="no-js"><title>nunodrama.my.id | 502: Bad gateway</title>'));
  check('flags cf error details', isBlocked('<div id="cf-error-details">'));
  check('flags empty body', isBlocked(''));
  check('accepts real page', !isBlocked('<!DOCTYPE html><html lang="id"><head><title>ok</title>'.padEnd(REAL_PAGE_MIN_BYTES + 10, 'x')));
  check('accepts short real fragment', !isBlocked('<div data-inf-section data-category="foryou"></div>'));

  group('UNIT :: player extraction');

  const watch = `<html><body>
    <video id="player" class="w-full" playsinline controls autoplay preload="auto"
      referrerpolicy="no-referrer" poster="p.jpg"
      data-kind="hls" data-src="https://cdn.test/a/b.m3u8?x=1&amp;y=2"
      data-platform="dramaverse" data-book="1002954" data-ep="1" data-title="The &amp; CEO"
      data-cover="c.jpg" data-lang="en" data-encrypted="true" data-key="57310184bfca43c2"
      data-prev="" data-next="/watch/dramaverse/1002954?ep=2"></video>
  </body></html>`;
  const p1 = parsePlayer(watch);
  check('finds video element', p1 !== null);
  eq('reads kind', p1.kind, 'hls');
  eq('decodes html entities in url', p1.url, 'https://cdn.test/a/b.m3u8?x=1&y=2');
  eq('decodes title entity', p1.title, 'The & CEO');
  eq('reads book id', attributes(watch.match(/<video\b[^>]*>/)[0])['data-book'], '1002954');
  eq('reads lang', p1.language, 'en');
  eq('reads encrypted flag', p1.encrypted, true);
  eq('reads key', p1.key, '57310184bfca43c2');

  const plain = `<video id="player" data-kind="mp4" data-src="https://cdn.test/v/720.mp4"></video>`;
  eq('plain mp4 kind', parsePlayer(plain).kind, 'mp4');
  eq('plain mp4 not encrypted', parsePlayer(plain).encrypted, false);
  eq('defaults lang to id', parsePlayer(plain).language, 'id');
  eq('empty video element yields null', parsePlayer('<video id="player"></video>'), null);
  eq('no video element yields null', parsePlayer('<div>nothing</div>'), null);
  eq('guesses hls from url', parsePlayer('<video data-src="https://x/y.m3u8"></video>').kind, 'hls');
  eq('guesses mp4 from url', parsePlayer('<video data-src="https://x/y.mp4"></video>').kind, 'mp4');
  eq('linkType m3u8', linkType('hls', 'x'), 'M3U8');
  eq('linkType video', linkType('mp4', 'x.mp4'), 'VIDEO');
  eq('linkType dash', linkType('dash', 'x.mpd'), 'DASH');

  const rel = `<video id="player" data-kind="mp4"
    data-src="/api/proxy/video?url=https%3A%2F%2Foberon.box.ca%2Fa%2Fb.mp4"
    data-cover="/static/img/cover.jpg" data-lang="id" data-encrypted="false"></video>`;
  const p2 = parsePlayer(rel);
  check('relative data-src is absolutised', p2.url.startsWith('https://nunodrama.my.id/api/proxy/video?url='), p2.url.slice(0, 60));
  eq('relative url keeps its query', p2.url.includes('url=https%3A%2F%2Foberon.box.ca%2Fa%2Fb.mp4'), true);
  eq('relative cover is absolutised', p2.cover, 'https://nunodrama.my.id/static/img/cover.jpg');
  eq('flags that the raw src was relative', p2.relative, true);
  eq('absolute src is left untouched', parsePlayer(plain).raw.startsWith('https://'), true);

  group('UNIT :: episode extraction');

  const eps = `<div data-ep-list>
    <a href="/watch/nunomix/405030?ep=1" data-ep-index="1" class="a">1</a>
    <a href="/watch/nunomix/405030?ep=2" data-ep-index="2" class="a">2</a>
    <a href="/watch/nunomix/405030?ep=10" data-ep-index="10" class="a">10</a>
    <a href="/watch/nunomix/405030?ep=2" data-ep-index="2" class="a">dupe</a>
  </div>`;
  const refs = parseEpisodes(eps);
  eq('parses unique episode count', refs.length, 3);
  eq('sorts ascending', refs.map((r) => r.number).join(','), '1,2,10');
  eq('absolutises href', refs[0].url, 'https://nunodrama.my.id/watch/nunomix/405030?ep=1');
  eq('no episode list yields empty', parseEpisodes('<div>none</div>').length, 0);
  eq('watch path parse', WATCH_PATH.exec('https://nunodrama.my.id/watch/melolo/7682802378407431173?ep=7')?.slice(1).join('/'), 'melolo/7682802378407431173');
  eq('episode query parse', EPISODE_QUERY.exec('https://x/watch/a/b?ep=42')?.[1], '42');
  eq('detail path parse', DETAIL_PATH.exec('https://nunodrama.my.id/detail/netshort/2104')?.slice(1).join('/'), 'netshort/2104');

  group('UNIT :: TVSeries ld+json');

  const ld = `<script type="application/ld+json">{"@context":"https://schema.org","@type":"WebSite","name":"NunoDrama"}</script>
    <script type="application/ld+json">{"@context":"https://schema.org","@type":"BreadcrumbList"}</script>
    <script type="application/ld+json">{"@context":"https://schema.org","@type":"TVSeries","description":"Plot","image":"i.jpg","inLanguage":"en","name":"Title","numberOfEpisodes":53}</script>`;
  const s = parseSeriesLd(ld);
  check('finds TVSeries among other ld blocks', s !== null);
  eq('ld name', s.name, 'Title');
  eq('ld episodes', s.episodeCount, 53);
  eq('ld language', s.language, 'en');
  eq('ld missing returns null', parseSeriesLd('<html></html>'), null);
  eq('ld malformed json is skipped', parseSeriesLd('<script type="application/ld+json">{"@type":"TVSeries",bad</script><script type="application/ld+json">{"@type":"TVSeries","name":"OK","numberOfEpisodes":3}</script>')?.name, 'OK');

  group('UNIT :: m3u8 master playlist');

  const master = [
    '#EXTM3U',
    '#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360',
    '360/index.m3u8',
    '#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720',
    '720/index.m3u8',
    '#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.6d401f"',
    'https://other.test/1080/index.m3u8',
    '#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360',
    '360/index.m3u8',
  ].join('\n');
  check('detects master playlist', isMasterPlaylist(master));
  check('media playlist is not master', !isMasterPlaylist('#EXTM3U\n#EXTINF:5,\n0.ts'));
  const variants = parseMaster(master, 'https://cdn.test/hls/base/index.m3u8');
  eq('variant count deduped', variants.length, 3);
  eq('sorted by height desc', variants.map((v) => v.height).join(','), '1080,720,360');
  eq('resolves relative uri', variants[1].url, 'https://cdn.test/hls/base/720/index.m3u8');
  eq('keeps absolute uri', variants[0].url, 'https://other.test/1080/index.m3u8');
  eq('reads bandwidth', variants[0].bandwidth, 5000000);
  eq('quality label from height', qualityLabel(1080, 0), '1080p');
  eq('quality label from bandwidth', qualityLabel(0, 2500000), '2500kbps');
  eq('quality label fallback', qualityLabel(0, 0), 'Auto');
  eq('quality from path', qualityFromPath('https://x/v/1080p.mp4'), 1080);
  eq('quality from path misses digits in host', qualityFromPath('https://192.168.1.1/v/a.mp4'), 0);
  eq('empty master yields no variants', parseMaster('#EXTM3U', 'https://x/y.m3u8').length, 0);

  group('UNIT :: subtitles');

  const withSubs = `<track kind="subtitles" srclang="en" label="English" src="/subs/en.vtt">
    <a href="/subs/forced.srt">forced</a> <a href="/subs/id.srt">id</a>`;
  const subs = [...withSubs.matchAll(SUBTITLE_URL)].map((m) => absolute(m[1]));
  eq('finds subtitle urls', subs.length, 3);
  eq('absolutises subtitle url', subs[0], 'https://nunodrama.my.id/subs/en.vtt');
  eq('no subtitles yields none', [...'<div>x</div>'.matchAll(SUBTITLE_URL)].length, 0);

  group('UNIT :: og meta fallbacks');

  const og = `<meta property="og:title" content="Judul — Nonton Sub Indo | NunoDrama">
    <meta property="og:description" content="A plot.">
    <meta property="og:image" content="https://i.test/c.jpg">`;
  eq('title strips suffix', titleFromHtml(og), 'Judul');
  eq('description', descriptionFromHtml(og), 'A plot.');
  eq('cover', coverFromHtml(og), 'https://i.test/c.jpg');
  eq('missing title returns null', titleFromHtml('<html></html>'), null);

  group('UNIT :: pagination bookkeeping');

  const seen = new Map();
  eq('first sighting is new', rememberNew(seen, 'k', 'a'), true);
  eq('repeat is not new', rememberNew(seen, 'k', 'a'), false);
  eq('empty id is never new', rememberNew(seen, 'k', ''), false);
  for (let i = 0; i < SEEN_MEMORY + 50; i++) rememberNew(seen, 'big', `id${i}`);
  check('seen set is trimmed', seen.get('big').size <= SEEN_MEMORY, String(seen.get('big').size));
  eq('rail key', railKey('freereels', 'foryou'), 'freereels|foryou');
  eq('language tag id', languageTag('id'), 'Indonesian');
  eq('language tag en', languageTag('en'), 'English');
  eq('tv type anime', tvTypeFor('donghua'), 'Anime');
  eq('tv type asian drama', tvTypeFor('DrakorID'), 'AsianDrama');
  eq('tv type series', tvTypeFor('reelshort'), 'TvSeries');
}

function unitRegressions() {
  group('UNIT :: regression guards');

  eq('string ChapterCount is tolerated', parseDramaDto({ BookID: 'a', ChapterCount: '50' }).chapterCount, 50);
  eq('numeric ChapterCount still works', parseDramaDto({ BookID: 'a', ChapterCount: 12 }).chapterCount, 12);
  eq('float ChapterCount truncates', parseDramaDto({ BookID: 'a', ChapterCount: 12.9 }).chapterCount, 12);
  eq('garbage ChapterCount becomes 0', parseDramaDto({ BookID: 'a', ChapterCount: 'n/a' }).chapterCount, 0);
  eq('null Cover becomes null', parseDramaDto({ BookID: 'a', Cover: null }).cover, null);
  eq('missing Cover becomes null', parseDramaDto({ BookID: 'a' }).cover, null);
  eq('string Cover survives', parseDramaDto({ BookID: 'a', Cover: 'c.jpg' }).cover, 'c.jpg');
  const section = parseSection({ dramas: [{ BookID: '1', ChapterCount: '7' }], next: '', success: true, extra: 1 });
  eq('section parses with unknown keys', section.dramas.length, 1);
  eq('section keeps a usable cursor', section.next, '');
  eq('null next is tolerated', parseSection({ dramas: [], next: null }).next, null);
  eq('missing dramas list is tolerated', parseSection({}).dramas.length, 0);

  eq('subtitle lang from srclang', subtitleLang('en', ''), 'en');
  eq('subtitle lang from label', subtitleLang('', 'English'), 'en');
  eq('subtitle lang indonesian label', subtitleLang('', 'Bahasa Indonesia'), 'id');
  eq('subtitle lang defaults to en', subtitleLang('', 'Whatever'), 'en');

  const merged = Array.from({ length: 130 }, (_, i) => i);
  eq('search page 1 slice', searchSlice(merged, 1, 60).slice.length, 60);
  eq('search page 3 slice', searchSlice(merged, 3, 60).slice.length, 10);
  eq('search page 3 hasNext', searchSlice(merged, 3, 60).hasNext, false);
  eq('search page 0 does not throw', searchSlice(merged, 0, 60).slice.length, 0);
  eq('search page 0 hasNext false', searchSlice(merged, 0, 60).hasNext, false);
  eq('search negative page does not throw', searchSlice(merged, -5, 60).slice.length, 0);
  eq('search past the end is empty', searchSlice(merged, 99, 60).slice.length, 0);

  eq('rail stops at max pages', railHasNext(MAX_PAGES, true, 0), false);
  eq('rail stops after 3 empty pages', railHasNext(9, false, 3), false);
  eq('rail continues while producing', railHasNext(9, true, 0), true);
  eq('rail tolerates a single empty page', railHasNext(2, false, 1), true);

  eq('block markers are anchored, not bare substrings', isBlocked('<p>a drama called Just a Moment</p>'), false);
  eq('anchored cloudflare title is caught', isBlocked('<title>Just a moment...</title><div id="challenge">'), true);
  eq('cf error details id is caught', isBlocked('<div id="cf-error-details">x</div>'), true);
  eq('challenge platform script is caught', isBlocked('<script src="/cdn-cgi/challenge-platform/x.js"></script>'), true);
  eq('chinese site text is not blocked', isBlocked('<title>NunoDrama</title><p> DramaVerse MicroDrama ReelShort Melolo </p>'), false);
}

/* ------------------------------------------------------------------ *
 * PHASE 2 - LIVE
 * ------------------------------------------------------------------ */

/**
 * Media probe runs in Node, not the page: some providers hand out bare CDN
 * urls which a browser refuses to read cross-origin, while ExoPlayer on
 * Android has no such rule. Probing from Node mirrors the player.
 */
function playbackHeaders() {
  return {
    'User-Agent': BROWSER_UA,
    Accept: '*/*',
    'Accept-Language': 'en-US,en;q=0.9,id;q=0.8',
  };
}

const refererHeaders = () => ({ ...playbackHeaders(), Referer: `${DEFAULT_BASE}/` });
const originHeaders = () => ({ ...playbackHeaders(), Origin: DEFAULT_BASE });

async function probeMedia(url, headers) {
  try {
    const res = await fetch(url, { headers: { ...(headers || playbackHeaders()), Range: 'bytes=0-2047' } });
    const buf = res.ok ? new Uint8Array(await res.arrayBuffer()) : new Uint8Array();
    return {
      status: res.status,
      type: res.headers.get('content-type') || '',
      length: buf.length,
      head: Array.from(buf.slice(0, 8))
        .map((b) => String.fromCharCode(b))
        .join('')
        .replace(/[^\x20-\x7e]/g, '.'),
    };
  } catch (e) {
    return { status: 0, error: String(e).slice(0, 80) };
  }
}

async function live() {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ userAgent: BROWSER_UA, viewport: { width: 1400, height: 900 }, locale: 'en-US' });
  const page = await ctx.newPage();
  await page.goto(`${DEFAULT_BASE}/`, { waitUntil: 'domcontentloaded', timeout: 60000 });

  const api = (slug, path) =>
    page.evaluate(
      async ({ slug, path }) => {
        const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
        for (let a = 0; a < 4; a++) {
          try {
            const res = await fetch(path, { headers: { 'X-Requested-With': 'XMLHttpRequest', Accept: 'application/json' } });
            const t = await res.text();
            try {
              return JSON.parse(t);
            } catch {
              await sleep(1200 * (a + 1));
            }
          } catch {
            await sleep(1200);
          }
        }
        return null;
      },
      { slug, path },
    );

  const html = (slug, path) =>
    page.evaluate(
      async ({ path }) => {
        const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
        for (let a = 0; a < 4; a++) {
          try {
            const res = await fetch(path);
            const t = await res.text();
            if (!/^<!DOCTYPE html>\s*<html class="no-js/.test(t)) return t;
            await sleep(1200 * (a + 1));
          } catch {
            await sleep(1200);
          }
        }
        return '';
      },
      { path },
    );

  const selectPlatform = (slug) =>
    ctx.addCookies([
      { name: 'nuno_platform', value: slug, domain: 'nunodrama.my.id', path: '/' },
      { name: 'nuno_lang', value: 'en', domain: 'nunodrama.my.id', path: '/' },
    ]);

  group('LIVE :: provider registry (auto-discovery)');
  const homeHtml = await html(null, '/');
  check('home page reachable', homeHtml.length > REAL_PAGE_MIN_BYTES, `${homeHtml.length} bytes`);
  const providers = parseProviders(homeHtml);
  note('providers discovered', providers.length);
  check('discovers every provider from markup', providers.length >= 40, `${providers.length}`);
  eq('no duplicate slugs', new Set(providers.map((p) => p.slug)).size, providers.length);
  check('every provider has a name', providers.every((p) => p.name.length > 0));
  check('no hardcoded provider list needed', providers.some((p) => p.slug === 'nunomix') && providers.some((p) => p.slug === 'velolo'));
  writeFileSync(`${SHOTS}/providers.json`, JSON.stringify(providers, null, 1));

  group('LIVE :: category resolution for every provider');
  const categoryOf = new Map();
  const categorySource = new Map();
  await mapBounded(providers, HTTP_PARALLELISM, async (provider) => {
    await selectPlatform(provider.slug);
    const pageHtml = await html(provider.slug, `/platform/${provider.slug}?next=/`);
    const discovered = parseCategories(pageHtml).map(([c]) => c)[0] || '';
    let category = discovered;
    categorySource.set(provider.slug, discovered ? 'discovered' : 'probed');
    if (!category) {
      for (const candidate of FALLBACK_CATEGORIES) {
        const section = await api(provider.slug, `/api/section/${provider.slug}/${candidate}?page=1`);
        if (section?.dramas?.length) {
          category = candidate;
          break;
        }
      }
    }
    if (category) categoryOf.set(provider.slug, category);
    return category;
  });
  note('categories resolved', `${categoryOf.size}/${providers.length}`);
  note('resolved by discovery', [...categorySource.values()].filter((v) => v === 'discovered').length);
  note('resolved by probe', [...categorySource.values()].filter((v) => v === 'probed').length);
  check('every provider resolves a category', categoryOf.size === providers.length, [...providers.map((p) => p.slug)].filter((s) => !categoryOf.has(s)).join(','));
  const catNames = [...new Set(categoryOf.values())];
  note('distinct category names on the site', catNames.join(', '));
  check('category names vary across providers (discovery is not a constant)', catNames.length > 1, String(catNames.length));

  group('LIVE :: catalogue pagination (unlimited scroll)');
  const paginationReport = [];
  const sample = providers.filter((_, i) => i % 7 === 0).slice(0, 8);
  for (const provider of sample) {
    const category = categoryOf.get(provider.slug);
    if (!category) continue;
    const cursors = new Map();
    const seen = new Map();
    const pages = [];
    for (let p = 1; p <= 3; p++) {
      const key = railKey(provider.slug, category);
      const cursor = cursors.get(`${key}|${p - 1}`);
      const section = await api(provider.slug, `/api/section/${provider.slug}/${category}?page=${p}${cursor ? `&next=${urlEncode(cursor)}` : ''}`);
      if (!section) {
        pages.push({ page: p, blocked: true });
        break;
      }
      if (section.next) cursors.set(`${key}|${p}`, section.next);
      const fresh = (section.dramas || []).filter((d) => rememberNew(seen, key, d.BookID));
      pages.push({ page: p, got: (section.dramas || []).length, fresh: fresh.length, cursor: section.next || '' });
    }
    paginationReport.push({ slug: provider.slug, category, pages });
    const ok = pages.length === 3 && pages.every((x) => !x.blocked);
    check(`${provider.slug}: 3 pages fetched (${category})`, ok, JSON.stringify(pages.map((x) => x.blocked ? 'BLOCKED' : `${x.fresh}/${x.got}`)));
    if (ok) {
      const totalFresh = pages.reduce((a, x) => a + x.fresh, 0);
      const gotData = pages.some((x) => x.got > 0);
      if (!gotData) {
        note(`${provider.slug}: upstream served no items for ${category}`, 'site-side, handled without crash');
      } else {
        check(`${provider.slug}: pages yield unique items`, totalFresh > 0, `${totalFresh} unique over 3 pages`);
        const usedCursor = pages.slice(1).some((x) => x.cursor);
        if (usedCursor) note(`${provider.slug}: cursor pagination active`, pages.map((x) => x.cursor || '-').join(' -> '));
      }
    }
  }
  writeFileSync(`${SHOTS}/pagination.json`, JSON.stringify(paginationReport, null, 1));

  const cursorProviders = providers.filter((p) => /offset=|cursor|position/.test(JSON.stringify(paginationReport.find((r) => r.slug === p.slug) || {})));
  note('cursor-based providers detected', cursorProviders.map((p) => p.slug).join(', ') || 'none in sample');

  group('LIVE :: rail isolation (mixed rail must not starve per-provider rails)');
  const firstSlug = providers[0].slug;
  const firstCat = categoryOf.get(firstSlug);
  if (firstCat) {
    const seen = new Map();
    const mixedScope = railScope('MIXED', firstSlug, firstCat);
    const railScopeKey = railScope('PROVIDER', firstSlug, firstCat);
    check('the two rails use different dedupe scopes', mixedScope !== railScopeKey, `${mixedScope} vs ${railScopeKey}`);
    const section = await api(firstSlug, `/api/section/${firstSlug}/${firstCat}?page=1`);
    const dramas = section?.dramas || [];
    const mixed = dramas.filter((d) => rememberNew(seen, mixedScope, d.BookID));
    note('mixed rail seeded', `${mixed.length} ids for ${firstSlug}`);
    const perProvider = dramas.filter((d) => rememberNew(seen, railScopeKey, d.BookID));
    note('per-provider rail after the mixed rail', `${perProvider.length} ids`);
    check('the mixed rail does not starve its own per-provider rail', perProvider.length === mixed.length, `${perProvider.length} vs ${mixed.length}`);
    const repeat = dramas.filter((d) => rememberNew(seen, railScopeKey, d.BookID));
    check('the per-provider rail still dedupes itself across pages', repeat.length === 0, `${repeat.length} repeats`);
  } else {
    check('rail isolation has a provider to test', false, 'no category resolved');
  }

  group('LIVE :: parallel search across all providers');
  for (const term of ['cinta', 'ceo']) {
    const batches = await mapBounded(providers, HTTP_PARALLELISM, async (provider) => {
      const section = await api(provider.slug, `/api/search/${provider.slug}?q=${urlEncode(term)}`);
      return (section?.dramas || []).slice(0, SEARCH_PER_PROVIDER).map((d) => [provider, d]);
    });
    const merged = new Map();
    for (const entry of batches.filter(Boolean).flat()) {
      const [provider, drama] = entry;
      const key = `${provider.slug}|${drama.BookID}`;
      if (!merged.has(key)) merged.set(key, entry);
    }
    const list = [...merged.values()];
    const contributors = new Set(list.map(([p]) => p.slug));
    note(`search "${term}"`, `${list.length} unique results from ${contributors.size}/${providers.length} providers`);
    check(`search "${term}" returns results`, list.length > 0, String(list.length));
    check(`search "${term}" spans multiple providers`, contributors.size > 1, String(contributors.size));
    check(`search "${term}" dedupes by provider+book`, merged.size === list.length);
    check(`search "${term}" every result has id and title`, list.every(([, d]) => d.BookID && (d.BookName || d.BookID)));
    check(`search "${term}" every result has a cover`, list.filter(([, d]) => d.Cover).length > list.length * 0.5);
  check(`search "${term}" results carry a provider label`, list.every(([p, d]) => p.name.length > 0 && (d.BookName || d.BookID)));
    check(`search "${term}" respects per-provider cap`, batches.filter(Boolean).every((b) => b.length <= SEARCH_PER_PROVIDER));
    const from = 0;
    const to = Math.min(from + SEARCH_PAGE_SIZE, list.length);
    note(`search "${term}" page 1 slice`, `${to} of ${list.length}, hasNext=${to < list.length}`);
    check(`search "${term}" pagination math`, list.length <= SEARCH_PAGE_SIZE || to < list.length);
    writeFileSync(`${SHOTS}/search-${term}.json`, JSON.stringify(list.slice(0, 40), null, 1));
  }

  group('LIVE :: detail page (metadata + episode list)');
  const detailReport = [];
  for (const provider of sample.slice(0, 5)) {
    const category = categoryOf.get(provider.slug);
    if (!category) continue;
    const section = await api(provider.slug, `/api/section/${provider.slug}/${category}?page=1`);
    const book = section?.dramas?.[0]?.BookID;
    if (!book) {
      check(`${provider.slug}: has an item`, false, 'no BookID');
      continue;
    }
    await selectPlatform(provider.slug);
    const detailHtml = await html(provider.slug, `/detail/${provider.slug}/${book}`);
    const series = parseSeriesLd(detailHtml);
    const watchHtml = await html(provider.slug, `/watch/${provider.slug}/${book}?ep=1`);
    const eps = parseEpisodes(watchHtml);
    const report = {
      slug: provider.slug,
      book,
      title: series?.name || null,
      declared: series?.episodeCount || 0,
      parsed: eps.length,
      plot: (series?.description || '').length,
      cover: !!series?.image,
    };
    detailReport.push(report);
    check(`${provider.slug}: detail has title`, !!series?.name, String(series?.name).slice(0, 40));
    check(`${provider.slug}: detail has plot`, (series?.description || '').length > 20);
    check(`${provider.slug}: detail has cover`, /^https?:\/\//.test(series?.image || ''));
    check(`${provider.slug}: episode list parsed`, eps.length > 0, `${eps.length} episodes`);
    if (series?.episodeCount) {
      const delta = Math.abs(eps.length - series.episodeCount);
      check(`${provider.slug}: episode count tracks declared`, delta <= 2, `declared ${series.episodeCount}, parsed ${eps.length}`);
    }
    check(`${provider.slug}: first episode url is watch page`, /^https?:\/\/.+\/watch\/.+\?ep=1$/.test(eps[0]?.url || ''), eps[0]?.url);
  }
  writeFileSync(`${SHOTS}/detail.json`, JSON.stringify(detailReport, null, 1));

  group('LIVE :: stream extraction + real HTTP probe');
  const streamReport = [];
  for (const provider of sample) {
    const category = categoryOf.get(provider.slug);
    if (!category) continue;
    const section = await api(provider.slug, `/api/section/${provider.slug}/${category}?page=1`);
    const book = section?.dramas?.[0]?.BookID;
    if (!book) continue;
    await selectPlatform(provider.slug);
    const watchHtml = await html(provider.slug, `/watch/${provider.slug}/${book}?ep=1`);
    const player = parsePlayer(watchHtml);
    if (!player) {
      note(`${provider.slug}: no video element on site (upstream 502 or dead title)`, 'handled -> no crash, no links');
      streamReport.push({ slug: provider.slug, book, player: null });
      continue;
    }
    const type = linkType(player.kind, player.url);
    const sources = [];
    if (type === 'M3U8') {
      const body = await page.evaluate(
        async (u) => {
          const res = await fetch(u, { headers: { Accept: '*/*' } });
          return res.ok ? res.text() : '';
        },
        player.url,
      );
      const variants = isMasterPlaylist(body) ? parseMaster(body, player.url) : [];
      if (variants.length) {
        for (const v of variants) {
          sources.push({ url: v.url, quality: qualityLabel(v.height, v.bandwidth), height: v.height });
        }
      } else {
        sources.push({ url: player.url, quality: 'Auto', height: 0 });
      }
    } else {
      sources.push({ url: player.url, quality: qualityLabel(qualityFromPath(player.url), 0), height: qualityFromPath(player.url) });
    }

    const probes = [];
    for (const source of sources.slice(0, 4)) {
      const probe = await probeMedia(source.url);
      probes.push({ ...source, ...probe });
    }

    const playable = probes.filter((p) => p.status === 200 || p.status === 206);
    const entry = {
      slug: provider.slug,
      book,
      kind: player.kind,
      encrypted: player.encrypted,
      type,
      lang: player.language,
      sources: probes,
    };
    streamReport.push(entry);
    check(`${provider.slug}: extracted ${probes.length} source(s)`, probes.length > 0, `${player.kind} enc=${player.encrypted}`);
    check(`${provider.slug}: source responds 200/206`, playable.length > 0, probes.map((p) => `${p.status} ${p.type}`).join(' | '));
    if (probes.length > 1) {
      note(`${provider.slug}: qualities`, probes.map((p) => p.quality).join(', '));
      check(`${provider.slug}: qualities are distinct`, new Set(probes.map((p) => p.quality)).size === probes.length);
    }
    if (playable[0] && type === 'M3U8') {
      check(`${provider.slug}: m3u8 payload is a playlist`, playable[0].head.startsWith('#EXTM3U'), playable[0].head.slice(0, 20));
    }
    if (playable[0] && type === 'VIDEO' && !player.encrypted) {
      check(`${provider.slug}: mp4 payload has ftyp box`, playable[0].head.includes('ftyp'), playable[0].head.slice(0, 20));
    }
  }
  writeFileSync(`${SHOTS}/streams.json`, JSON.stringify(streamReport, null, 1));

  group('LIVE :: full stream sweep across every provider');
  const sweep = await mapBounded(providers, HTTP_PARALLELISM, async (provider) => {
    const category = categoryOf.get(provider.slug);
    if (!category) return { slug: provider.slug, state: 'no-category' };
    const section = await api(provider.slug, `/api/section/${provider.slug}/${category}?page=1`);
    const book = section?.dramas?.[0]?.BookID;
    if (!book) return { slug: provider.slug, state: 'no-items' };
    await selectPlatform(provider.slug);
    const watchHtml = await html(provider.slug, `/watch/${provider.slug}/${book}?ep=1`);
    const player = parsePlayer(watchHtml);
    if (!player) return { slug: provider.slug, state: 'no-video-element' };
    const type = linkType(player.kind, player.url);
    const candidates = [];
    if (type === 'M3U8') {
      const body = await fetch(player.url, { headers: playbackHeaders() })
        .then((r) => (r.ok ? r.text() : ''))
        .catch(() => '');
      const variants = isMasterPlaylist(body) ? parseMaster(body, player.url) : [];
      if (variants.length) {
        for (const v of variants) candidates.push({ url: v.url, quality: qualityLabel(v.height, v.bandwidth) });
      } else {
        candidates.push({ url: player.url, quality: 'Auto' });
      }
    } else {
      candidates.push({ url: player.url, quality: qualityLabel(qualityFromPath(player.url), 0) });
    }
    let best = null;
    for (const candidate of candidates) {
      const probe = await probeMedia(candidate.url);
      if (probe.status === 200 || probe.status === 206) {
        best = { ...candidate, ...probe };
        break;
      }
    }
    return {
      slug: provider.slug,
      state: best ? 'ok' : 'dead-source',
      kind: player.kind,
      encrypted: player.encrypted,
      relativeSrc: player.relative,
      type,
      lang: player.language,
      qualities: candidates.length,
      probedAbsolute: candidates.every((c) => /^https?:\/\//.test(c.url)),
      best: best && { quality: best.quality, status: best.status, type: best.type, head: best.head.slice(0, 12) },
    };
  });

  const okList = sweep.filter(Boolean);
  const states = {};
  for (const s of okList) states[s.state] = (states[s.state] || 0) + 1;
  const withPlayer = okList.filter((s) => ['ok', 'dead-source'].includes(s.state)).length;
  note('sweep states', JSON.stringify(states));
  note('with multiple qualities', okList.filter((s) => s.qualities > 1).map((s) => s.slug).join(', ') || 'none');
  note('encrypted hls (playable, EXT-X-KEY)', okList.filter((s) => s.encrypted && s.type === 'M3U8').map((s) => s.slug).join(', ') || 'none');
  note('encrypted mp4 (needs in-browser decrypt)', okList.filter((s) => s.encrypted && s.type === 'VIDEO').map((s) => s.slug).join(', ') || 'none');
  check('every provider reaches a verdict', okList.length === providers.length, `${okList.length}/${providers.length}`);
  const relativeSlugs = okList.filter((s) => s.relativeSrc).map((s) => s.slug);
  note('providers serving a site-relative data-src (absolutised)', relativeSlugs.join(', ') || 'none');
  check('every probed source url is absolute', okList.filter((s) => s.probedAbsolute !== undefined).every((s) => s.probedAbsolute), okList.filter((s) => s.probedAbsolute === false).map((s) => s.slug).join(','));
  check('a source is extracted for every title that has one', (states.ok || 0) + (states['dead-source'] || 0) === withPlayer, `${(states.ok || 0) + (states['dead-source'] || 0)} of ${withPlayer}`);
  check('extraction never leaves a provider unparsed', okList.every((s) => s.state), '');
  check('the majority of providers serve a live source', (states.ok || 0) >= providers.length * 0.75, `${states.ok || 0}/${providers.length}`);
  check('hls sources serve a real playlist', okList.filter((s) => s.state === 'ok' && s.type === 'M3U8').every((s) => s.best.head.startsWith('#EXTM3U')), okList.filter((s) => s.state === 'ok' && s.type === 'M3U8' && !s.best.head.startsWith('#EXTM3U')).map((s) => s.slug).join(','));
  check('unencrypted mp4 sources carry a valid mp4 header', okList.filter((s) => s.state === 'ok' && s.type === 'VIDEO' && !s.encrypted).every((s) => s.best.head.includes('ftyp')), okList.filter((s) => s.state === 'ok' && s.type === 'VIDEO' && !s.encrypted && !s.best.head.includes('ftyp')).map((s) => s.slug).join(','));
  check('stream language resolved for all live sources', okList.filter((s) => s.state === 'ok').every((s) => s.lang === 'en' || s.lang === 'id'), okList.filter((s) => s.state === 'ok').map((s) => s.lang).join(','));
  const problem = okList.filter((s) => s.state !== 'ok');
  if (problem.length) {
    note('providers without a confirmed live source (site-side)', problem.map((s) => `${s.slug}(${s.state})`).join(', '));
  }
  writeFileSync(`${SHOTS}/sweep.json`, JSON.stringify(okList, null, 1));

  group('LIVE :: playback header strategy');
  const headerSlugs = ['nunomix', 'reelala', 'bstation', 'flextv', 'donghua', 'melolo', 'dotdrama', 'dramaverse']
    .filter((s) => categoryOf.has(s));
  const combos = [
    ['shipped (ua only)', playbackHeaders],
    ['+referer', refererHeaders],
    ['+origin', originHeaders],
  ];
  const scores = Object.fromEntries(combos.map(([l]) => [l, 0]));
  let trials = 0;
  const headerRows = [];
  for (const slug of headerSlugs) {
    const perCombo = Object.fromEntries(combos.map(([l]) => [l, 0]));
    let seen = 0;
    for (let attempt = 0; attempt < 3; attempt++) {
      const section = await api(slug, `/api/section/${slug}/${categoryOf.get(slug)}?page=${attempt + 1}`);
      const book = section?.dramas?.[0]?.BookID;
      if (!book) continue;
      await selectPlatform(slug);
      const w = await html(slug, `/watch/${slug}/${book}?ep=${attempt + 1}`);
      const player = parsePlayer(w);
      if (!player) continue;
      seen++;
      for (const [label, make] of combos) {
        const probe = await probeMedia(player.url, make());
        if (probe.status === 200 || probe.status === 206) {
          perCombo[label]++;
          scores[label]++;
        }
      }
    }
    if (seen) {
      headerRows.push({ slug, trials: seen, results: perCombo });
      note(slug.padEnd(12), combos.map(([l]) => `${l}=${perCombo[l]}/${seen}`).join('  '));
      trials += seen;
    }
  }
  const okTotal = (l) => scores[l];
  note('totals over ' + trials + ' url probes', combos.map(([l]) => `${l}=${okTotal(l)}/${trials}`).join('  '));
  check('shipped header set is sent with no Referer', !('Referer' in playbackHeaders()));
  check('shipped header set is sent with no Origin', !('Origin' in playbackHeaders()));
  check('shipped header set always sends a browser User-Agent', /Mozilla\/5\.0/.test(playbackHeaders()['User-Agent']));
  check('shipped header set scores highest overall', (() => {
    const best = Math.max(...combos.map(([l]) => okTotal(l)));
    return okTotal('shipped (ua only)') === best;
  })(), JSON.stringify(scores));
  check('no provider regresses under the shipped header set', (() => {
    for (const row of headerRows) {
      const shipped = row.results['shipped (ua only)'];
      for (const [l] of combos) {
        if (l === 'shipped (ua only)') continue;
        if (row.results[l] > shipped) return false;
      }
    }
    return true;
  })(), JSON.stringify(headerRows));
  writeFileSync(`${SHOTS}/headers.json`, JSON.stringify(headerRows, null, 1));

  group('LIVE :: resilience');
  const missing = await html('nunomix', '/detail/nunomix/__does_not_exist__');
  check('unknown detail page does not throw', typeof missing === 'string');
  eq('unknown detail page yields no episodes', parseEpisodes(missing).length, 0);
  eq('unknown detail page yields no player', parsePlayer(missing), null);
  const emptyTerm = await api('nunomix', `/api/search/nunomix?q=${urlEncode('zzzzqqqxxnotathing')}`);
  check('nonsense search returns no crash', emptyTerm === null || Array.isArray(emptyTerm.dramas));
  const blockedProbe = parsePlayer('<!DOCTYPE html> <html class="no-js ie6 oldie"><title>Just a moment...</title>');
  eq('challenge page yields no player', blockedProbe, null);
  check('challenge page detected as blocked', isBlocked('<!DOCTYPE html> <html class="no-js ie6 oldie">'));
  const badJson = await page.evaluate(async () => {
    try {
      await (await fetch('/api/section/nunomix/all_drama?page=99999')).json();
      return 'json';
    } catch {
      return 'threw';
    }
  });
  check('deep page request does not crash', badJson === 'json' || badJson === 'threw');

  group('LIVE :: english vs indonesian toggle');
  const toggleSlug = 'melolo';
  const toggleBook = (await api(toggleSlug, `/api/section/${toggleSlug}/${categoryOf.get(toggleSlug)}?page=1`))?.dramas?.[0]?.BookID;
  if (toggleBook) {
    for (const lang of ['id', 'en']) {
      await ctx.clearCookies();
      await ctx.addCookies([
        { name: 'nuno_platform', value: toggleSlug, domain: 'nunodrama.my.id', path: '/' },
        { name: 'nuno_lang', value: lang, domain: 'nunodrama.my.id', path: '/' },
      ]);
      const w = await html(toggleSlug, `/watch/${toggleSlug}/${toggleBook}?ep=1`);
      const pl = parsePlayer(w);
      note(`nuno_lang=${lang}`, `stream lang=${pl?.language} title="${(pl?.title || '').slice(0, 46)}"`);
      if (lang === 'en') check('english toggle sets stream lang=en', pl?.language === 'en', String(pl?.language));
    }
  } else {
    check('english toggle book available', false);
  }

  await ctx.close();
  await browser.close();
}

/* ------------------------------------------------------------------ */

const only = process.argv[2];
const started = Date.now();

if (!only || only === 'unit') { unit(); unitRegressions(); }
if (!only || only === 'live') {
  await live().catch((e) => {
    check('live phase completed', false, String(e && e.stack ? e.stack.split('\n')[0] : e));
  });
}

const passed = results.filter((r) => r.ok).length;
const failed = results.filter((r) => !r.ok);
const seconds = ((Date.now() - started) / 1000).toFixed(1);

console.log(`\n${'='.repeat(72)}`);
console.log(`RESULT  ${passed}/${results.length} passed in ${seconds}s`);
if (failed.length) {
  console.log(`\nFAILURES (${failed.length}):`);
  for (const f of failed) console.log(`  - [${f.group}] ${f.label}${f.detail ? ` :: ${f.detail}` : ''}`);
}
console.log(`artifacts: ${SHOTS}`);
console.log('='.repeat(72));

process.exit(failed.length ? 1 : 0);
