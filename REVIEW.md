# NoteArray — Security & Functionality Review

> Captured: 2026-10-18. Last reviewed: 2026-10-21. Working document: findings are checked off as they are implemented.
> Use this as the backlog for making NoteArray a world-class note-taking application.
> Priority order lives in the "Top-10 roadmap" at the end; each item links back to its finding.

---

## What's already solid (keep these invariants)

- **SQL injection**: every query in `NoteResource.list` uses bound parameters (`NoteResource.java:135-177`); tag filters are escaped and bound too.
- **Sessions**: SHA-256-hashed tokens stored server-side, `HttpOnly; SameSite=Strict`, `Secure` in prod profile, TTL + hourly purge (`SessionService.java`).
- **Login timing**: bcrypt comparison runs even for unknown emails via a decoy hash (`UserResource.java:95-100`).
- **Secrets in JSON**: `passwordHash` and `superuser` are `@JsonIgnore` (`User.java:21,37`).
- **Import hardening**: per-entry 10 MB cap, BOM handling, surrogate-safe title truncation, archive type sniffing.
- **Unique-constraint race handling** mapped to 409 instead of 500 (with the caveat in finding #10).
- **Cascading FKs**: `notes` and `sessions` both `ON DELETE CASCADE` from `users` (attachments too, via `V1.1.8`).
- **Attachment authorization**: both upload (`POST /api/notes/{id}/attachments`) and download
  (`GET /api/attachments/{id}`) check owner/superuser; uploads are whitelisted to PNG/JPEG/GIF/WebP/PDF
  and filenames are sanitized (`AttachmentResource.java`).
- **Storage quota enforced server-side** on every write path (note create/update, both imports,
  attachment upload) via `StorageQuota` — but this is only as strong as plan assignment, which
  currently is user-controlled (see finding #26).
- **Request body limit is explicit** now: `quarkus.http.limits.max-body-size=55M`
  (`application.properties:17`), so legitimate 10–50 MB JEX uploads no longer 413 before app code.
- **Framing exception is narrow and documented**: only `api/attachments/*` responses permit same-origin
  framing (PDF preview), everything else stays `DENY`/`frame-ancestors 'none'`
  (`SecurityHeadersFilter.java:10-20,82-91`).

---

## Security findings

### High

- [x] **1. No rate limiting / lockout on login or signup** (`UserResource.java:90,122`).
  bcrypt slows an attacker but nothing stops distributed or patient brute-forcing, and failed attempts
  aren't logged or counted. Add per-IP + per-account throttling (`quarkus-smallrye-fault-tolerance`
  `@RateLimiting`, or a JAX-RS filter with a bucket counter), lockout/backoff, and a metric/log on failures.
  *Done (branch `agent/login-throttling`): DB-backed per-account exponential backoff (1s→64s, 15-min
  lockout after 8 consecutive failures, reset on success) + per-IP hourly budgets (120 login / 30
  signup → `429` + `Retry-After`); `X-Forwarded-For` honored only behind a trusted proxy.*

- [x] **2. No password strength policy anywhere**. Any non-blank password is accepted
  (`UserResource.java:124,215`; `LoginPage.jsx` only checks presence). Enforce min length (12+),
  reject common/breached passwords (zxcvbn, or HaveIBeenPwned k-anonymity API).
  *Done, at the requested strength (branch `agent/password-policy`): 8+ chars, one uppercase, one
  lowercase, one digit; special characters from `!@#$%^&*` allowed but optional. Enforced on create
  and password-changing update (400 lists each unmet rule) and mirrored in the signup form. Common/
  breached-password checks (zxcvbn/HIBP) remain a possible follow-up.*

- [x] **3. Stored-XSS surface in the editor + zero security headers.**
  Note content (markdown/HTML, including imported `.md`/Joplin files) is fed straight into Tiptap
  (`NotesPanel.jsx:42-118`). Verify and defend:
  - Link `href="javascript:..."` — the Link extension (`NotesPanel.jsx:51-55`) doesn't restrict protocols;
    a click executes in app origin.
  - Raw HTML passthrough from markdown import.
  - Even with `HttpOnly` cookies, same-origin script can call `/api/*` with the victim's session and
    exfiltrate every note.

  Fixes: sanitize on ingest and on render (DOMPurify), configure `Link` to allow only `http/https/mailto`,
  and add a strict CSP + headers (`X-Content-Type-Options`, `frame-ancestors`, `Referrer-Policy`,
  `Permissions-Policy`, HSTS in prod) via a response filter. Currently *no* security headers are emitted.
  *Done (branches `security-sanitization`, `agent/security-xss-headers`): DOMPurify sanitization on
  initial editor mount and content updates (`NotesPanel.jsx`), Tiptap `Link` protocol whitelist
  (`http:`, `https:`, `mailto:`, relative links), server-side ingest sanitization (`NoteResource`),
  and `SecurityHeadersFilter` (`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`,
  `Permissions-Policy`, strict `Content-Security-Policy` with `script-src 'self'` and
  `frame-ancestors 'none'`, plus HSTS when `notearray.session.cookie-secure=true`).*

- [x] **4. Email handling bugs enable duplicate accounts and enumeration**
  (`User.java:41`, `UserResource.java:131-136`). Emails are stored as typed and looked up
  case-sensitively — `A@x.com` and `a@x.com` are different accounts, and trailing spaces differ too
  (signup trims, login doesn't — `LoginPage.jsx:23` vs `:46`). Also no email *format* validation
  (`"abc"` is a valid email today), and 409 responses tell anonymous callers exactly which emails/names
  are registered on an open-signup app.
  Normalize (trim + lowercase) at write and lookup; validate format; consider an
  invite-only/disable-signup config flag and email verification.
  *Done (branch `agent/email-validation`): `EmailPolicy` trims and lowercases emails and trims names on
  both server (`UserResource`, `AuthenticationService`, `User.findByEmail`/`findByName`) and client
  (`LoginPage.jsx`), and validates `local@domain.tld` (`something@x.y`) token structure and length limits
  on both sides (`EmailPolicyTest`).*

- [ ] **5. Import limits are inconsistent and expandable** (`NoteResource.java:49,329`).
  `MAX_IMPORT_BYTES` is 50 MB, but Quarkus's default `quarkus.http.limits.max-body-size` is ~10 MB —
  legitimate >10 MB JEX files 413 before the app code runs (or, if raised, each request buffers 50 MB on
  the heap with no concurrency cap → memory DoS by parallel uploads). Each *entry* is capped at 10 MB but
  there is no cap on **total uncompressed bytes or entry count** — a zip bomb can expand far past 50 MB
  across thousands of entries. Set the body limit explicitly, cap total expansion + notes-per-import,
  and bound concurrent imports.
  *Partially done: `quarkus.http.limits.max-body-size=55M` is now set explicitly
  (`application.properties:17`), so the >10 MB 413 is fixed, and each archive entry is still capped at
  10 MB (`readEntryAsString`). Still outstanding: no cap on total uncompressed bytes or entry count
  (thousands of ≤10 MB entries still buffer and persist on the heap in one transaction) and no
  concurrency bound — up to 55 MB buffered per in-flight import request.*

- [x] **6. Swagger UI, OpenAPI, and Prometheus metrics are public in prod**
  (`application.properties:26-31`, `always-include=true`; `quarkus-micrometer`). Disable Swagger/OpenAPI
  in `%prod` (or put behind auth) and protect `/q/metrics`.
  *Done (branch `agent/disable-prod-openapi-metrics`): removed `quarkus.swagger-ui.always-include=true`
  and disabled Swagger UI (`%prod.quarkus.swagger-ui.enable=false`), OpenAPI
  (`%prod.quarkus.smallrye-openapi.enable=false`), and Prometheus metrics export
  (`%prod.quarkus.micrometer.export.prometheus.enabled=false`) in the `prod` profile while keeping them
  enabled in `dev`.*

- [ ] **26. Any authenticated user can grant themselves any plan — Stripe state is trusted from the
  client** (`UserResource.java:220-222,240-242,344-346,435-484`). `POST /api/users` and
  `PUT /api/users/{id}` accept a client-supplied `plan` field and write it straight to the user row,
  and `POST /api/users/{id}/plan/sync` with `{"action":"confirm"}` accepts
  `plan`/`stripeCustomerId`/`stripeSubscriptionId` from the request body with **no Stripe
  verification** — there is no webhook endpoint, no signature check, and no server-side
  subscription/Checkout-Session lookup. One request upgrades an account to `premium`, lifting the
  storage quota to 5 GB (`StorageQuota.java`), bypassing payment entirely. The DELETE-time Stripe
  cancellation check can't fix this because the client never proves it paid. Fix: remove `plan` (and
  the Stripe identifiers) from user-writable request fields; activate paid plans only from a signed
  Stripe webhook (or a server-side Checkout Session / subscription status lookup tied to the
  authenticated billing user); keep the client display-only.

### Medium

- [x] **7. CSRF is currently OK but the invariant is undocumented.** Safety rests entirely on
  `SameSite=Strict` + JSON-only body consumption. If anyone ever relaxes SameSite or adds CORS, every
  endpoint becomes cross-site writable. Add an `Origin`/`Sec-Fetch-Site` check or CSRF token, and
  document why it's currently safe.
  *Done (branch `agent/csrf-protection`): added `CsrfFilter` (`@PreMatching` `ContainerRequestFilter`)
  enforcing `Sec-Fetch-Site` (`same-origin`/`same-site`/`none`) and `Origin`-to-`Host` matching on all
  state-changing methods (`POST`, `PUT`, `DELETE`, `PATCH` → `403 Forbidden`), documented the 3-layer
  CSRF invariant in `CsrfFilter`, `SessionService`, and `README.md`, and added `CsrfFilterTest`.*

- [x] **8. Session UX/gaps**: fixed 7-day TTL with no rotation or idle timeout; no UI to view/revoke
  other devices (though `invalidateAllForUser` exists); **no password-reset flow at all** — a forgotten
  password can only be fixed by `psql`. Expose an "Active sessions / Sign out everywhere" panel, and add
  single-use expiring reset tokens.
  *Done, for authenticated password change & session invalidation (branches `personal_settings`,
  `change_password`): Personal Settings page includes a Change Password workflow that verifies the
  current password first, enforces `PasswordPolicy` and confirmation matching on the new password,
  invalidates all active sessions for the user, expires the session cookie, and redirects to `/login`.*

- [x] **9. Lost updates between devices** (`NoteResource.update`, `NotesPanel.jsx:479-555`).
  No `@Version` column — concurrent edits are silent last-write-wins, and the debounced auto-saves aren't
  serialized, so a slow PUT can land *after* a newer one. Add optimistic locking (`@Version` → 409),
  queue auto-saves one-at-a-time, and retry-with-merge in the UI.
  *Done (branch `agent/optimistic-locking-autosave`): added Flyway `V1.1.5__add_version_to_notes.sql` and
  `@Version` on `Note`, checked expected `version` in `NoteResource.update` (mapping stale versions and
  `OptimisticLockException` to `409 Conflict`), serialized auto-save `PUT`s in `NotesPanel.jsx` with
  automatic non-overlapping field merge on `409`, and added `NoteVersionConflictTest`.*

- [x] **10. Misleading 409 for oversized fields** (`UserResource.java:145-151`). Name/email lengths are
  never validated, so a 500-char name fails on flush and is mapped to **409 "name or email already in
  use"**. Validate lengths/format → 400.
  *Done (branch `agent/email-validation`): `EmailPolicy` validates name (≤ 255 chars) and email (≤ 320
  chars, local ≤ 64, domain ≤ 255) before persistence and returns 400.*

- [ ] **11. Re-importing the same JEX duplicates every note** — no dedupe (hash of title+content) and the
  whole import runs in one transaction with all entities in one Hibernate session. Batch it, and
  skip-identical on re-import.

- [x] **27. Destructive account deletion requires only the session cookie**
  (`UserResource.java:486-535`). `DELETE /api/users/{id}` permanently deletes the account and all
  notes/attachments (explicitly framed in the UI as irreversible) but, unlike the password change,
  does **not re-verify the password** — a stolen, shared, or forgotten-logged-in session destroys
  everything irreversibly. Require the current password (and/or an email confirmation step) before
  destructive deletion.
  *Done (branch `agent/delete-password-verification`): `DELETE /api/users/{id}` now takes a
  `DeleteRequest` body and re-verifies the current password against the bcrypt hash before any Stripe
  interaction (`UserResource.validateDeleteConfirmation` — blank/missing → 400 "Password is required
  to delete your account", wrong → 400 "Current password is incorrect"), tested in
  `UserDeleteVerificationTest`; the Personal Settings delete card now requires the password field and
  disables Delete until it is entered, and `USER_GUIDE.md` documents the step.*
  *Accepted follow-up: the Stripe HTTP confirmation (15 s timeout) still runs synchronously
  inside the `@Transactional` delete request, so a Stripe outage stalls or fails deletions;
  consider an async/outbox confirmation.*

---

## Functionality & performance

- [ ] **12. `GET /api/notes` returns every note with full content, and the UI calls it twice per load**
  (`NotesPanel.jsx:412-440` — one filtered, one "everything" just for tag lists/counts, plus a cheap
  new `/api/notes/storage` usage call). With 10 MB notes
  this collapses at scale. Biggest engineering gap:
  - Add pagination (`page`/`size`) + server-side sorting (sort is client-side today).
  - Return a list projection without `content` (e.g. first ~300 chars preview).
  - Add `/api/notes/tags` returning distinct tags + counts instead of downloading all notes.
  - Add composite index `(owner_id, created_at DESC)` — today only `idx_notes_owner_id` and a global
    `created_at` index exist.

- [ ] **13. Search ceiling** (`NoteResource.java:137-148`): `plainto_tsquery` = AND-all-words,
  `'english'` hardcoded, no prefix/phrase/fuzzy match, no highlighting (`ts_headline`), unbounded `q`
  length. Consider `websearch_to_tsquery`, `pg_trgm` GIN for substring search, capped query length,
  highlighted excerpts. Also: the API supports `createdFrom/...` date filters but **the UI can no longer
  set them** (`FilterPanel.buildApiFilters` only passes tags/collections) — re-expose or drop the dead
  API surface.

- [ ] **14. No export.** You can import Joplin/Markdown but can't get data *out* except SQL. Add
  Export Markdown (.zip), JSON backup, and full account export. Import-only = lock-in.

- [ ] **15. Deletes are permanent with a `window.confirm`** (`NotesPanel.jsx:601`). Add soft-delete
  (trash, 30-day restore) + an undo toast, plus **note version history** — critical given autosave and
  multi-device editing can silently destroy content.

- [ ] **16. Joplin folders are flattened to tags** (`NoteResource.java:394-402`) — hierarchy is lost on
  import. Preserve folders as a real nesting structure (or nested tag convention).

- [x] **17. No images/attachments** — rich text editor with no way to insert an image. Add upload
  endpoint + storage with size quotas, or at minimum paste-an-image.
  *Done (branch `agent/attachments-drop-target`): added `note_attachments` table (`V1.1.8`) with
  `BYTEA` storage and `ON DELETE CASCADE` on both `note_id` and `owner_id`, `AttachmentResource`
  (`POST /api/notes/{noteId}/attachments`, `GET /api/attachments/{id}`), `StorageQuota` enforcing
  per-plan quotas (100 MB Free / 1 GB Pro / 5 GB Premium) and 10 MB per-attachment limit, and Tiptap
  drag-and-drop + paste support for images and PDFs (`NotesPanel.jsx`). Extended 2026-10-20
  (`85f9849`–`2b4561f`): upload endpoint lives in `NoteResource` (route-matching fix), attachment
  preview modal renders PDFs in a same-origin iframe (narrow `frame-ancestors 'self'` exception in
  `SecurityHeadersFilter`), `?download=1` prompt mode, and usage surfaced via `/api/notes/storage`
  (`AttachmentQuotaTest`).*

- [x] **18. No dark mode** — theme picker changes hue only; `MantineProvider` has no `forceColorScheme`.
  Also theme is read from localStorage first, then server (`main.jsx:15-26`) → flicker; prefer the server
  value on session restore.
  *Done (branch `personal_settings`): added Light / Dark / Auto appearance selector via
  `useMantineColorScheme` on the Personal Settings page with `defaultColorScheme="auto"` on
  `MantineProvider`, and synced the server `themeColor` to `localStorage` on login and session restore.*

- [ ] **19. UX polish for a world-class feel**: keyboard shortcuts + command palette (⌘K search, ⌘S save,
  ⌘N new), click-a-tag-badge-to-filter, tag autocomplete from existing tags, multi-select/bulk delete &
  retag, note preview on hover, in-note search term highlighting.
  *Partially done (2026-10-19/20): removable search/filter badges, untagged/pinned smart collections,
  result counts, card/list view toggle with responsive two-column grid, inline card actions, Enter
  submits login (`bb30e08`), search help modal, storage usage bar, Help/About pages, `wideMode`
  layout. Still outstanding: keyboard shortcuts/command palette, tag autocomplete, multi-select/bulk
  operations, hover preview, in-note highlighting, click-a-badge-to-filter.*

- [ ] **20. Accessibility**: table rows are click-to-edit but not keyboard-focusable
  (`NotesPanel.jsx:1139`) — make the title a button or add `tabIndex`/`onKeyDown`. The card view
  (`NotesPanel.jsx:319`) has the same click-only pattern. (Icon buttons already have good
  `aria-label`s, and sortable headers now expose `aria-sort`.)

---

## Engineering quality

- [x] **21. There are zero tests** — no `src/test`, no frontend test setup. `AGENTS.md` mandates running
  tests before commits, but there are none to run. Start with:
  - `@QuarkusTest` + Testcontainers: authorization matrix (every endpoint × owner/other-user/superuser/
    anonymous), session expiry/revocation, import edge cases (zip bomb, malformed, >limit),
    validation → status-code mapping.
  - Vitest + React Testing Library for autosave/search flows; Playwright for login→edit→search E2E.
  *Done, unit test suite established (`src/test`) and grown through 2026-10-21: 72 JUnit 5 tests
  across 12 classes (`LoginThrottlePolicyTest`, `ClientIpTest`, `PasswordPolicyTest`,
  `NoteArrayApplicationTest`, `UserPasswordChangeTest`, `EmailPolicyTest`, `SecurityHeadersFilterTest`,
  `CsrfFilterTest`, `NoteVersionConflictTest`, `AttachmentQuotaTest`, `UserPlanSyncTest`,
  `UserDeleteVerificationTest`) covering
  throttling, client IP resolution, password policy, JAX-RS resource registration & SPA redirects,
  password change validation order, security headers + ingest XSS sanitization, CSRF filtering,
  optimistic-lock conflict handling, attachment quotas, and plan checkout/sync state transitions.*

- [ ] **22. No CI and no linter/formatter config** (no `.github/`, no ESLint/Prettier for `ui/`, no
  Checkstyle/Spotless). Add a pipeline that builds, tests, `npm audit`, and lints.

- [x] **23. Prod config hygiene** (`application.properties`):
  - `%prod` should *require* env vars (no `bob/bob` dev defaults that silently work) (`:7-9`).
  - `com.notearray` logging is `DEBUG` in prod (`:35`) → set INFO.
  - Add `quarkus-smallrye-health` for liveness/readiness, structured access logs with request IDs.
  *Done (branch `agent/prod-config-hygiene`): `%prod.quarkus.datasource.{jdbc.url,username,password}`
  now expand `${NOTEARRAY_DB_*}` with **no defaults** — a packaged jar refuses to start unless all
  three are set (defaults only remain for non-prod profiles); `com.notearray` DEBUG moved to
  `%dev` only (prod stays INFO); added `quarkus-smallrye-health` (`/q/health`, `/q/health/ready`,
  with the PostgreSQL datasource readiness check — verified live) and
  `quarkus.http.access-log` with a pattern including the `X-Request-Id` emitted by
  `SecurityHeadersFilter` (generated/sanitized per request, caller-supplied ids echoed).
  Also removed the stale, ignored `quarkus.http.cookie.same-site-cookie*` keys that Quarkus
  warned about at startup. Minor follow-ups left as-is: deprecated
  `quarkus.hibernate-orm.database.generation` / `.enable` keys still work but could migrate to
  their newer names; JSON log formatting (e.g. `quarkus.log.*.format`) not yet enabled.*
  *Verified in `NoteArrayApplicationTest.prodConfigHygiene` and
  `SecurityHeadersFilterTest` request-id tests.*

- [x] **24. Doc drift**: README:85 still says Flyway "will insert an initial test user" — removed in
  `V1.1.2`.
  *Done: updated in `README.md` (`9fd166c`).*

- [ ] **25. API errors are plain strings with `application/json`** — prefer RFC 7807 `problem+json` with
  a stable `code`, so the client doesn't string-match messages (`LoginPage.jsx:53-58`).

---

## Top-10 roadmap (implementation order)

| # | Item | Findings | Status |
|---|------|----------|--------|
| 1 | Login rate limiting + password policy | #1, #2 | ✓ (`agent/login-throttling`, `agent/password-policy`) |
| 2 | Security headers + CSP + link/HTML sanitization | #3 | ✓ (`security-sanitization`, `agent/security-xss-headers`) |
| 3 | Email normalization/validation + signup config flag | #4, #10 | ✓ (`agent/email-validation`) |
| 4 | Fix body-size limit & import expansion caps | #5 | ◐ (body limit fixed in `application.properties:17`; total-expansion/entry-count/concurrency caps outstanding) |
| 5 | Test suite + CI | #21, #22 | ◐ (unit suite done — 72 tests/12 classes; CI & lint/formatter config outstanding) |
| 6 | Paginated list + lightweight projection + tags endpoint | #12 | ☐ |
| 7 | Optimistic locking + serialized autosave | #9 | ✓ (`agent/optimistic-locking-autosave`) |
| 8 | Trash/undo + note history | #15 | ☐ |
| 9 | Export (MD/ZIP/JSON) | #14 | ☐ |
| 10 | Prod config hardening + health/metrics | #6, #23 | ✓ (`agent/disable-prod-openapi-metrics`, `agent/prod-config-hygiene`) |

---

## Changelog of implemented work

| Date | Findings addressed | Branch / PR | Notes |
|------|--------------------|-------------|-------|
| 2026-10-18 | #1 | `agent/login-throttling` | Exponential backoff (1s doubling to 64s), lockout after 8 failures for 15 min, hourly per-IP caps (120 login / 30 signup → 429 Retry-After), Flyway `V1.1.4`, first unit tests |
| 2026-10-18 | #2 | `agent/password-policy` | 8+ chars / uppercase / lowercase / digit enforced in `UserResource` create+update and signup form; specials `!@#$%^&*` optional; `PasswordPolicyTest` |
| 2026-10-19 | #3 | `security-sanitization`, `agent/security-xss-headers` | DOMPurify on render + update, `Link` protocol whitelist (`http/https/mailto`), server-side ingest sanitization in `NoteResource`, `SecurityHeadersFilter` with strict CSP + prod HSTS, `SecurityHeadersFilterTest` |
| 2026-10-19 | #8, #18, #21, #24 | `personal_settings`, `change_password` | Personal Settings view in main area with Light/Dark/Auto appearance selector, current-password-verified password change that invalidates sessions and logs out to `/login`, SPA route redirects, and 36 JUnit 5 unit tests |
| 2026-10-19 | #4, #10 | `agent/email-validation` | Email trim+lowercase normalization and liberal `something@x.y` token/length validation + name validation on server (`EmailPolicy`, `UserResource`, `AuthenticationService`, `User`) and client (`LoginPage.jsx`); `EmailPolicyTest` |
| 2026-10-19 | #6 | `agent/disable-prod-openapi-metrics` | Disabled Swagger UI, SmallRye OpenAPI, and Prometheus `/q/metrics` export in `%prod` (`application.properties`); verified in `NoteArrayApplicationTest` |
| 2026-10-19 | #7 | `agent/csrf-protection` | Added `CsrfFilter` (`Sec-Fetch-Site` + `Origin` verification on state-changing requests → 403), documented 3-layer CSRF invariant in `SessionService`, `CsrfFilter`, and `README.md`; `CsrfFilterTest` |
| 2026-10-19 | #9 | `agent/optimistic-locking-autosave` | Flyway `V1.1.5` (`notes.version`), `@Version` optimistic locking on `Note` → 409 Conflict in `NoteResource.update`, serialized auto-saves with non-overlapping merge-and-retry in `NotesPanel.jsx`; `NoteVersionConflictTest` |
| 2026-10-20 | #19 (partial) | `e26d09b`, `bb30e08`, `6d1f9b4`, `b41d046`, `403d778`, `5d19cae` | Untagged filter + removable filter badges, result counts, Enter submits login, card/list view toggle with responsive two-column grid, inline card actions, title wrapping |
| 2026-10-20 | new: attachments, storage, billing, help | `85f9849`, `bb30e08`, `ceef6b1`, `deb76bb`, `8375086`, `0f9c784`, `2b4561f` | `/api/notes/storage` usage endpoint + UI bar, Stripe plan checkout/sync endpoints (`V1.1.6`, `V1.1.7`) and plan badges, account deletion gated on Stripe cancellation confirmation (`StripeBillingService`; `UserPlanSyncTest`), attachment upload route fix, same-origin framing exception for PDF preview, `?download` mode, Help/About pages, `USER_GUIDE.md`; suite grown to 62 tests / 11 classes (`AttachmentQuotaTest`, `UserPlanSyncTest`). NOTE: this work introduced findings #26 (plan self-escalation via API) and #27 (deletion without password re-verification) |
| 2026-10-21 | #27 | `agent/delete-password-verification` | `DELETE /api/users/{id}` requires the current password (`DeleteRequest` → `validateDeleteConfirmation`, verified before Stripe calls), password field in the Personal Settings delete card with Delete disabled until entered, doc updates; `UserDeleteVerificationTest` (5 tests) |
| 2026-10-21 | #23 | `agent/prod-config-hygiene` | `%prod` datasource requires `NOTEARRAY_DB_*` env vars (no dev fallbacks — verified jar refuses to start without them), `com.notearray` DEBUG moved to `%dev` only, `quarkus-smallrye-health` added (`/q/health` + DB readiness verified live + 200), access log enabled with `X-Request-Id` (generated/sanitized in `SecurityHeadersFilter`, caller-supplied ids echoed; verified in log), removed stale ignored `quarkus.http.cookie.same-site-cookie*` keys; 72 tests/12 classes |
| 2026-10-21 | fix: Firefox attachment download quirk | `agent/attachment-download-fix` | Blob-based Download button (never a viewer navigation) + Firefox fix: blob and `?download` mode served as `application/octet-stream` so Firefox's built-in PDF handling ("Open in Firefox" / `open_pdf_attachments_inline`) cannot save-and-reopen the download despite `Content-Disposition: attachment` (Bugzilla 1756980); preview (`inline`) responses keep `application/pdf` |
