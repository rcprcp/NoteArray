# NoteArray

A modern note-taking application built with **Quarkus** (Java 25), **Panache ORM**, **PostgreSQL**, and a **React** UI with **Vite** and **Mantine**. Works great on desktop, tablet, and mobile devices.

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
│   │   │   │   ├── User.java                  # User entity with unique name/email, theme_color, last_login, superuser flag
│   │   │   │   ├── Note.java                  # Note entity with title, markdown content, and space-delimited tags
│   │   │   │   ├── Session.java               # Session entity backing persistent logins (token hash, user, expiry)
│   │   │   │   └── LoginThrottle.java         # Throttle state (per-email backoff, per-IP hourly windows)
│   │   │   ├── resource/
│   │   │   │   ├── UserResource.java          # REST endpoints for users (CRUD + login/logout, throttled, password policy)
│   │   │   │   └── NoteResource.java          # REST endpoints for notes (CRUD + search + filter + import)
│   │   │   └── service/
│   │   │       ├── SessionService.java        # Persisted session create/resolve/invalidate + scheduled purge
│   │   │       ├── AuthenticationService.java # BCrypt credential check for login (timing-safe for unknown emails)
│   │   │       ├── LoginThrottleService.java  # Rate-limit decisions: backoff/lockout + per-IP hourly budgets
│   │   │       ├── LoginThrottlePolicy.java   # Pure backoff rules (1s doubling to 64s, lockout after 8 failures)
│   │   │       ├── ClientIp.java              # Client address for rate limiting (X-Forwarded-For opt-in)
│   │   │       └── PasswordPolicy.java        # Password rules (8+ chars, uppercase, lowercase, digit)
│   │   └── resources/
│   │       ├── application.properties         # Quarkus config (DB, Flyway, OpenAPI, sessions, rate limits)
│   │       └── db/migration/
│   │           ├── V1.0.0__create_users_and_notes.sql
│   │           ├── V1.0.1__add_theme_color_to_users.sql
│   │           ├── V1.0.2__add_title_to_notes.sql
│   │           ├── V1.0.3__add_last_login_to_users.sql
│   │           ├── V1.0.4__add_superuser_to_users.sql
│   │           ├── V1.0.5__insert_initial_user.sql
│   │           ├── V1.0.6__add_fulltext_search_to_notes.sql
│   │           ├── V1.0.7__add_tags_to_notes.sql
│   │           ├── V1.0.8__add_pinned_to_notes.sql
│   │           ├── V1.0.9__add_session_to_users.sql
│   │           ├── V1.1.0__create_sessions_table.sql
│   │           ├── V1.1.1__add_unique_constraint_to_users_name.sql
│   │           ├── V1.1.2__remove_seeded_test_user.sql
│   │           ├── V1.1.3__add_notes_created_at_index.sql
│   │           └── V1.1.4__create_login_throttle.sql
│   └── test/java/com/notearray/service/
│       ├── LoginThrottlePolicyTest.java      # Backoff/lockout rule tests
│       ├── ClientIpTest.java                 # X-Forwarded-For parsing tests
│       └── PasswordPolicyTest.java           # Password rule tests
├── ui/                                        # React + Vite frontend (mobile-responsive)
│   ├── src/
│   │   ├── App.jsx                            # Main app shell with routing and logout
│   │   ├── main.jsx                           # React entry point with session check
│   │   ├── api.js                             # Fetch wrapper for REST API (includes credentials)
│   │   ├── LoginPage.jsx                      # Login and signup page
│   │   ├── NotesPanel.jsx                     # Note management UI with rich text editor, tags, search, import, auto-save, and responsive design
│   │   ├── FilterPanel.jsx                    # Tag filters, smart collections, saved searches
│   │   ├── ThemeButton.jsx                    # Theme color picker
│   │   ├── UserMenu.jsx                       # Top-right personal settings menu (profile, theme, logout)
│   │   └── index.css                          # Mobile-first responsive styles
│   ├── vite.config.js                         # Vite configuration
│   ├── index.html                             # HTML template with viewport meta
│   └── package.json                           # Node dependencies
└── README.md
```

## Prerequisites

- **Java 25** (JDK)
- **Maven 3.8+**
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
- `email` (VARCHAR 320, unique, required)
- `password_hash` (VARCHAR 255, BCrypt hashed, never returned in API responses)
- `theme_color` (VARCHAR 32, Mantine color name, default: `'blue'`)
- `last_login` (TIMESTAMP, nullable, updated on successful login)
- `superuser` (BOOLEAN, default: FALSE, read-only for application, set via psql only)
- `created_at` (TIMESTAMP, set on insert)
- `updated_at` (TIMESTAMP, auto-updated on modification)

### `notes` Table
- `id` (UUID, primary key)
- `owner_id` (UUID, foreign key → users.id, ON DELETE CASCADE)
- `title` (VARCHAR 255, required)
- `content` (TEXT, stores markdown-formatted content, max ~10MB)
- `tags` (VARCHAR 10000, space-delimited tags, default empty)
- `pinned` (BOOLEAN, default: FALSE, for favorite/pinned notes)
- `title_tsv` (tsvector, auto-generated full-text search index on title)
- `content_tsv` (tsvector, auto-generated full-text search index on content, truncated to 500KB)
- `created_at` (TIMESTAMP, set on insert)
- `updated_at` (TIMESTAMP, auto-updated on modification)

Indexed on `owner_id` for efficient owner-based queries. GIN indexes on `title_tsv` and `content_tsv` for fast full-text search.

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
that user's sessions; when you change your own password you stay signed in on the current device
with a freshly rotated token.

### Session Cookies

- **HttpOnly:** Cannot be accessed by JavaScript; protects against XSS attacks.
- **SameSite=Strict:** Prevents CSRF attacks.
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
  - Returns 400 if any required field is missing or the password fails the policy (see Password Policy)
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
  - **Request body:** `{ "name": "...", "email": "...", "password": "...", "themeColor": "..." }`
  - **Response:** Updated user object
  - Returns 401 if not authenticated
  - Returns 404 if user doesn't exist or caller lacks permission
  - Returns 409 if new name or email is already in use
  - Returns 400 if a provided password fails the policy (see Password Policy)
  - Superusers can update any user; regular users can only update themselves
  - Changing the password invalidates **all** of that user's sessions; when you change your own password you stay signed in on the current device with a freshly rotated token

- `DELETE /api/users/{id}` – Delete a user
  - **Response:** 204 No Content
  - Returns 401 if not authenticated
  - Returns 404 if user doesn't exist or caller lacks permission
  - Cascades to all notes owned by that user
  - Superusers can delete any user; regular users can only delete themselves

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
  - Returns 400 if title is missing or blank
  - Content is stored as markdown
  - Tags are space-delimited and optional
  - Note is always owned by the authenticated user (ownerId is not accepted in the request)

- `PUT /api/notes/{id}` – Update a note
  - **Request body:** `{ "title": "...", "content": "...", "tags": "...", "pinned": true|false }` or any combination
  - **Response:** Updated note object
  - Returns 401 if not authenticated
  - Returns 404 if note doesn't exist or caller lacks permission
  - Returns 400 if title is blank
  - Superusers can update any note; regular users can only update their own
  - Content is stored as markdown
  - Tags are space-delimited
  - `pinned` field allows marking notes as favorites

- `DELETE /api/notes/{id}` – Delete a note
  - **Response:** 204 No Content
  - Returns 401 if not authenticated
  - Returns 404 if note doesn't exist or caller lacks permission
  - Superusers can delete any note; regular users can only delete their own

### Notes – Filters

- **GET** `/api/notes` also accepts filter parameters, combined with AND (and with any search query):
  - `tags` – repeatable query parameter; a note must have every listed tag to match. Example: `?tags=work&tags=important`
  - `untagged` – `true` to return only notes without any tags
  - `pinned` – `true` to return only pinned notes
  - `createdFrom` / `createdTo` – ISO-8601 instants for note creation date range (inclusive)
  - `updatedFrom` / `updatedTo` – ISO-8601 instants for note modification date range (inclusive)
  - Invalid date values return 400
  - All filters are combined with AND logic
  - Examples:
    - `?tags=work` – notes tagged with "work"
    - `?tags=work&tags=important` – notes tagged with both "work" AND "important"
    - `?untagged=true` – notes with no tags
    - `?pinned=true` – pinned notes only
    - `?createdFrom=2024-01-01T00:00:00Z&createdTo=2024-12-31T23:59:59Z` – notes created in 2024
    - `?tags=work&pinned=true&updatedFrom=2024-10-01T00:00:00Z` – pinned work notes updated since Oct 1

### Notes – Search

- `GET /api/notes?q=...&searchTitles=true&searchContent=true` – Full-text search
  - **Query parameters:**
    - `q` – Search query (required when searching)
    - `searchTitles` – Search in note titles (boolean, default false)
    - `searchContent` – Search in note content (boolean, default false)
  - **Response:** Array of matching note objects, sorted by `createdAt` descending
  - Returns 401 if not authenticated
  - Returns 400 if q is provided but neither searchTitles nor searchContent is true
  - Search is word-based, case-insensitive, and ignores common stop words (like "the")
  - Word endings are normalized (e.g., "running" matches "run")
  - Superusers search all notes; regular users search only their own
  - Can be combined with filter parameters: `?q=bug&searchTitles=true&tags=urgent`

### Notes – Import

- `POST /api/notes/import/joplin` – Import from Joplin JEX archive
  - **Content-Type:** `application/octet-stream`
  - **Request body:** Binary .jex file (ZIP or TAR archive), max 50 MB
  - **Response:** `{ "imported": N, "errors": N, "messages": [...] }`
  - Returns 401 if not authenticated
  - Returns 400 if file is invalid
  - Returns 413 if the archive exceeds 50 MB (individual entries are also capped at 10 MB)
  - Detects archive format: ZIP (folder/note JSON metadata) or TAR (raw markdown export)
  - Flattens Joplin's folder hierarchy into space-delimited tags
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

When the backend is running (`mvn quarkus:dev`), you can access the API documentation:

- **OpenAPI Spec (YAML):** http://localhost:8080/q/openapi
- **OpenAPI Spec (JSON):** http://localhost:8080/q/openapi?format=json
- **Swagger UI (Interactive):** http://localhost:8080/q/swagger-ui
- **Metrics (Prometheus):** http://localhost:8080/q/metrics

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
  - Invalid credentials show an error message
  - Session cookie is set on success
  - Repeated failures are throttled with exponential backoff (1s doubling to 64s; 15-minute lockout after 8 consecutive failures, cleared by a successful login) — a throttled attempt shows a "try again in N seconds" message
- **Signup** – Create a new account from the login page
  - Name, email, and password fields
  - Password must be confirmed and meet the password policy (8+ characters, uppercase, lowercase, number; special characters optional) — shown as a hint under the field and checked client-side
  - Auto-login after successful signup
- **Logout button** – Clears the session cookie and returns to login page

### Note Management
- **List notes** – Responsive view that adapts to screen size
  - **Desktop:** Table with Created, Updated, and Title columns (all sortable)
  - **Mobile:** Card view with note title, dates, and tags
  - Default sort: Created, descending (newest first)
  - Click column headers (desktop) to sort; click again to reverse direction
  - Tags displayed as badges below each note's title
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
- **Filter panel** – Toggled with the "Tags" header button
  - **Tag filter:**
    - Add tags by typing (space-delimited) or multi-select from existing tags
    - Notes must have all selected tags
  - **Smart collections:**
    - **Pinned** – Favorite/pinned notes
    - **Untagged** – Notes with no tags
    - Note counts shown on each collection button
  - **Saved searches:**
    - Save current search query and filters under a custom name
    - One click applies a saved search
    - Delete saved searches
    - Filters and saved searches stored in browser localStorage
    - Auto-restore last used filters on page reload
- **Active filter badges** showing which filters are applied
  - Clear individual filters with X button
  - "Clear all filters" button to reset all filters
  - Active filter count indicator

### Full-Text Search
- **Search bar** – Type your search query
- **Search checkboxes** – Choose where to search
  - **Mobile:** Collapsible "Search Options" button to toggle checkboxes
  - **Desktop:** Always-visible checkboxes
  - **Search Titles** – Match words in note titles
  - **Search Note Content** – Match words in note content
  - Both can be checked together for comprehensive search
- **Search results** – Shows matching notes with result count
- **Smart search** – Word-based, case-insensitive, ignores common words and word endings
- **Help modal** – Click search with no checkboxes to see instructions
- **Combined with filters** – Use search and filters together for powerful discovery

### Import Notes
- **Import button** – Upload notes from external sources
- **Joplin JEX import** – Import entire note collections from Joplin
  - Upload .jex files (Joplin's native export format)
  - Preserves folder structure as space-delimited tags
  - Extracts title from markdown headings
  - Shows import progress and results
- **Markdown import** – Import individual markdown files
  - Upload .md files
  - Extracts title from first markdown heading or line
  - Automatically tagged with `markdown-import`
  - Preserves all markdown formatting

### Theme Selection
- **"Theme" button** – Choose from 14 Mantine color palettes
  - Saved to the user's account and restored on login

### Superuser Features (if applicable)
- If you are a superuser, `GET /api/notes` returns all notes from all users
- You can view, edit, delete, and pin any user's notes
- You can search and filter across all users' notes
- You can view and manage all user accounts via API (no UI for user management in this release)

## Building for Production

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

Database credentials are read from environment variables. **Set these in production** — the built-in defaults point at the local dev database (`localhost:5432/bob`):

| Variable | Default | Description |
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

Covered so far: login-throttle backoff/lockout rules, client-IP / `X-Forwarded-For` parsing, and the password policy. The UI has no automated tests yet. `mvn clean package` runs the tests as part of the build.

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
- **Tags and search:** Tags are space-delimited and searchable via the full-text search feature and tag filters.
- **Importing notes:** Use the Import feature to migrate notes from Joplin or upload individual markdown files. Imports automatically preserve metadata and convert folder structures to tags.
- **Auto-save behavior:** Changes to existing notes are automatically saved after 2 seconds of inactivity. New notes require manual save first. The debounce delay prevents excessive server requests while providing responsive save behavior. No changes are lost when closing the modal—pending changes are flushed before closing.
- **Responsive design:** Mobile-first CSS approach with breakpoints at 768px and 1024px. Use browser DevTools to test different screen sizes. Touch targets are 44px minimum on mobile for accessibility.
- **Mobile optimization:** Modals go full-screen on mobile, search filters collapse, notes display as cards instead of tables. Font sizes scale with viewport using `clamp()`.
- **Filter state management:** Active filters and saved searches are managed in NotesPanel state. Filter state persists to localStorage for quick recovery on page reload. Filters are combined with search queries using AND logic.
- **Date filtering:** Date range filters support custom date pickers with ISO-8601 instant format (`YYYY-MM-DDTHH:MM:SSZ`). Backend validates date ranges and returns 400 for invalid formats.
- **Tag filtering:** Multi-select tag filter automatically populates from tags in the user's notes. A note matches if it has ALL selected tags. Combine multiple tags for powerful filtering.
- **Smart collections:** Pre-built collections (Untagged, Pinned, Modified today, Oldest unmodified) update dynamically as notes are created/edited. Display note counts to help users navigate quickly.
- **Pinned notes:** Use the star icon in notes list or edit modal to pin/unpin notes as favorites. Pinned notes are easily filterable and appear first in custom collection.

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
- **Quarkus Scheduler** – periodic cleanup of expired sessions and stale throttle rows
- **Jackson** – JSON processing for import parsing
- **JUnit 5** – backend unit tests (`src/test`)

### Frontend
- **React** 19 – UI library
- **React Router** 7 – client-side routing (HashRouter for SPA)
- **Vite** 8 – fast build tool and dev server
- **Mantine** 9 – UI component library with hooks for responsive design
- **TipTap** 3 – headless rich text editor with markdown support
- **Tabler Icons** – icon library

## License

Apache 2.0
