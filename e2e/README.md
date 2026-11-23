# End-to-end user journeys

Playwright tests that walk through the app the way a **Neuling** (newcomer) and an **EinAb-Geber:in** (teacher) use it,
in German, on a desktop (1280×800) and a phone (390×844) viewport. Every step saves a screenshot.
Admin pages are out of scope.

| Journey | File | What it covers |
| --- | --- | --- |
| Neuling | `tests/neuling.spec.ts` | Bezirk wählen, freie EinAbs ohne Login, Login mit Test-Zugang, Datenschutz, EinAb buchen, Meine Buchungen, stornieren, Hilfe, Profil, Logout |
| EinAb-Geber:in | `tests/einab-geber.spec.ts` | Login, Meine EinAbs, neue EinAb veröffentlichen, ein Neuling bucht sie (zweiter Browser), Buchung sehen, Aktionen, Kalendereinstellungen, spontane EinAb nachtragen, Anleitung, Freie EinAbs, Profil |

## How it runs

The tests start their own server (`scripts/start-server.sh`): the Spring Boot jar with the bundled Angular client,
the `local` profile, a fresh **in-memory H2 database** and the **demo login** switched on. Signing in uses the
demo login (Foodsharing-ID `1308` → "Testen als Neuling" / "Testen als EinAb-Geber:in"), so no real account,
no secret and no connection to foodsharing.network is needed. All outbound channels (Foodsharing messages,
mail, push, automation) are off or point to a dead local port. The server runs in `e2e/.server-run/`,
so a developer's `server/local-secrets.properties` is never loaded.

## Run

```bash
cd e2e
npm ci
npx playwright install chromium   # CI: npx playwright install --with-deps chromium
npm test
```

The first run builds the jar with `./mvnw package` (this also builds the client). To reuse a jar you already built:

```bash
E2E_SKIP_SERVER_BUILD=true npm test
```

Other options:

- `npx playwright test --project=mobile` runs only the phone viewport (`desktop` for the other one).
- `npm run test:headed` watches the browser.
- `npm run report` opens the HTML report with every screenshot per step.
- `E2E_SERVER_PORT` changes the port (default 8181).
- `E2E_BASE_URL=http://localhost:8181` runs against a server that is already running (it must have the demo login on).

## CI

`.github/workflows/e2e.yml` runs the journeys on every pull request and every push to `main` (check name
**Playwright e2e**). It builds the jar once, then runs the tests with `E2E_SKIP_SERVER_BUILD=true`. The HTML report,
screenshots and traces are attached to the run as the `e2e-report` artifact.

## Screenshots

`screenshots/<desktop|mobile>/<journey>/NN-<step>.png`, numbered in journey order (gitignored). Pages are captured
whole; with a dialog open, the visible screen is captured instead.

To refresh the committed copies in `docs/screenshots/` (journeys under `desktop/` and `mobile/`, store screenshots
under `store/`):

- **Locally:** `npm run screenshots` (from `e2e/`). Options after `--`: `--skip-build` reuses the built jar,
  `--only=journeys` or `--only=store` limits what is regenerated. Commit the result yourself.
- **On GitHub:** Actions → *Update screenshots* → Run workflow. It runs the same script, uploads the screenshots as an
  artifact and opens a PR from a `screenshots/<date>-<run>` branch (and starts the e2e check on it). Opening the PR
  needs Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests"; without it the
  run summary links to the compare page instead.

## Good to know

- The demo login creates at most 20 accounts per IP and hour. One full run creates 6, so a server you keep
  running (`E2E_BASE_URL`) allows about three runs per hour; a server started by the tests begins fresh every time.
- Dates come from the seeded demo EinAbs (a few days ahead), so screenshots change from day to day. They are a
  record of the journey, not a pixel comparison.
