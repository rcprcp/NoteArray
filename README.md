# NoteArray

A modern note-taking application built with **Quarkus** (Java 25), **Panache ORM**, **PostgreSQL**, and a **React** UI with **Vite** and **Mantine**. Works great on desktop, tablet, and mobile devices.

## Documentation

| Document | Audience | Contents |
|---|---|---|
| **README.md** (this file) | developers & operators | features, REST API, configuration, build |
| **[USER_GUIDE.md](USER_GUIDE.md)** | end users | day-to-day use, plans and billing, troubleshooting |
| **[DEVOPS.md](DEVOPS.md)** | operators | fresh-server deployment, nginx/TLS, backups, runbook |
| **[STRIPE.md](STRIPE.md)** | operators | Stripe products, Payment Links, webhook setup |
| **[SECURITY_SCAN.md](SECURITY_SCAN.md)** | developers & operators | security scan results and hardened defaults |
| **[REVIEW.md](REVIEW.md)** | developers | code-review findings and their resolutions |

## Project Structure

```
NoteArray/
├── pom.xml                                    # Maven configuration with Java 25 and Quarkus
├── src/
│   ├── main/
│   │   ├── java/com/notearray/
│   │   │   ├── NoteArrayApplication.java       # JAX-RS application root (@ApplicationPath "/")
│   │   │   ├── entity/
│   │   │   │   ├── BaseEntity.java            # Base class with UUID id and timestamps
│   │   │   │   ├── User.java                  # User entity: unique name/email, theme_color, last_login, superuser, plan + Stripe state
│   │   │   │   ├── Note.java                  # Note entity with title, markdown content, tags, pinned flag, version
│   │   │   │   ├── NoteAttachment.java        # Attachment blob (BYTEA) + filename/content type, orphan tracking
│   │   │   │   ├── Session.java               # Session entity backing persistent logins (token hash, user, expiry)
│   │   │   │   └── LoginThrottle.java         # Throttle state (per-email backoff, per-IP hourly windows)
│   │   │   ├── resource/
│   │   │   │   ├── UserResource.java          # Users: CRUD + login/logout, plan checkout/sync, throttle, password/email policy
│   │   │   │   ├── NoteResource.java          # Notes: CRUD, search, filters, storage, imports, attachment upload/list
│   │   │   │   ├── AttachmentResource.java    # Attachment download/preview (?download=1) and delete
│   │   │   │   ├── StripeWebhookResource.java # Signature-verified, downgrade-only Stripe lifecycle webhook
│   │   │   │   └── SpaResource.java           # SPA route handler and unauthenticated redirect to /login
│   │   │   ├── security/
│   │   │   │   ├── CsrfFilter.java            # Origin / Sec-Fetch-Site check for state-changing requests
│   │   │   │   └── SecurityHeadersFilter.java # Per-request X-Request-Id trace header (security headers live in application.properties)
│   │   │   └── service/
│   │   │       ├── SessionService.java        # Persisted session create/resolve/invalidate + scheduled purge
│   │   │       ├── AuthenticationService.java # BCrypt credential check for login (timing-safe for unknown emails)
│   │   │       ├── LoginThrottleService.java  # Rate-limit decisions: backoff/lockout + per-IP hourly budgets
│   │   │       ├── LoginThrottlePolicy.java   # Pure backoff rules (1s doubling to 64s, lockout after 8 failures)
│   │   │       ├── ClientIp.java              # Client address for rate limiting (X-Forwarded-For opt-in)
│   │   │       ├── PasswordPolicy.java        # Password rules (8+ chars, uppercase, lowercase, digit)
│   │   │       ├── EmailPolicy.java           # Email/name normalization and format validation
│   │   │       ├── StorageQuota.java          # Plan storage limits and per-attachment size cap
│   │   │       ├── AttachmentCleanupService.java # Orphan marking, note-deletion settlement, scheduled purge
│   │   │       └── StripeBillingService.java  # Stripe Checkout verification and subscription cancellation (see STRIPE.md)
│   │   └── resources/
│   │       ├── application.properties         # Quarkus config (DB, Flyway, OpenAPI, sessions, limits, headers, Stripe)
│   │       └── db/migration/                  # Flyway migrations V1.0.0 … V1.1.9 (20 files)
│   └── test/java/com/notearray/               # 146 JUnit 5 unit tests across 19 classes
│       ├── NoteArrayApplicationTest.java      # JAX-RS registration and SPA route redirects
│       ├── resource/                          # AttachmentQuota, ImportLimits, JoplinAttachmentImport,
│       │                                      # JoplinFolderTags, NoteVersionConflict, StripeWebhook,
│       │                                      # TagPrefixFilter, UserDeleteVerification,
│       │                                      # UserPasswordChange, UserPlanSync
│       ├── security/                          # CsrfFilterTest, SecurityHeadersFilterTest
│       └── service/                           # AttachmentCleanupService, ClientIp, EmailPolicy,
│                                              # LoginThrottlePolicy, PasswordPolicy, StripeBillingService
├── ui/                                        # React + Vite frontend (mobile-responsive)
│   ├── src/
│   │   ├── App.jsx                            # App shell, responsive header, routes (/login, /notes, /settings, /help/*)
│   │   ├── HeaderActions.jsx                  # Dynamic right-to-left header action overflow menu
│   │   ├── HelpPage.jsx                       # In-app FAQ, Privacy Policy and Security Notes pages
│   │   ├── PersonalSettings.jsx               # Profile, appearance, password, storage meter, plans, delete account
│   │   ├── main.jsx                           # React entry point with session check, theme and plan syncing
│   │   ├── api.js                             # Fetch wrapper for REST API (includes credentials)
│   │   ├── filters.js                         # Persisted list filters, API serialization, active-filter chips
│   │   ├── LoginPage.jsx                      # Login and signup page
│   │   ├── NotesPanel.jsx                     # Notes UI: editor, tags, search panel, attachments, import, auto-save
│   │   ├── ThemeButton.jsx                    # Theme color palette constants and picker
│   │   ├── UserMenu.jsx                       # Legacy user menu components (unused)
│   │   └── index.css                          # Mobile-first responsive styles
│   ├── vite.config.js                         # Vite configuration
│   ├── index.html                             # HTML template with viewport meta
│   └── package.json                           # Node dependencies
└── README.md
```

## Prerequisites

- **Java 25** (JDK)
- **Maven 3.9+** (required by Quarkus 3.40)
- **Node.js 20.19+** (or 22.12+) and **npm**
- **PostgreSQL** running locally (or configure DB connection)

## Getting Started

### 1. Database Setup

Create the PostgreSQL database and user:

```sql
CREATE USER bob WITH PASSWORD 'bob';
CREATE DATABASE bob OWNER bob;
GRANT ALL PRIVILEGES ON DATABASE bob TO bob;
```

### 2. Backend Setup (Quarkus)

```bash
# Run in development mode (with live reload)
mvn quarkus:dev
```

The backend will run on `http://localhost:8080`.

Flyway will automatically create/migrate the schema on startup.

### 3. Frontend Setup (React + Vite)

In a separate terminal:

```bash
cd ui

# Install dependencies
npm install

# Run development server (with hot reload)
npm run dev
```

The frontend dev server will run on `http://localhost:3000` and proxy API calls to the backend.

### 4. Access the Application

Open your browser to **`http://localhost:3000`** (or access on any device on your network).

You will see a login page. Create an account with the signup form; the first account created can be granted superuser access (see below).

### Initial Account

No account is seeded. Sign up through the login page to create the first user. To grant a user superuser access, use `psql`:

```sql
UPDATE users SET superuser = TRUE WHERE email = 'you@example.com';
```

## Database Schema

### `users` Table
- `id` (UUID, primary key)
- `name` (VARCHAR 255, unique, required)
- `email` (VARCHAR 320, unique, required; trimmed and lowercased on write)
- `password_hash` (VARCHAR 255, BCrypt hashed, never returned in API responses)
- `theme_color` (VARCHAR 32, Mantine color name, default: `'blue'`)
- `last_login` (TIMESTAMP, nullable, updated on successful login)
- `superuser` (BOOLEAN, default: FALSE, read-only for application, set via psql only)
- `plan` (VARCHAR 32, default `'free'`; `free`, `pro` or `premium`)
- `plan_status` (VARCHAR 32, default `'active'`; also `pending_checkout`, `pending_cancellation`, `past_due`, `canceled`)
- `pending_plan` (VARCHAR 32, nullable, target tier while a Stripe Checkout is pending)
- `stripe_customer_id` / `stripe_subscription_id` (VARCHAR 255, nullable)
- `plan_updated_at` (TIMESTAMP, nullable, last plan/Stripe transition)
- `created_at` (TIMESTAMP, set on insert)
- `updated_at` (TIMESTAMP, auto-updated on modification)

### `notes` Table
- `id` (UUID, primary key)
- `owner_id` (UUID, foreign key → users.id, ON DELETE CASCADE)
- `title` (VARCHAR 255, required)
- `content` (TEXT, stores markdown-formatted content, max ~10MB)
- `tags` (VARCHAR 10000, space-delimited tags, default empty)
- `pinned` (BOOLEAN, default: FALSE, for favorite/pinned notes)
- `version` (BIGINT, default: 0, `@Version` optimistic locking counter incremented on each update)
- `title_tsv` (tsvector, auto-generated full-text search index on title)
- `content_tsv` (tsvector, auto-generated full-text search index on content, truncated to 500KB)
- `created_at` (TIMESTAMP, set on insert)
- `updated_at` (TIMESTAMP, auto-updated on modification)

Indexed on `owner_id` for efficient owner-based queries. GIN indexes on `title_tsv` and `content_tsv` for fast full-text search.

### `note_attachments` Table
- `id` (UUID, primary key)
- `note_id` (UUID, foreign key → notes.id, ON DELETE CASCADE) – the note the file was uploaded to
- `owner_id` (UUID, foreign key → users.id, ON DELETE CASCADE)
- `filename` (VARCHAR 255), `content_type` (VARCHAR 128), `byte_size` (BIGINT)
- `data` (BYTEA, the binary file; max 10 MB per file)
- `orphaned_at` (TIMESTAMP, nullable) – set when no note links the file any more; the purge job deletes it after the grace window
- `created_at` / `updated_at` (TIMESTAMP)

Indexed on `note_id`, `owner_id`, and (partially) `orphaned_at`. Attachments are **linked from note markdown**
(`/api/attachments/{id}`), not owned exclusively by one note: a file referenced by several notes is kept until the
last link is gone. Because blobs live in PostgreSQL, they are included in a normal `pg_dump` backup.

### `sessions` Table
- `token_hash` (VARCHAR 64, primary key, SHA-256 hash of the session token; the raw token is never stored)
- `user_id` (UUID, foreign key → users.id, ON DELETE CASCADE)
- `created_at` (TIMESTAMP, when the login was created)
- `expires_at` (TIMESTAMP, when the login expires)
- Indexed on `user_id` and `expires_at`
- A user may have many rows, i.e. be signed in on multiple devices at once

### `login_throttle` Table

Rate-limiting state for the login and signup endpoints (added in `V1.1.4`). Rows are keyed by an opaque key so per-account backoff and per-IP budgets share one table:

- `throttle_key` (VARCHAR 320, primary key) – `email:<lowercased email>` for failed-login backoff, `login-ip:<address>` / `signup-ip:<address>` for hourly request windows
- `consecutive_failures` (INTEGER) – consecutive failed logins for an email key
- `window_count` (INTEGER) / `window_started_at` (TIMESTAMP) – requests seen in the current hourly window for an IP key
- `next_allowed_at` (TIMESTAMP, nullable) – when set, the key's next attempt must wait until this instant
- `updated_at` (TIMESTAMP) – supports the hourly purge of stale rows (scheduled job)

Rows are read under a pessimistic lock so concurrent login attempts cannot lose increments.

## Authentication & Session Management

### How It Works

1. **Login:** POST `/api/users/login` with email and password sets an HttpOnly, SameSite=Strict session cookie.
2. **Session:** The session cookie is automatically included in all subsequent requests; the server looks up the owning user from the persisted session.
3. **Logout:** POST `/api/users/logout` clears the persisted session and expires the cookie.
4. **Protected Routes:** All endpoints require authentication. Unauthenticated requests return **401**.

Sessions are **persisted in a dedicated `sessions` table in PostgreSQL** (via Hibernate Panache),
so they survive application restarts and are shared across multiple application instances that use
the same database. A user may be **signed in on multiple devices at once**; each login is its own
row, so signing out on one device leaves the others signed in.

Only a **SHA-256 hash** of the token is stored in the database; the raw token lives solely in
the browser cookie, so a leaked database snapshot cannot be replayed. Sessions expire after
**7 days**; expired sessions are rejected and deleted on access, and a scheduled job
(`quarkus-scheduler`, hourly) also purges stale rows. Changing a password invalidates **all** of
that user's sessions, expires the current session cookie, and redirects the user to the login
screen so they must sign in again with the new password.

### Session Cookies & CSRF Protection

- **HttpOnly:** Cannot be accessed by JavaScript; protects against XSS attacks.
- **SameSite=Strict:** Prevents browsers from attaching `NOTEARRAY_SESSION` to cross-site requests.
- **Non-form Content-Type:** All state-changing endpoints require `application/json` (or `application/octet-stream` for imports), which HTML `<form>` submissions cannot produce and which forces a CORS preflight on cross-origin `fetch`/`XHR`.
- **Origin & `Sec-Fetch-Site` Filter (`CsrfFilter`):** Every state-changing request (`POST`, `PUT`, `DELETE`, `PATCH`) is checked by `CsrfFilter`: requests with `Sec-Fetch-Site: cross-site`, `Origin: null`, or a mismatched `Origin` host are rejected with **403 Forbidden**, ensuring endpoints remain protected even if `SameSite` or CORS settings are ever relaxed.
- **Secure:** Added in the `prod` profile by default (`notearray.session.cookie-secure=true`). Requires HTTPS; set it to `false` if you run the production JAR over plain HTTP (e.g. LAN testing).
- **Automatic:** Handled by the browser; no manual token management needed.

### Rate Limiting & Lockout

Failed logins are throttled per account, and both auth endpoints are budgeted per client IP:

- **Per-account exponential backoff** – after a failed login, the next attempt for that email is delayed 1s, then 2s, 4s … up to 64s. The 8th consecutive failure locks the account out for 15 minutes. A successful login clears the counter. The email is throttled even when no such account exists, so throttling never reveals which addresses are registered.
- **Per-IP hourly budgets** – 120 login and 30 signup requests per hour per client address (configurable, see Production Configuration).
- Exceeding a budget or hitting a locked account returns **429 Too Many Requests** with a `Retry-After` header and a message such as `too many attempts; try again in 30 seconds`. Short backoff waits (≤ 5 seconds) are held server-side before the request is processed.
- State lives in the `login_throttle` table, so limits survive application restarts and are shared across multiple application instances; stale rows are purged hourly by a scheduled job.
- Client addresses come from the socket peer by default. Behind a trusted reverse proxy that overwrites `X-Forwarded-For`, set `notearray.rate-limit.trust-proxy=true` so limits key on the real client — leave it `false` when the app is directly exposed, otherwise clients can spoof the header to bypass the per-IP budgets.

### Password Policy

Passwords are validated wherever they can be set (signup and password-changing updates):

- At least **8 characters**
- At least one **uppercase** letter
- At least one **lowercase** letter
- At least one **number**
- Special characters from `!@#$%^&*` are allowed but **optional**

Violations return **400** listing each unmet rule (e.g. `password must be at least 8 characters`). The signup form checks the same rules client-side and shows a hint under the password field; the server remains authoritative.

## Authorization & Access Control

### User Access Rules

- **Regular users** can only view/edit/delete their own account and notes.
- **Superusers** (set via `psql` only) can view/edit/delete any user account and any note.
- **Unauthorized access** returns **404** (not found), not 403, to prevent UUID enumeration.

### Making a User a Superuser

On the database server, use `psql`:

```sql
UPDATE users SET superuser = TRUE WHERE email = 'user@example.com';
```

The user will have full access after their next login.

### Revoking Superuser Status

```sql
UPDATE users SET superuser = FALSE WHERE email = 'user@example.com';
```

**Note:** The `superuser` flag is:
- **Read-only** in the application code (never inserted or updated by the app)
- **Hidden from the UI** (marked with `@JsonIgnore` in the API response)
- **Only visible** in the database and in code logic

## API Endpoints

### Authentication

- `POST /api/users/login` – Authenticate and set session cookie
  - **Request body:** `{ "email": "...", "password": "..." }`
  - **Response:** User object on success (password hash and superuser flag not returned)
  - Returns 400 if email or password is missing
  - Returns 401 if credentials are invalid
  - Returns 429 with a `Retry-After` header if the account is in backoff/lockout or the IP's hourly login budget is exhausted
  - Sets session cookie on success; updates `last_login` timestamp

- `POST /api/users/logout` – Invalidate session and log out
  - **Request body:** (empty)
  - **Response:** 204 No Content
  - Deletes the session row and expires the session cookie
  - Only signs out the current device; other sessions for the same user remain active

- `GET /api/users/me` – Get the current authenticated user
  - **Response:** User object (password hash and superuser flag not returned)
  - Returns 401 if not authenticated

### Users

- `POST /api/users` – Create a new user (public, no authentication required)
  - **Request body:** `{ "name": "...", "email": "...", "password": "..." }`
  - **Response:** User object
  - Returns 400 if any required field is missing, the email/name is malformed, the password fails the policy (see Password Policy), or a `plan` is supplied (`plan cannot be set directly; use /plan/checkout`)
  - Returns 409 if name or email already exists
  - Returns 429 with a `Retry-After` header when the IP's hourly signup budget is exhausted

- `GET /api/users` – List all users (superusers only)
  - **Response:** Array of user objects
  - Returns 401 if not authenticated
  - Returns 403 if caller is not a superuser

- `GET /api/users/{id}` – Get a specific user
  - **Response:** User object
  - Returns 401 if not authenticated
  - Returns 404 if user doesn't exist or caller lacks permission (non-superusers can only view themselves)

- `PUT /api/users/{id}` – Update a user (all fields optional)
  - **Request body:** `{ "name": "...", "email": "...", "currentPassword": "...", "password": "...", "confirmPassword": "...", "themeColor": "..." }`
  - **Response:** Updated user object
  - Returns 401 if not authenticated
  - Returns 404 if user doesn't exist or caller lacks permission
  - Returns 409 if new name or email is already in use
  - When changing a password, `currentPassword` is verified against the stored hash **first**; only after it is confirmed valid is the new `password` validated against the Password Policy and checked against `confirmPassword` (returning 400 on any failure)
  - Superusers can update any user; regular users can only update themselves
  - Changing the password invalidates **all** of that user's sessions and expires the caller's session cookie so they must log in again with the new password

- `DELETE /api/users/{id}` – Delete a user
  - **Request body:** `{ "currentPassword": "..." }` (required)
  - **Response:** 204 No Content
  - Returns 401 if not authenticated
  - Returns 404 if user doesn't exist or caller lacks permission
  - Returns 400 if the password is missing or does not match (`Password is required to delete your account` / `Current password is incorrect`)
  - Returns 400 while a Stripe checkout is pending (“cancel or complete it first”)
  - For paid plans the subscription is cancelled with Stripe **first** and deletion proceeds only once Stripe
    confirms; otherwise the request fails with **502** (`Could not confirm subscription cancellation with Stripe…`),
    so no recurring charge is left behind
  - Cascades to all notes and attachments owned by that user
  - Superusers can delete any user; regular users can only delete themselves

### Plans & Billing

Plan state is written **only** by the server: the tier is never accepted from a request body, paid activation
requires verifying a Stripe Checkout session with the server's API key, and the webhook is downgrade-only.

- `POST /api/users/{id}/plan/checkout` – Start or change a plan
  - **Request body:** `{ "plan": "free" | "pro" | "premium" }`
  - **Response:** `{ "user", "checkoutUrl", "status" }`
  - `free` cancels any live Stripe subscription first, then clears the plan and subscription id;
    if Stripe cannot confirm the cancellation the request fails with **502** and the paid plan is left
    unchanged, so a downgrade can never leave a recurring charge behind
  - `pro` / `premium` return the configured Stripe Payment Link URL and mark the account `pending_checkout`
  - Returns 400 for a missing/unknown plan, **502** when a required Stripe cancellation is not confirmed;
    401/404 as for other user endpoints

- `POST /api/users/{id}/plan/sync` – Confirm or cancel a pending checkout
  - **Request body:** `{ "action": "confirm" | "cancel", "checkoutSessionId": "cs_..." }`
  - `confirm` requires `checkoutSessionId`; the session is fetched from Stripe and the plan is taken from the verified
    price id, never from the request. Returns **400** without a session id, **422** if Stripe cannot verify it
  - `cancel` clears the pending state
  - `past_due` / `canceled` cannot be reported by a client — those are webhook-only

- `POST /api/stripe/webhook` – Stripe lifecycle webhook (public, no session)
  - Requires a valid `Stripe-Signature` HMAC-SHA256 header; missing/invalid/stale signatures return **400**
  - Returns **503** when `notearray.stripe.webhook-secret` is not configured
  - Handles downgrade-only events (subscription deleted → `free`, payment failure → `past_due`); unknown events return 200
  - See [STRIPE.md](STRIPE.md) for dashboard setup

### Notes – CRUD

- `GET /api/notes` – List notes
  - **Response:** Array of note objects, sorted by `createdAt` descending
  - Returns 401 if not authenticated
  - Superusers see all notes; regular users see only their own notes

- `GET /api/notes/{id}` – Get a specific note
  - **Response:** Note object
  - Returns 401 if not authenticated
  - Returns 404 if note doesn't exist or caller lacks permission

- `POST /api/notes` – Create a new note
  - **Request body:** `{ "title": "...", "content": "...", "tags": "..." }`
  - **Response:** Note object with id and timestamps
  - Returns 401 if not authenticated
  - Returns 400 if `title` is missing/blank or `content` is missing (send `""` for an empty note)
  - Content is stored as markdown
  - Tags are space-delimited and optional
  - Note is always owned by the authenticated user (ownerId is not accepted in the request)

- `PUT /api/notes/{id}` – Update a note
  - **Request body:** `{ "title": "...", "content": "...", "tags": "...", "pinned": true|false, "version": 0 }` or any combination
  - **Response:** Updated note object (with incremented `version`)
  - Returns 401 if not authenticated
  - Returns 404 if note doesn't exist or caller lacks permission
  - Returns 400 if title is blank
  - Returns 409 if `version` is provided and does not match the current persisted version (optimistic lock conflict)
  - Superusers can update any note; regular users can only update their own
  - Content is stored as markdown
  - Tags are space-delimited
  - `pinned` field allows marking notes as favorites

- `DELETE /api/notes/{id}` – Delete a note
  - **Response:** 204 No Content
  - Returns 401 if not authenticated
  - Returns 404 if note doesn't exist or caller lacks permission
  - Superusers can delete any note; regular users can only delete their own
  - Attachments are settled in the same request: a file that another note still links is kept (re-homed to that
    note), a file only this note used is removed immediately, and a file this note referenced but that lives on
    another note starts its orphan grace clock

### Notes – Attachments

- `POST /api/notes/{id}/attachments?filename=...` – Upload one image (PNG/JPEG/GIF/WebP) or PDF (body is the raw file, max 10 MB)
  - **Response:** 201 with `{ "id", "noteId", "filename", "contentType", "byteSize", "url" }`
  - Returns 400 for an unsupported type or empty body, 413 when the file exceeds 10 MB or the plan quota would be exceeded
  - Counts against the plan's storage
- `GET /api/notes/{id}/attachments` – Metadata for the attachments the note's text **links to** (a file shared by several notes is listed by each of them)
- `GET /api/attachments/{id}` – Download/preview the bytes (`?download=1` forces a save)
- `DELETE /api/attachments/{id}?fromNote={noteId}` – Delete an attachment
  - `fromNote` names the note whose link is being removed, so its own reference does not block the delete
  - Returns 409 while **another** note still links the file; 404 for unknown or foreign attachments

Attachments whose link is removed from every note are reclaimed automatically: the save that drops the last reference
marks the row (`orphaned_at`), and a scheduled job deletes it once it is older than `notearray.attachments.orphan-grace`
(default `PT24H`), leaving auto-save, undo and cut/paste a safety window. The job runs every
`notearray.attachments.purge-interval` (default `1h`).

Deleting a **note** settles its attachments in the same request: files another note still links are re-homed to that note
and kept, files only the deleted note used are removed immediately, and files the deleted note referenced but that live
on another note start their grace clock right away. Deleting an **account** removes every note and attachment through
`ON DELETE CASCADE` — instantly and completely.

### Notes – Storage

- `GET /api/notes/storage` – the authenticated user's usage:
  - **Response:** `{ "usedBytes", "limitBytes", "noteCount", "notesBytes", "attachmentsBytes", "overQuota" }`
  - `notesBytes` is the note markdown (`OCTET_LENGTH(content)`), `attachmentsBytes` the binary attachments, and
    `usedBytes` their sum — so clients can show the plan meter split by kind
  - Limits are per plan: **free 100 MB**, **pro 1 GB**, **premium 5 GB**. Creating notes/imports and uploading
    attachments is refused with **413** at the limit, while shrinking or deleting an existing note is always allowed

### Notes – Filters

- **GET** `/api/notes` also accepts filter parameters, combined with AND (and with any search query):
  - `tags` – repeatable query parameter; a note must have every listed tag to match. A value ending with `/` selects a whole folder subtree: `?tags=grq/` matches the token `grq` itself and every deeper token (`grq/cobol`, …).
  - `tagPrefix` – repeatable query parameter for hierarchical (`grandparent/parent/child`) tags; a note matches when it carries the token itself or any deeper token. Example: `?tagPrefix=work` matches `work` and `work/2024`
  - List responses include `attachmentCount` per note, so clients can show an attachment indicator without fetching blobs
  - `untagged` – `true` to return only notes without any tags
  - `hasAttachments` – `true` to return only notes that have at least one attachment
  - `pinned` – `true` to return only pinned notes
  - `createdFrom` / `createdTo` – ISO-8601 instants for note creation date range (inclusive)
  - `updatedFrom` / `updatedTo` – ISO-8601 instants for note modification date range (inclusive)
  - Invalid date values return 400
  - All filters are combined with AND logic
  - Examples:
    - `?tags=work` – notes tagged with "work"
    - `?tags=work&tags=important` – notes tagged with both "work" AND "important"
    - `?tagPrefix=work` – notes in folder `work` or any sub-folder of it
    - `?untagged=true` – notes with no tags
    - `?hasAttachments=true` – notes that have attachments
    - `?pinned=true` – pinned notes only
    - `?createdFrom=2024-01-01T00:00:00Z&createdTo=2024-12-31T23:59:59Z` – notes created in 2024
    - `?tags=work&pinned=true&updatedFrom=2024-10-01T00:00:00Z` – pinned work notes updated since Oct 1

### Notes – Search

- `GET /api/notes?q=...&searchTitles=true&searchContent=true` – Full-text search
  - **Query parameters:**
    - `q` – Search query (required when searching)
    - `searchTitles` – Search in note titles (boolean, default false)
    - `searchContent` – Search in note content (boolean, default false)
    - `searchTags` – Search the tags as a partial match (boolean, default false): every tag token containing the query matches, so `q=sup` finds tags like `super-duper` and `supper`, and `q=grq` finds notes in folders `grq` and `grq/cobol`. Notes with no tags at all are included too, since they have no tags to match
  - **Response:** Array of matching note objects, sorted by `createdAt` descending
  - Returns 401 if not authenticated
  - Returns 400 if q is provided but neither searchTitles nor searchContent nor searchTags is true
  - Search is word-based, case-insensitive, and ignores common stop words (like "the")
  - Word endings are normalized (e.g., "running" matches "run")
  - Superusers search all notes; regular users search only their own
  - Can be combined with filter parameters: `?q=bug&searchTitles=true&tags=urgent`

### Notes – Import

- `POST /api/notes/import/joplin` – Import from Joplin JEX archive
  - **Content-Type:** `application/octet-stream`
  - **Request body:** Binary .jex file (ZIP or TAR archive), max 50 MB
  - **Response:** `{ "imported": N, "errors": N, "attachments": N, "messages": [...] }`
  - Returns 401 if not authenticated
  - Returns 400 if file is invalid
  - Returns 413 if the archive exceeds 50 MB (individual entries are also capped at 10 MB)
  - Decompressed size is capped at 256 MB cumulatively (anti zip-bomb) and at 10,000 notes per import; at most
    **2 imports** run at a time — further requests get **503** with `Retry-After`
  - The plan quota is enforced per note; if the import would overshoot the plan the whole transaction rolls back with **413**, so an import can fill but never exceed a plan
  - Detects archive format: ZIP (folder/note JSON metadata) or TAR (raw markdown export)
  - TAR (JEX) imports also bring attachments: `resources/<resourceId>.<ext>` blobs referenced by
    `[name.ext](:/<resourceId>)` links become attachments named after the link text, and the note's
    links are rewritten to `/api/attachments/{id}` (images inline, other files force-download).
    Dangling/over-10 MB resources keep their original link and are reported in the response messages
  - Folder hierarchy becomes hierarchical tags: each folder level joined with `/`
    (`Work/Projects/Acme`), folder names lowercased with whitespace becoming `_`.
    For TAR (JEX) the hierarchy is reconstructed from the archive's `type_: 2` folder items;
    for ZIP from the folder JSON metadata
  - Only note items (`type_: 1`, and todos `type_: 3` when present) are imported; folder, resource,
    and tag metadata items are skipped and summarized in the response messages. Archives with no
    Joplin metadata at all (bare markdown TARs) import every `.md` entry
  - Applies any `tags:` values found in the Joplin metadata footer as Tags
  - Strips the trailing Joplin metadata block (`id:`, `parent_id:`, etc.) from note content
  - Extracts title from first markdown heading, else the first content line, else the filename
  - Content is stored as markdown

- `POST /api/notes/import/markdown` – Import a single markdown file
  - **Content-Type:** `application/octet-stream`
  - **Request body:** Binary .md file, max 10 MB
  - **Response:** Note object
  - Returns 401 if not authenticated
  - Returns 400 if file is invalid
  - Returns 413 if the file exceeds 10 MB
  - Extracts title from first markdown heading or first line
  - Tags imported note with `markdown-import`
  - Content is stored as markdown

## OpenAPI Documentation

When the backend is running in development mode (`mvn quarkus:dev`), you can access the API documentation and metrics (these endpoints are disabled by default in the `prod` profile):

- **OpenAPI Spec (YAML):** http://localhost:8080/q/openapi
- **OpenAPI Spec (JSON):** http://localhost:8080/q/openapi?format=json
- **Swagger UI (Interactive):** http://localhost:8080/q/swagger-ui
- **Metrics (Prometheus):** http://localhost:8080/q/metrics
- **Health:** http://localhost:8080/q/health (`/q/health/live`, `/q/health/ready`)

The packaged (`prod`) build **disables all four** (OpenAPI, Swagger UI, metrics and health), so `/q/*` 404s on a
production jar. A plain `GET /` is the production availability check; see [DEVOPS.md](DEVOPS.md) §9.4.

The interactive Swagger UI allows you to test all endpoints directly from your browser.

## UI Features

### Responsive Design
- **Mobile-first approach** – Works seamlessly on phones, tablets, and desktops
- **Breakpoints:**
  - **Mobile** (<768px) – Optimized for small screens with full-width modals, card view for notes, collapsible filters
  - **Tablet** (768-1024px) – Balanced layout with responsive spacing
  - **Desktop** (>1024px) – Full-featured table view with all columns visible
- **Touch optimization** – 44px minimum touch targets on mobile, better spacing for fingers
- **Mobile features:**
  - Card-based note display (instead of table)
  - Collapsible search filter buttons
  - Full-screen modals for editing
  - Responsive typography with `clamp()` for readability
  - iOS/Android optimizations (no zoom on input, proper keyboard handling)
- **All devices:**
  - Responsive button sizing
  - Adaptive font sizes
  - Touch-friendly form inputs
  - Improved focus states for accessibility

### Login & Authentication
- **Login page** – Email and password fields (responsive)
  - Unauthenticated requests to any application route (e.g. `/`, `/overview`, `/notes`, `/settings`) redirect to `/login`
  - Invalid credentials show an error message
  - Session cookie is set on success and the user is navigated to `/notes` (the Notes List page)
  - Repeated failures are throttled with exponential backoff (1s doubling to 64s; 15-minute lockout after 8 consecutive failures, cleared by a successful login) — a throttled attempt shows a "try again in N seconds" message
- **Signup** – Create a new account from the login page
  - Name, email, and password fields
  - Password must be confirmed and meet the password policy (8+ characters, uppercase, lowercase, number; special characters optional) — shown as a hint under the field and checked client-side
  - Auto-login after successful signup
- **Personal Settings & Logout** – Clicking the avatar in the header opens the Personal Settings page in the main content area:
  - **Logout button** in the upper-right of the main content area (directly below the avatar) clears the session cookie and returns to the login page
  - **My profile** table displaying Name, Email, Plan (with Stripe status), Theme, Last login, Created, Updated, and ID
  - **Storage Used** card with a two-section bar (note text vs. attachments), plan limit, note count and percentage; the bar turns red at 90%
  - **Available Plans** cards (Free / Pro / Premium) with upgrade/downgrade, Stripe checkout return handling and a past-due alert
  - **Theme** card with **Appearance** (**Light / Dark / Auto**) above the 14 Mantine **Color** palettes
  - **Security / Change Password** form requiring Current Password (verified first), New Password, and Verify New Password; upon success, all sessions are invalidated and the user is logged out to `/login`
  - **Delete Account** card requiring the current password (and Stripe cancellation confirmation on paid plans)

### Note Management
- **List notes** – Responsive view that adapts to screen size, with a **card / list view toggle** on every device
  - **Desktop:** Table with Created, Updated, and Title columns (all sortable)
  - **Mobile:** Card view with note title, dates, and tags
  - Default sort: Created, descending (newest first)
  - Click column headers (desktop) to sort; click again to reverse direction
  - Tags displayed as badges below each note's title
  - A paperclip badge marks notes with attachments (hover shows the count)
  - Star icon to pin/unpin notes as favorites
- **Create note** – "Create note" in the header opens an inline editor with Title, Tags, and Rich Text Editor fields
  - Title is required and shown in the list
  - Tags are space-delimited (e.g., "joplin important work")
  - Content uses a full-featured markdown editor
  - The note is auto-created a couple of seconds after typing starts; auto-save takes over from there
- **Edit note** – Click a row or the Edit button to open the note in the inline editor
  - Title, Tags, and Rich Text Editor fields (all editable with live formatting)
  - Only changed fields are sent to the server on update
  - Pin/unpin toggle from the note list
- **Delete note** – Confirmation shows the note's title

### Attachments
- **Drag-and-drop or paste** images (PNG/JPEG/GIF/WebP) and PDFs directly into the editor; each file is max **10 MB**
- Images render inline; PDFs appear as a `📄` file link
- An **Attachments (n)** list under the Tags field shows Preview / Download / Delete for the files the note links to
- A **preview modal** renders the file in a same-origin frame with **Download** and **Open in New Tab**
- Dropping a file into an untitled note fills the title from the file name
- Deleting a note keeps files that another note still links; files no longer linked by any note are reclaimed after a 24 h grace period (configurable)

### Plans, Storage & Billing
- Three tiers: **Free** (100 MB), **Pro** (1 GB, $2/month) and **Premium** (5 GB, $4/month); storage counts note text **and** attachments
- Upgrading opens **Stripe Checkout** and records a pending plan; returning to the app verifies the session server-side,
  and **Cancel Checkout** clears the pending state
- Downgrading to Free cancels the paid Stripe subscription first (server-side) and only then applies the Free
  limits; if Stripe cannot be reached or does not confirm, the downgrade is refused and the paid plan stays
  active, so a recurring charge is never silently left running
- At the storage limit a banner disables **Create Note** and **Import**; deleting or shrinking notes is always allowed
- See [USER_GUIDE.md](USER_GUIDE.md) §9 and [STRIPE.md](STRIPE.md) for the operator setup

### Help & About
- The header **Help/About** menu opens the in-app **FAQ**, **Privacy Policy** and **Security Notes** pages (`/help/faq`,
  `/help/privacy`, `/help/security`)

### Tags
- **Tags field** – Space-delimited text input in note editor
  - Multiple tags separated by spaces (no special characters needed)
  - Example: `joplin project-alpha important`
  - Tags displayed as badges in the notes list
  - Useful for organizing and categorizing notes
  - Particularly useful with imported notes (tags preserve folder hierarchy from Joplin)

### Auto-Save
- **Auto-save** – Changes are saved automatically after 2 seconds of typing inactivity
  - Applies to both new and existing notes: a new note is created by the first auto-save, then updated in place
  - No data loss: all unsaved changes are persisted to the server
  - Status indicators:
    - **"Saving..."** – Request in flight
    - **"✓ Saved at HH:MM:SS"** – Recent successful save with timestamp
    - **"Unsaved changes..."** – Changes pending the 2-second debounce timer
    - **Error message** – Auto-save failed; continuing to edit retries the save
  - Closing the editor flushes any pending changes to prevent data loss
  - Only changed fields are sent to the server (optimized)
- **No data loss guarantee** – In-flight requests complete before the editor closes; debounced changes flush before the editor closes

### Rich Text Editing with Markdown
- **Formatting toolbar** – Bold, Italic, Underline, Strikethrough, Clear formatting
- **Headings** – H1, H2, H3 support
- **Lists** – Bullet lists and ordered lists
- **Code blocks** – Inline code and multi-line code blocks
- **Links** – Insert and remove links
- **Blockquotes** – Quote formatting
- **Markdown storage** – Content is stored as markdown in the database, so you can use it in any markdown viewer
- **Live preview** – Editor supports TipTap's rich text rendering with markdown shortcuts

### Advanced Search & Filtering
- **Responsive header actions** – Header includes **Create Note**, **List Notes**, **Search Notes**, **Pinned**, **Untagged**, **Import**, **Help/About** and the user avatar. When viewport width is constrained, items collapse right-to-left into an overflow (`...`) menu, and Search/Pinned/Untagged/Import controls are disabled when outside the List Notes view.
- **Search Notes panel** – One header button opens the combined search panel: the text search box with its **Search Titles / Search Note Content / Search Tags** scope checkboxes plus the **Find notes with attachments** filter. Toggled with the **Search Notes** header button
  - There is no separate tag picker: tags are found through the **Search Tags** scope, which matches any part of a tag
    (so `sup` finds `super-duper`, and `grq` finds notes in `grq` and `grq/cobol`)
- **Pinned button** – **Pinned** toggle button in the header filters directly to pinned notes
- **Untagged button** – **Untagged** toggle filters to notes with no tags
- **Active filter badges** showing which filters are applied
  - Clear individual filters with X button
  - "Clear all filters" button to reset all filters
  - Active filter count indicator
  - Filter state persists to browser `localStorage` and restores on page reload
  - **List Notes** clears the search text but keeps the Pinned / Untagged / attachment filters

### Full-Text Search
- **Search bar** – Type your search query
- **Search checkboxes** – Choose where to search
  - **Mobile:** Collapsible "Search Options" button to toggle checkboxes
  - **Desktop:** Always-visible checkboxes
  - **Search Titles** – Match words in note titles
  - **Search Note Content** – Match words in note content
  - **Search Tags** – Partial match inside tag tokens (including hierarchical `a/b/c` tags); notes with no tags are
    included because they have no tags to exclude them
  - Any combination can be checked together for comprehensive search
- **Search results** – Shows matching notes with result count
- **Smart search** – Word-based and case-insensitive for titles/content (stop words ignored, word endings normalized);
  tag matching is a partial substring match
- **Help modal** – Click search with no checkboxes to see instructions
- **Combined with filters** – Use search and filters together for powerful discovery

### Import Notes
- **Import button** – Upload notes from external sources
- **Joplin JEX import** – Import entire note collections from Joplin
  - Upload .jex files (Joplin's native export format)
  - Folder structure becomes hierarchical tags (`Work/Projects/Acme`), filterable from the Search Notes panel
  - Extracts title from markdown headings
  - Imports note attachments referenced by `[name.ext](:/…)` links, named from the link text, with the links rewritten to point at the uploaded files (images render inline; other files download on click)
  - Shows import progress and results
- **Markdown import** – Import individual markdown files
  - Upload .md files
  - Extracts title from first markdown heading or line
  - Automatically tagged with `markdown-import`
  - Preserves all markdown formatting

### Theme Selection
- **Appearance & Color Theme** (in Personal Settings):
  - **Appearance**: Switch between **Light**, **Dark**, and **Auto** (system preference) modes
  - **Color**: Choose from 14 Mantine color palettes, saved to the user's account and synced to `localStorage` on login and session restore

### XSS Protection & Security Headers
- **Render & Link Sanitization**: Note content is sanitized with **DOMPurify** on initial editor load and content updates, and the Tiptap `Link` extension restricts link protocols to `http:`, `https:`, `mailto:`, and relative URLs.
- **Ingest Sanitization**: `NoteResource` strips `<script>`, `<iframe>`, `<object>`, `<embed>` elements, inline `on*=` attributes, and `javascript:`/`vbscript:`/`data:text/html` URIs on note create, update, and import.
- **HTTP Security Headers**: Set globally via `quarkus.http.header.*` in `application.properties`, so they apply to **every** response — including the static SPA document (`/`, `/index.html`, `/assets/*`) that a JAX-RS filter would never reach: `Content-Security-Policy` (`default-src 'self'; script-src 'self'; ... frame-ancestors 'self'`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy`, and `Strict-Transport-Security` in `prod`. `frame-ancestors 'self'` / `SAMEORIGIN` (rather than `'none'` / `DENY`) is deliberate: cross-site framing — i.e. clickjacking — stays blocked, while the note preview modal can still render attachments (`GET /api/attachments/{id}`) in a same-origin `<iframe>`. `SecurityHeadersFilter` now only generates/echoes `X-Request-Id`.

### Superuser Features (if applicable)
- If you are a superuser, `GET /api/notes` returns all notes from all users
- You can view, edit, delete, and pin any user's notes
- You can search and filter across all users' notes
- You can view and manage all user accounts via API (no UI for user management in this release)

## Building for Production

> **Deploying to a server?** See **[DEVOPS.md](DEVOPS.md)** for the full operation-ready checklist:
> PostgreSQL, Java 25, systemd, nginx + Let's Encrypt, backups, upgrades/rollback, monitoring and troubleshooting.

### Build the UI

```bash
cd ui
npm run build
cd ..
```

Output: `src/main/resources/META-INF/resources/` (consumed by Quarkus)

### Build the Backend

```bash
mvn clean package
```

Output: `target/quarkus-app/` (Quarkus fast-jar layout)

### Run the JAR

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

The app will be available at `http://localhost:8080` with both the backend API and the built UI.

### Production Configuration

Database credentials are read from environment variables. In the `prod` profile there are **no defaults** — a packaged
JAR refuses to start unless all three are set (the defaults below apply to local development only):

| Variable | Dev default | Description |
|---|---|---|
| `NOTEARRAY_DB_URL` | `jdbc:postgresql://localhost:5432/bob` | JDBC connection URL |
| `NOTEARRAY_DB_USERNAME` | `bob` | Database username |
| `NOTEARRAY_DB_PASSWORD` | `bob` | Database password |

Rate-limit settings (defaults suit local development):

| Variable | Default | Description |
|---|---|---|
| `NOTEARRAY_RATE_LIMIT_LOGIN_PER_HOUR_PER_IP` | `120` | Hourly login request budget per client IP |
| `NOTEARRAY_RATE_LIMIT_SIGNUP_PER_HOUR_PER_IP` | `30` | Hourly signup request budget per client IP |
| `NOTEARRAY_RATE_LIMIT_TRUST_PROXY` | `false` | Honor `X-Forwarded-For` when resolving client IPs (only enable behind a trusted reverse proxy) |

Import and attachment bounds:

| Variable | Default | Description |
|---|---|---|
| `NOTEARRAY_IMPORT_MAX_ARCHIVE_BYTES` | `52428800` (50 MB) | Maximum compressed JEX upload |
| `NOTEARRAY_IMPORT_MAX_TOTAL_EXPANSION_BYTES` | `268435456` (256 MB) | Cumulative decompressed size cap (zip-bomb defense) |
| `NOTEARRAY_IMPORT_MAX_ENTRIES` | `10000` | Maximum notes per import |
| `NOTEARRAY_ATTACHMENTS_ORPHAN_GRACE` | `PT24H` | Grace window before an unreferenced attachment is purged |
| `NOTEARRAY_ATTACHMENTS_PURGE_INTERVAL` | `1h` | How often the orphan purge job runs |

Stripe billing (optional — leave unset to keep paid plans unavailable; full setup in [STRIPE.md](STRIPE.md)):

| Variable | Default | Description |
|---|---|---|
| `NOTEARRAY_STRIPE_API_KEY` | — | Secret key used to verify Checkout sessions and cancel subscriptions |
| `NOTEARRAY_STRIPE_PRO_PRICE_ID` | — | Stripe price id mapped to the `pro` tier during verification |
| `NOTEARRAY_STRIPE_PREMIUM_PRICE_ID` | — | Stripe price id mapped to the `premium` tier |
| `NOTEARRAY_STRIPE_PRO_CHECKOUT_URL` | — | Payment Link for Pro (must include `{CHECKOUT_SESSION_ID}` in its success URL) |
| `NOTEARRAY_STRIPE_PREMIUM_CHECKOUT_URL` | — | Payment Link for Premium |
| `NOTEARRAY_STRIPE_WEBHOOK_SECRET` | — | `whsec_…` signing secret; without it `/api/stripe/webhook` returns 503 |

Production also sets `notearray.session.cookie-secure=true` and sends HSTS. The OpenAPI, Swagger UI, metrics and
health endpoints are disabled in `prod`. See [DEVOPS.md](DEVOPS.md) for the systemd environment file and proxy
requirements (host validation + CSRF rely on `Host`/`Origin` being forwarded correctly).

### Mobile Deployment
- The app is fully responsive and works on mobile devices
- Serve on your network to access from phones/tablets: `http://<your-ip>:3000` (dev) or `http://<your-ip>:8080` (production)
- The production build marks the session cookie `Secure` (HTTPS only); for plain-HTTP LAN access, run with `-Dnotearray.session.cookie-secure=false`
- Test on multiple devices to ensure optimal experience

## Development Workflow

### Running in Development Mode

For the best development experience, run the backend and frontend in separate terminals:

**Terminal 1: Backend (Quarkus)**
```bash
mvn quarkus:dev
```
- Runs on `http://localhost:8080`
- Auto-reloads on Java code changes
- Serves the built UI from `src/main/resources/META-INF/resources/`

**Terminal 2: Frontend (Vite)**
```bash
cd ui
npm run dev
```
- Runs on `http://localhost:3000`
- Hot-reloads React code changes instantly
- Proxies `/api` and `/q` requests to the backend

**Access the app at `http://localhost:3000`**

### Running Tests

Backend unit tests run with JUnit 5 (no database or server required):

```bash
mvn test
```

Covered so far (**146 unit tests across 19 test classes**): login-throttle backoff/lockout rules (`LoginThrottlePolicyTest`), client-IP / `X-Forwarded-For` parsing (`ClientIpTest`), password and email policies (`PasswordPolicyTest`, `EmailPolicyTest`), JAX-RS registration and SPA redirects (`NoteArrayApplicationTest`), password change and account-deletion confirmation (`UserPasswordChangeTest`, `UserDeleteVerificationTest`), CSRF filtering (`CsrfFilterTest`), security headers / request-id / ingest XSS sanitization (`SecurityHeadersFilterTest`), note version conflicts (`NoteVersionConflictTest`), tag prefix filtering (`TagPrefixFilterTest`), imports and Joplin folder/attachment mapping (`ImportLimitsTest`, `JoplinFolderTagsTest`, `JoplinAttachmentImportTest`), attachment quotas and cleanup (`AttachmentQuotaTest`, `AttachmentCleanupServiceTest`), plan/Stripe handling (`UserPlanSyncTest`, `StripeWebhookResourceTest`), and the Stripe subscription cancellation that precedes a downgrade or account deletion (`StripeBillingServiceTest`, wire-level `DELETE /v1/subscriptions/{id}` against a local HTTP server). `mvn clean package` runs the tests as part of the build.

### Testing on Mobile Devices

1. Find your machine's IP address:
   ```bash
   # macOS/Linux
   ifconfig | grep "inet"
   # Windows
   ipconfig
   ```

2. Access from mobile device on the same network:
   ```
   http://<your-ip>:3000  (development)
   http://<your-ip>:8080  (production build)
   ```

3. Use browser DevTools mobile view to test responsive breakpoints

### Development Tips

- **Vite dev mode (recommended):** Run `npm run dev` in the `ui/` folder for hot-reload React changes. The dev server proxies API calls to Quarkus on port 8080, so you don't need to rebuild after each change.
- **Quarkus hot-reload:** Java backend changes are automatically reloaded when running `mvn quarkus:dev`.
- **Database migrations:** Flyway runs automatically on startup. Add new migrations to `src/main/resources/db/migration/` with naming `V{version}__{description}.sql`.
- **OpenAPI updates:** Changes to REST endpoint signatures are reflected in the OpenAPI spec automatically on next Quarkus hot-reload.
- **Theme persistence:** The selected theme is saved to your user record in the database and restored on login.
- **Note sorting:** Notes are sorted by creation time (newest first) by default. Click any column header in the Notes table to sort by that field (desktop only; mobile cards maintain date sort).
- **Login tracking:** Each successful login via `POST /api/users/login` updates the user's `last_login` timestamp.
- **Session security:** Sessions are stored (SHA-256 hashed) in the `sessions` table, so they survive restarts and allow multiple concurrent logins; cookies are HttpOnly and SameSite=Strict (plus Secure in `prod`).
- **Login throttling:** Failed logins back off exponentially (1s doubling to 64s; 15-minute lockout after 8 consecutive failures, reset by a successful login), and each client IP gets 120 login + 30 signup requests per hour. Throttled requests return `429` with a `Retry-After` header. Tune via `notearray.rate-limit.*` properties — repeated failed logins while testing locally will hit the lockout.
- **Password policy:** New or changed passwords need 8+ characters with an uppercase letter, a lowercase letter, and a number (special characters from `!@#$%^&*` are optional). Enforced server-side on create/update and mirrored by the signup form.
- **Markdown in notes:** Content is stored as markdown, so you can export, version control, and sync notes easily.
- **Tags and search:** Tags are space-delimited and searchable with the **Search Tags** scope (partial tag matches) in the Search Notes panel.
- **Importing notes:** Use the Import feature to migrate notes from Joplin or upload individual markdown files. Imports automatically preserve metadata and convert folder structures to tags.
- **Auto-save behavior:** Both new and existing notes are saved automatically after 2 seconds of inactivity — the first save creates the note (once it has a title or an attachment), later ones update it in place. Pending changes are flushed when the editor closes. The debounce delay prevents excessive server requests while providing responsive save behavior.
- **Responsive design:** Mobile-first CSS approach with breakpoints at 768px and 1024px. Use browser DevTools to test different screen sizes. Touch targets are 44px minimum on mobile for accessibility.
- **Mobile optimization:** Modals go full-screen on mobile, search filters collapse, notes display as cards instead of tables. Font sizes scale with viewport using `clamp()`.
- **Filter state management:** Active filters are managed in NotesPanel state and persisted to `localStorage` for recovery on page reload. Filters are combined with search queries using AND logic.
- **Date filtering:** Backend API supports ISO-8601 instant range parameters (`createdFrom`, `createdTo`, `updatedFrom`, `updatedTo`) and returns 400 for invalid formats.
- **Attachment filter:** **Find notes with attachments** in the Search Notes panel keeps only notes that carry files.
- **Smart collections:** the header **Pinned** and **Untagged** buttons filter directly to pinned or untagged notes; both combine with the Search Notes panel and stay on until clicked off.
- **Pinned notes:** Use the pin icon in the notes list to pin/unpin notes as favorites.

## Technologies Used

### Backend
- **Quarkus** 3.40.1 – lightweight, fast Java framework
- **Java** 25 – language runtime
- **Hibernate Panache** – ORM simplification
- **PostgreSQL** JDBC driver – database connectivity
- **Flyway** – database schema versioning and migration
- **BCrypt** (Quarkus Elytron) – password hashing and verification
- **SmallRye OpenAPI** – automatic OpenAPI 3.0 spec generation and Swagger UI
- **Micrometer Prometheus** – application metrics export
- **Quarkus Scheduler** – periodic cleanup of expired sessions, stale throttle rows and orphaned attachments
- **Jackson** – JSON processing for import parsing and Stripe webhook events
- **Apache Commons Compress** – TAR (JEX) archive support
- **Stripe REST API** – server-side Checkout verification and subscription cancellation (plain JDK HTTP client)
- **JUnit 5** – backend unit tests (`src/test`)

### Frontend
- **React** 19 – UI library
- **React Router** 7 – client-side routing (`BrowserRouter` with backend `SpaResource` fallback)
- **Vite** 8 – fast build tool and dev server
- **Mantine** 9 – UI component library with Light/Dark/Auto color scheme and responsive hooks
- **TipTap** 3 – headless rich text editor with markdown support
- **DOMPurify** – HTML/markdown sanitization against XSS
- **Tabler Icons** – icon library

## License

Apache 2.0
