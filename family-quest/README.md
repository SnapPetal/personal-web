# Family Quest

A family-friendly web app where an AI dungeon master runs a shared D&D-style adventure. Kids can play. The owner is Thon Becker.

The table is server-rendered HTML (Bootstrap 5, Alpine.js, and htmx, vendored in `public/`). The browser's light or dark setting is followed. There is no theme toggle and no stored theme. Noto Sans is vendored under the SIL Open Font License (`public/fonts/OFL.txt`).

Nothing in this app calls a paid third-party API or asks for a new API key. Workers AI and D1 are used through Cloudflare bindings, inside the free daily allowance, with a configurable cap in front of that allowance.

This project lives in `family-quest/` so it can be lifted into its own repository later. A separate private GitHub repository `SnapPetal/family-quest` was not created from this change.

## What you can do

- Create a quest or continue a saved one. Quick start builds a party from names. Custom start picks a kid-friendly class and ancestry for each hero.
- Each hero has a name, class, ancestry, level, HP, AC, the six ability scores, inventory, XP, and a short portrait description.
- Type actions. The dungeon master narrates, gives people you meet a voice, and asks for rolls. A running summary is stored in D1 so a long quest does not have to resend the whole log.
- Turn the narrator on, optionally auto-play new replies, and speak an action with the browser Web Speech API where the browser supports it.
- Ask for a gentle challenge. Combat has initiative, enemies, turns, attack and damage rolls, conditions, and an ending with victory, loot, and XP. The encounter panel shows turn order and HP bars.
- Roll d4–d20 with a modifier, advantage, or disadvantage. Combat dice use the same roller.
- The server rolls every die with `crypto.getRandomValues` and rejection sampling. The model chooses critter actions and story. The server applies the numbers.
- A shared family passcode (Worker secret) sets a signed session cookie.
- A daily cap on Workers AI calls is stored in D1. Dice, sheets, and saved chapters keep working after the cap, with a plain explanation.

Classes: Brave Knight, Forest Ranger, Kind Healer, Spark Wizard, Sneaky Scout, Story Bard, Animal Friend, Gadget Inventor.

Ready-made premises: The Lost Picnic, The Birthday Dragon, The Library of Living Books, and Rainbow Bridge Repair.

## Cost and model limits

Workers AI includes **10,000 neurons per day** on the free allocation. That resets at 00:00 UTC. Past that, Cloudflare bills neurons, so this app stops itself first.

| Setting | Default | Purpose |
| --- | --- | --- |
| `AI_DAILY_CAP` | `40` | Successful AI attempts (story, critter choices, and narrator audio) per UTC day |
| `AI_MODEL` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | Dungeon master |
| `AI_MODEL_FALLBACK` | `@cf/meta/llama-3.1-8b-instruct-fast` | Used when the 70B model errors or returns nothing |
| `TTS_MODEL` | `@cf/myshell-ai/melotts` | Narrator audio |

`@cf/meta/llama-3.3-70b-instruct-fp8-fast` is listed at about 26,668 neurons per million input tokens and 204,805 neurons per million output tokens ([Workers AI pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/)). A normal turn is on the order of a couple thousand input tokens and a few hundred output tokens, roughly 100–200 neurons. Forty turns stay under the free 10,000-neuron day for ordinary family play. The smaller fallback model is cheaper when the primary call fails. A failed primary call may try the fallback; that still counts as one family turn in D1, but it can spend neurons twice.

Narration uses `@cf/myshell-ai/melotts`, which is Cloudflare-hosted (about $0.000205 per audio minute, billed as neurons). Each clip is cut to 450 characters. `@cf/deepgram/aura-1` is available on Workers AI, but it is a partner model billed at about $0.015 per 1,000 characters, outside the neuron pool. This app does not call it.

If MeloTTS fails, the daily cap is spent, or the browser blocks autoplay, the page falls back to `speechSynthesis`. Speaking an action uses the browser `SpeechRecognition` API only. Some browsers, especially iOS Safari and Firefox, do not implement speech recognition; the microphone button says so and typing still works. Auto-play can also be blocked until a click, so each Dungeon Master line has its own Read aloud button.

The system prompt requires a PG, family-friendly tone: cozy trouble, no gore, no romance, and no real harm. A small server-side word check replaces a reply that slips past the prompt. Knockouts wake up safe with 1 HP. Critter stats proposed by the model are clamped before they touch the encounter.

## Local setup

```bash
cd family-quest
npm ci
cp .dev.vars.example .dev.vars
```

Edit `.dev.vars`:

- `FAMILY_PASSCODE` — at least 4 characters. This is the shared family passcode.
- `SESSION_SECRET` — at least 16 characters. Use a long random string (`openssl rand -base64 32`).

Create a local D1 database and apply the migration. This does not create a remote database:

```bash
npx wrangler d1 migrations apply family-quest --local
npx wrangler dev
```

Open the URL Wrangler prints (usually `http://localhost:8787`). The session cookie is `Secure` only on HTTPS, so local HTTP still keeps you signed in.

## Deploy

Do this from the `family-quest/` directory when you are ready. These commands create the remote database, store secrets, and publish the Worker. They were not run for the initial build.

```bash
npx wrangler d1 create family-quest
```

Copy the printed `database_id` into `wrangler.toml`, replacing `00000000-0000-0000-0000-000000000000`.

```bash
npx wrangler d1 migrations apply family-quest --remote
printf '%s' 'your-family-passcode' | npx wrangler secret put FAMILY_PASSCODE
printf '%s' "$(openssl rand -base64 32)" | npx wrangler secret put SESSION_SECRET
npx wrangler deploy
```

`FAMILY_PASSCODE` and `SESSION_SECRET` are Worker secrets. They are not in `wrangler.toml`.

Change `AI_DAILY_CAP` in `[vars]` if you want a different daily ceiling, then deploy again.

### quest.thonbecker.biz

`wrangler.toml` documents the intended hostname and leaves the route commented out, so deploy does not attach a custom domain. When the DNS name exists in the `thonbecker.biz` zone on the same account, uncomment:

```toml
[[routes]]
pattern = "quest.thonbecker.biz"
custom_domain = true
```

Then run `npx wrangler deploy` again.

## Tests

```bash
npm test
npm run typecheck
```

Vitest covers the dice roller (including unbiased sampling, advantage, and disadvantage), the encounter engine (initiative, hits, misses, crits, conditions, victory, loot, XP, defeat, and clamped enemy stats), and the daily AI cap. GitHub Actions runs both checks for changes under `family-quest/`.

## Project map

- `src/dice.ts` — crypto dice
- `src/encounter.ts` — combat rules
- `src/cost.ts` — daily cap
- `src/dm.ts` — PG system prompt and JSON parsing
- `src/ai.ts` — Workers AI text and MeloTTS audio
- `src/auth.ts` — passcode check and HMAC session cookie
- `migrations/0001_init.sql` — D1 schema
- `public/` — vendored Bootstrap, Alpine, htmx, Noto Sans, and app CSS/JS
