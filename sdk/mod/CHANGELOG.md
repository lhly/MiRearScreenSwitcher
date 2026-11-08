# ReMRSS Mod SDK changes

## 1.1.0 / RC3 — 2026-11-08

- Add bounded native list, conditional visibility, formatting, icon, semantic tone/size and layout alignment nodes; align Host, CLI, JSON Schema and TypeScript contracts.
- Add native elapsedRealtime `deadlineText`, clock context, visible schedule and registered-action effects/selectors. Schedule clocks use safe integers; hidden scenes stop ticking and do not replay missed intervals.
- Match runtime event schemas to production payloads: `host.schedule.tick.causes` identifies interval/deadline, and effect results accept epoch-qualified action IDs.
- Connect the settings action picker to optional top-level config fields and rear buttons; unset refs stay disabled, and only opaque owner registry IDs can reach the host callback.
- Add a Debug-only same-author reload package flow and bounded single-Mod diagnostics; keep normal versionCode upgrades and dataVersion migrations on the standard import path.
- Ship the kit sources with the ReMRSS v4.0.0-rc.3 preview.

## 1.0.5 candidate — 2026-11-06

- Clarify `host.configChanged` refusals as per-Save outcomes; preserve committed model and logical observers while the host confirms old-worker shutdown and reloads the committed model into a new worker before retry.
- Allow `tests/config-changes.json` to assert bounded Effect intent; author-side tests do not claim host admission.
- Document that config-save Effects are prevalidated and dispatched only after commit; `rear.open` is rejected there because Save cannot mint user activation.


## 1.0.4 — 2026-11-06

- Add host-origin `host.configChanged`: script Mods derive state from saved config in the same revision; failures preserve the prior model and effects are forbidden.
- Add `tests/config-changes.json` with schema-checked success/refusal cases; keep no-script saves on the direct no-VM path.
- Update the generic counter template so Save immediately commits its derived display state.


## 1.0.3 — 2026-11-06

- Bind nullable migration output to input state: an initialized object state cannot migrate to `null`.
- Require migration test fixtures to include `state` explicitly; omission is not treated as JSON null.
- Keep null-state migration for a never-activated Mod and continue validating object outputs against the target schemas.

## 1.0.2 — 2026-11-06

- Define migrations for never-activated packages: `state` may be `null` and should remain null until the updated Mod's first activation calls `init`.
- `remrss test` accepts null-state migration cases and validates non-null migration results against the target state/config schemas.
- Preserve existing bounded `layout.row` behavior from 1.0.1.

## 1.0.1 — 2026-11-05

- Document the stable `layout.row` sizing contract: children divide the remaining width evenly after gaps and are measured within bounded cells.
- Pair this SDK kit with host candidate `platformmodrowfix` for small landscape layouts.
- No UI schema, TypeScript type, manifest capability, or reducer ABI changed.
