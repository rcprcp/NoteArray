# NoteArray — Security & Functionality Review

> Captured: 2026-10-18. Working document: findings are checked off as they are implemented.
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
- **Cascading FKs**: `notes` and `sessions` both `ON DELETE CASCADE` from `users`.

---

## Security findings

### High

- [ ] **1. No rate limiting / lockout on login or signup** (`UserResource.java:90,122`).
  bcrypt slows an attacker but nothing stops distributed or patient brute-forcing, and failed attempts
  aren't logged or counted. Add per-IP + per-account throttling (`quarkus-smallrye-fault-tolerance`
  `@RateLimiting`, or a JAX-RS filter with a bucket counter), lockout/backoff, and a metric/log on failures.

- [ ] **2. No password strength policy anywhere**. Any non-blank password is accepted
  (`UserResource.java:124,215`; `LoginPage.jsx` only checks presence). Enforce min length (12+),
  reject common/breached passwords (zxcvbn, or HaveIBeenPwned k-anonymity API).

- [ ] **3. Stored-XSS surface in the editor + zero security headers.**
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

- [ ] **4. Email handling bugs enable duplicate accounts and enumeration**
  (`User.java:41`, `UserResource.java:131-136`). Emails are stored as typed and looked up
  case-sensitively — `A@x.com` and `a@x.com` are different accounts, and trailing spaces differ too
  (signup trims, login doesn't — `LoginPage.jsx:23` vs `:46`). Also no email *format* validation
  (`"abc"` is a valid email today), and 409 responses tell anonymous callers exactly which emails/names
  are registered on an open-signup app.
  Normalize (trim + lowercase) at write and lookup; validate format; consider an
  invite-only/disable-signup config flag and email verification.

- [ ] **5. Import limits are inconsistent and expandable** (`NoteResource.java:49,329`).
  `MAX_IMPORT_BYTES` is 50 MB, but Quarkus's default `quarkus.http.limits.max-body-size` is ~10 MB —
  legitimate >10 MB JEX files 413 before the app code runs (or, if raised, each request buffers 50 MB on
  the heap with no concurrency cap → memory DoS by parallel uploads). Each *entry* is capped at 10 MB but
  there is no cap on **total uncompressed bytes or entry count** — a zip bomb can expand far past 50 MB
  across thousands of entries. Set the body limit explicitly, cap total expansion + notes-per-import,
  and bound concurrent imports.

- [ ] **6. Swagger UI, OpenAPI, and Prometheus metrics are public in prod**
  (`application.properties:26-31`, `always-include=true`; `quarkus-micrometer`). Disable Swagger/OpenAPI
  in `%prod` (or put behind auth) and protect `/q/metrics`.

### Medium

- [ ] **7. CSRF is currently OK but the invariant is undocumented.** Safety rests entirely on
  `SameSite=Strict` + JSON-only body consumption. If anyone ever relaxes SameSite or adds CORS, every
  endpoint becomes cross-site writable. Add an `Origin`/`Sec-Fetch-Site` check or CSRF token, and
  document why it's currently safe.

- [ ] **8. Session UX/gaps**: fixed 7-day TTL with no rotation or idle timeout; no UI to view/revoke
  other devices (though `invalidateAllForUser` exists); **no password-reset flow at all** — a forgotten
  password can only be fixed by `psql`. Expose an "Active sessions / Sign out everywhere" panel, and add
  single-use expiring reset tokens.

- [ ] **9. Lost updates between devices** (`NoteResource.update`, `NotesPanel.jsx:479-555`).
  No `@Version` column — concurrent edits are silent last-write-wins, and the debounced auto-saves aren't
  serialized, so a slow PUT can land *after* a newer one. Add optimistic locking (`@Version` → 409),
  queue auto-saves one-at-a-time, and retry-with-merge in the UI.

- [ ] **10. Misleading 409 for oversized fields** (`UserResource.java:145-151`). Name/email lengths are
  never validated, so a 500-char name fails on flush and is mapped to **409 "name or email already in
  use"**. Validate lengths/format → 400.

- [ ] **11. Re-importing the same JEX duplicates every note** — no dedupe (hash of title+content) and the
  whole import runs in one transaction with all entities in one Hibernate session. Batch it, and
  skip-identical on re-import.

---

## Functionality & performance

- [ ] **12. `GET /api/notes` returns every note with full content, and the UI calls it twice per load**
  (`NotesPanel.jsx:265-274` — one filtered, one "everything" just for tag lists/counts). With 10 MB notes
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

- [ ] **17. No images/attachments** — rich text editor with no way to insert an image. Add upload
  endpoint + storage with size quotas, or at minimum paste-an-image.

- [ ] **18. No dark mode** — theme picker changes hue only; `MantineProvider` has no `forceColorScheme`.
  Also theme is read from localStorage first, then server (`main.jsx:15-26`) → flicker; prefer the server
  value on session restore.

- [ ] **19. UX polish for a world-class feel**: keyboard shortcuts + command palette (⌘K search, ⌘S save,
  ⌘N new), click-a-tag-badge-to-filter, tag autocomplete from existing tags, multi-select/bulk delete &
  retag, note preview on hover, in-note search term highlighting.

- [ ] **20. Accessibility**: table rows are click-to-edit but not keyboard-focusable
  (`NotesPanel.jsx:800`) — make the title a button or add `tabIndex`/`onKeyDown`.
  (Icon buttons already have good `aria-label`s.)

---

## Engineering quality

- [ ] **21. There are zero tests** — no `src/test`, no frontend test setup. `AGENTS.md` mandates running
  tests before commits, but there are none to run. Start with:
  - `@QuarkusTest` + Testcontainers: authorization matrix (every endpoint × owner/other-user/superuser/
    anonymous), session expiry/revocation, import edge cases (zip bomb, malformed, >limit),
    validation → status-code mapping.
  - Vitest + React Testing Library for autosave/search flows; Playwright for login→edit→search E2E.

- [ ] **22. No CI and no linter/formatter config** (no `.github/`, no ESLint/Prettier for `ui/`, no
  Checkstyle/Spotless). Add a pipeline that builds, tests, `npm audit`, and lints.

- [ ] **23. Prod config hygiene** (`application.properties`):
  - `%prod` should *require* env vars (no `bob/bob` dev defaults that silently work) (`:7-9`).
  - `com.notearray` logging is `DEBUG` in prod (`:35`) → set INFO.
  - Add `quarkus-smallrye-health` for liveness/readiness, structured access logs with request IDs.

- [ ] **24. Doc drift**: README:85 still says Flyway "will insert an initial test user" — removed in
  `V1.1.2`.

- [ ] **25. API errors are plain strings with `application/json`** — prefer RFC 7807 `problem+json` with
  a stable `code`, so the client doesn't string-match messages (`LoginPage.jsx:53-58`).

---

## Top-10 roadmap (implementation order)

| # | Item | Findings | Status |
|---|------|----------|--------|
| 1 | Login rate limiting + password policy | #1, #2 | ☐ |
| 2 | Security headers + CSP + link/HTML sanitization | #3 | ☐ |
| 3 | Email normalization/validation + signup config flag | #4 | ☐ |
| 4 | Fix body-size limit & import expansion caps | #5 | ☐ |
| 5 | Test suite + CI | #21, #22 | ☐ |
| 6 | Paginated list + lightweight projection + tags endpoint | #12 | ☐ |
| 7 | Optimistic locking + serialized autosave | #9 | ☐ |
| 8 | Trash/undo + note history | #15 | ☐ |
| 9 | Export (MD/ZIP/JSON) | #14 | ☐ |
| 10 | Prod config hardening + health/metrics | #6, #23 | ☐ |

---

## Changelog of implemented work

| Date | Findings addressed | Branch / PR | Notes |
|------|--------------------|-------------|-------|
|      |                    |             |       |
