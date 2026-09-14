# Frontend UI Architecture

This project uses Bootstrap 5, Thymeleaf, HTMX, and Alpine.js together. The goal is to keep Bootstrap as the visual foundation while giving each interaction a single owner.

## Interaction ownership

|         Tool         |             Responsibility             |                        Examples                        |
|----------------------|----------------------------------------|--------------------------------------------------------|
| Native HTML          | Simple navigation and browser behavior | Links, selects, form validation, tenant switching      |
| HTMX                 | Server-rendered updates                | Form submissions, alerts, table and fragment refreshes |
| Alpine.js            | Local client-side state                | Theme toggles, tabs, local UI state, media controls    |
| Bootstrap JavaScript | Components that need it                | Modals and tooltips                                    |
| Bootstrap CSS        | Visual system                          | Grid, spacing, forms, tables, cards, responsive layout |

An element should not be controlled by multiple interaction systems. For example, a tenant selector should be a native select, not a Bootstrap dropdown also managed by HTMX and Alpine.

## Conventions

- Use Bootstrap classes for layout and visual consistency.
- Use native links and controls for navigation whenever possible.
- Use HTMX for server-rendered HTML changes; return focused fragments.
- Use Alpine for state that does not need a server round trip.
- Use Bootstrap JavaScript only when the component requires it.
- Keep Alpine state local to the component that owns it.
- Use `x-cloak` for Alpine-managed elements that should not flash before initialization.
- Use Thymeleaf expressions for tenant-aware URLs and all static resources.
- Add loading and error states to HTMX requests.
- Do not make Bootstrap, Alpine, and HTMX manage the same state or event.

## Migration path

1. Preserve the existing Bootstrap layout and styles.
2. Replace unnecessary Bootstrap JavaScript interactions with native HTML controls.
3. Keep HTMX for Foosball player/game updates and server-rendered fragments.
4. Use Alpine only for local state, such as theme toggling and last-game form behavior.
5. Remove stale JavaScript and template field names as each fragment is touched.
6. Add browser regression checks for tenant switching, HTMX game recording, modal behavior, and mobile layout.
7. Reconsider the UI foundation only if Bootstrap cannot support a required design system or component.

## First steps

The Foosball module is the first reference implementation:

- Tenant switching uses a native select and direct navigation.
- Player and game updates use HTMX fragments.
- The game form submits tenant-local player IDs.
- Bootstrap remains responsible for layout, forms, cards, and modals.
- Alpine or imperative JavaScript is limited to local modal/form behavior.

When adding or changing a UI component, identify its owner before writing markup or JavaScript. Verify the behavior with `mvn test`, `mvn spotless:check`, and a browser check for user-facing changes.
