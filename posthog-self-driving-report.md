# PostHog Self-driving setup report

## Summary

PostHog Self-driving has been configured for this web application. Session Replay, Error Tracking, and Support were enabled; native health, error, and Support responders were enabled; and the selected GitHub Issues warehouse source and responder were connected.

Four selective built-in scouts and two Replay Vision monitors are armed. Findings will start appearing in the [Self-driving inbox](https://us.posthog.com/project/431281/inbox) within about 30 minutes as the scout coordinator runs and recordings become available.

## AI data processing

**Approved.** Organization-level AI data processing approval was confirmed by the wizard before this setup began.

## GitHub

| Item | Status |
|---|---|
| GitHub App | Already connected before setup |
| Repository | `SnapPetal/personal-web` selected and confirmed |
| GitHub Issues warehouse source | Connected by this setup; source `01a06a62-ec2d-0000-598f-c6b320fb5ec0`; first sync started |
| Synced table | `issues` only, incrementally by `updated_at` |

Only the responder-consumed `issues` table is syncing. Additional GitHub tables can be enabled later in PostHog if needed.

## Products enabled

| Product | Result | Notes |
|---|---|---|
| Session Replay | enabled | This is a web application. The existing browser `posthog.init` does not disable replay; input masking remains enabled. No recordings were present during setup, so scanners are armed for first captured sessions. |
| Error Tracking | enabled | The existing browser initialization does not disable exception capture. No error issues were returned by the light probe. |
| Support | enabled | Support tickets require an inbound email, inbox, or Slack channel before ticket data arrives. |

## Signal sources

| `source_product` | `source_type` | Action |
|---|---|---|
| `signals_scout` | `cross_source_issue` | Deliberately skipped: the scout gate is enabled by server default; creating a row would only be needed to opt out. |
| `health_checks` | `health_issue` | Enabled; config `01a06a5d-7a52-7452-bcf4-99b56dcbc944`. |
| `error_tracking` | `issue_created` | Enabled; config `01a06a5d-7a55-7f3f-a0ca-a45e581d5358`. |
| `error_tracking` | `issue_reopened` | Enabled; config `01a06a5d-7a4f-7cab-8da3-9ccc33e8eb6c`. |
| `error_tracking` | `issue_spiking` | Enabled; config `01a06a5d-7b0d-7558-bd7c-c736f82821dd`. |
| `conversations` | `ticket` | Enabled; config `01a06a5d-7a50-7445-92d6-6984ea526f1a`. |
| `github` | `issue` | Enabled; config `01a06a62-f777-7153-9278-96525787f2c0`; source status is running. |
| `session_replay` | `session_analysis_cluster` | Deliberately skipped: this retired source is replaced by Replay Vision scanners. |
| `replay_vision` | — | Deliberately skipped: each scanner's `emits_signals: true` setting is its source authorization. |

## Connected tools

| Tool | Selection and result |
|---|---|
| GitHub Issues | Selected and connected by this setup. Self-driving reads the incremental `issues` warehouse table and its responder is enabled. |
| Linear, Jira, Sentry, Zendesk, and hidden catalog tools | Not used — not selected in the connected-tools prompt. |

## Scout troop

The troop has **4 active** scouts and **23 disabled** scouts. The enforced daily limit is **100 runs**, with **0 runs used today** and **100 remaining**. The server banner states: “Scouts are in early access. Each project gets up to 100 scout runs a day. Contact team-self-driving@posthog.com if you need more.”

### Enabled

| Scout | Why it is active |
|---|---|
| `signals-scout-general` | Always-on cross-product coverage for correlations and surfaces without a specialist. |
| `signals-scout-product-analytics` | The repository defines product funnels for booking, landscape planning, skate-trick analysis, trivia, and authentication. |
| `signals-scout-web-analytics` | The product serves browser flows with pageview capture and public user journeys. |
| `signals-scout-feature-flags` | Client and server code both include feature-flag evaluation support. |

### Disabled

| Scout | Reason it remains disabled |
|---|---|
| `signals-scout-ai-observability` | OpenAI is used in the application, but PostHog LLM trace telemetry is not confirmed. |
| `signals-scout-anomaly-detection` | No saved insight or dashboard usage was confirmed; generic coverage is more relevant at this stage. |
| `signals-scout-apm` | No PostHog APM or OpenTelemetry usage was confirmed. |
| `signals-scout-conversations` | Support was just enabled and no inbound channel or ticket activity is configured yet. |
| `signals-scout-csp-violations` | No PostHog CSP reporting configuration was found. |
| `signals-scout-customer-analytics` | No B2B group/account analytics usage was confirmed. |
| `signals-scout-data-pipelines` | No CDP destinations, batch exports, or Hog flows were confirmed. |
| `signals-scout-data-warehouse` | The new GitHub source is monitored by its responder; warehouse-health specialization is not yet needed. |
| `signals-scout-error-tracking` | Covered by the native Error Tracking responders. |
| `signals-scout-experiments` | No active A/B experiment usage was confirmed. |
| `signals-scout-health-checks` | Native health-check responder is enabled; the selective troop stays small. |
| `signals-scout-inbox-validation` | Fresh setup has no shipped Self-driving fixes to validate. |
| `signals-scout-insight-alerts` | No configured insight-alert usage was confirmed. |
| `signals-scout-logs` | PostHog Logs usage was not confirmed. |
| `signals-scout-mcp-tool-calls` | MCP telemetry is not a core product surface for this application. |
| `signals-scout-observability-gaps` | Kept off to reserve troop capacity; generic and product analytics coverage are enabled. |
| `signals-scout-replay-vision` | No pre-existing Replay Vision observations existed; the two new scanners provide the recording route. |
| `signals-scout-revenue-analytics` | No payment SDK or revenue data was found. |
| `signals-scout-session-replay` | Covered by the Replay Vision scanners below. |
| `signals-scout-skills-store` | Skill-store hygiene is not a product surface for this application. |
| `signals-scout-surveys` | No survey usage was found. |
| `signals-scout-tasks` | PostHog Tasks usage was not confirmed. |
| `signals-scout-web-vitals` | Web Vitals capture was not confirmed; web analytics is the higher-priority web specialist. |

Disabled specialists can be enabled later from the inbox when their associated product surface is adopted.

## Custom scouts

No custom scouts were created: the proposal was declined.

Two product-specific candidates were considered and declined:

- **Booking availability and confirmation liveness** — would have caught entry-volume or handoff silence in the booking flow, complementing the active product-analytics scout’s steady-entrant conversion checks.
- **AI-assisted creation workflow completion** — would have watched landscape planning and skate-trick analysis starts that do not reach a completed or saved result; this remains distinct from AI observability because no PostHog trace telemetry is configured.

Error tracking and replay analysis were ruled out because native responders and Replay Vision scanners own those routes. Generic product conversion and web-traffic behavior are covered by the active built-in scouts. If a future custom scout proves noisy, set its config’s `emit` value to `false` in PostHog to keep it in dry-run mode.

## Replay Vision scanners

A scanner is an LLM that watches individual session recordings on a schedule and pushes what it finds to the inbox. Scanners are the only part of this setup that spends Replay Vision quota; findings carry half weight and require corroboration before becoming an inbox report.

No recordings existed during setup. Both scanners are armed and will begin scanning when recording data arrives.

| Brief | Scanner | Status | Scope and purpose | Sampling | Estimate |
|---|---|---|---|---|---|
| Breakage monitor | **Booking flow breakage** (`01a06a66-7870-7099-b361-f2d4ffeba8cd`) | created | Sessions whose URL contains `/booking`, the public appointment completion journey and its immediate steps. Watches visibly failed availability loading, slot selection, form submission, and confirmation. | 0.5 | 0 observations/month; 0 credits/month (5 credits per observation). |
| Frustration monitor | **Personal app frustration** (`01a06a66-7841-7f37-9891-f0a43af1cd1d`) | created | Sessions with `$rageclick` only; this disjoint activity-based scope watches visible struggle in booking, landscape, skate-trick, and trivia interactions. | 1.0 | 0 observations/month; 0 credits/month (5 credits per observation). |

The organization had 7,500 Replay Vision credits remaining and was not exhausted when the estimates were created. Both monitors have `emits_signals` enabled.

## Follow-ups

- [ ] Connect an inbound Support channel (email, inbox, or Slack) in PostHog so the enabled Support ticket responder can receive ticket data.
- [ ] Generate or wait for real browser traffic so Session Replay starts producing recordings; the two Replay Vision monitors will then activate automatically.
- [ ] Reauthorize the MCP connection with `property_definition:read` if server-side event-schema confirmation is needed. The repository event contract was used for custom-scout analysis because that read scope was unavailable.

## What happens next

Fresh scout configurations are picked up by the coordinator within about 30 minutes and draw from the 100-runs-per-day project budget. Findings cluster into reports in the [Self-driving inbox](https://us.posthog.com/project/431281/inbox); immediately actionable reports can begin coding tasks.

## Files modified or created

- Created `posthog-self-driving-report.md`.
- Three design-system JavaScript files were updated to capture HTMX errors, table sorting, and theme-toggle events. No environment files or PostHog secrets were modified.
