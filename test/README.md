# NunoDrama test harness

`node test/nunodrama.test.mjs [unit|live]`

`unit` (offline, ~0.1s) runs every parser through fixtures, including regression
guards for each bug this harness has already caught.
`live` drives the real site through Playwright and then performs real HTTP
probes on every extracted stream. Both phases exit non-zero on failure.

Requires Playwright (`npm i playwright && npx playwright install chromium`).

## What it mirrors

Every parser in the harness is a line-for-line mirror of the Kotlin:

| Harness function                     | Kotlin                           |
| ------------------------------------ | -------------------------------- |
| `parseProviders` / `parseCategories` | `NunoDramaRegistry`              |
| `parsePlayer` / `parseEpisodes`      | `NunoDramaStreams`               |
| `parseSeriesLd`                      | `NunoDramaStreams.parseSeriesLd` |
| `parseMaster` / `parseMaster`        | `NunoDramaStreams`               |
| `parseDramaDto` / `parseSection`     | `LenientIntSerializer` + DTOs    |
| `isBlocked`                          | `NunoDramaClient.isBlocked`      |
| `rememberNew` / `railScope`          | `NunoDramaProvider` dedupe state |
| `searchSlice` / `railHasNext`        | `NunoDramaProvider.search/rail`  |
| `subtitleLang`                       | `NunoDramaProvider.subtitleLang` |

Keep the two in step. The Kotlin cannot be compiled without a JDK, so this
harness is the executable specification of its behaviour.

## Live phase coverage

- provider auto-discovery from the site markup (all of them, no hardcoded list)
- category resolution for every provider, by discovery and by fallback probe
- three-page pagination per rail, including cursor (`next` token) chaining
- parallel search merged across every provider, deduped, then paginated
- detail pages: title, plot, cover, full episode list
- a stream sweep over all providers, with a real range request against each
  source, asserting playlist/mp4 magic bytes
- the playback header strategy, measured live across header combinations
- resilience against Cloudflare challenge pages, 502s and unknown ids
