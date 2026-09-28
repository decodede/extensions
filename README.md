<div align="center">

# 🍪 Few Cookies

[Tap to install in CloudStream](https://self-similarity.github.io/http-protocol-redirector?r=cloudstreamrepo://github.com/decodede/extensions/raw/builds/repo.json)

```
https://raw.githubusercontent.com/decodede/extensions/builds/repo.json
```

</div>

## Extensions

| Extension | Description                                                       | Status     |
| --------- | ----------------------------------------------------------------- | ---------- |
| Rulz      | Movies and shows                                                  | ✅ Working |
| Wood      | Telugu and dubbed movies                                          | ✅ Working |
| Wap       | Movies and shows                                                  | ✅ Working |
| Screen    | Movies and shows                                                  | ✅ Working |
| NunoDrama | 56 short-drama providers as catalogues, English/Indonesian switch | ✅ Working |
| StremioCS | Stremio addons                                                    | ✅ Working |

## NunoDrama

Every provider the site carries becomes its own catalogue, so scrolling one rail
walks the whole provider rather than stopping after the first page. Providers
are read from the site at runtime, so a provider added upstream appears without
a code change.

- **Catalogues** — one rail per provider plus a merged _All Providers_ rail,
  cursor pagination included, up to 100 pages or 3 empty pages.
- **Search** — fanned out across all providers concurrently (8 at a time), merged,
  deduped and paginated.
- **Sources** — every quality of a master playlist, every variant of a plain
  playlist, plus the direct file for mp4. Subtitles and audio tracks are emitted
  when the page carries them.
- **Language** — the site's own Indonesian/English switch, exposed in settings and
  sent as the `nuno_lang` cookie on every request.
- **Resilience** — Cloudflare challenge pages and upstream 502s are detected and
  retried with backoff; a dead title degrades to "no links" instead of a crash.

Settings (gear icon on the provider) switch language, override the site address
and refresh the provider list.

### Tests

```bash
npm i playwright && npx playwright install chromium
node test/nunodrama.test.mjs unit    # offline, parser fixtures
node test/nunodrama.test.mjs live     # real site + real stream probes
```

See [test/README.md](test/README.md).
