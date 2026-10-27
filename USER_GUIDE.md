# NoteArray User's Guide

A practical guide to using NoteArray, a modern note-taking application that works on desktop, tablet, and mobile devices.

**Last updated: October 2026**

---

## Contents

1. [Getting Started](#1-getting-started)
2. [Creating & Writing Notes](#2-creating--writing-notes)
3. [Images & Attachments](#3-images--attachments)
4. [Organizing: Tags & Pinning](#4-organizing-tags--pinning)
5. [Searching Your Notes](#5-searching-your-notes)
6. [Viewing, Sorting & Deleting](#6-viewing-sorting--deleting)
7. [Importing Notes](#7-importing-notes)
8. [Personal Settings & Appearance](#8-personal-settings--appearance)
9. [Plans, Storage & Billing](#9-plans-storage--billing)
10. [Privacy & Security](#10-privacy--security)
11. [Troubleshooting & Error Messages](#11-troubleshooting--error-messages)

---

## 1. Getting Started

### Creating an account

1. Open NoteArray in your browser.
2. On the login page, click **Sign up** ("Don't have an account? Sign up").
3. Fill in **Name**, **Email**, **Password**, and **Confirm Password**.
4. Click **Create Account**. You are signed in automatically.

Your password must:

- Be at least **8 characters** long
- Contain at least **one uppercase letter**, **one lowercase letter**, and **one number**
- (Optional) use special characters such as `!@#$%^&*`

You'll see a hint under the password field, and the form checks the rules before submitting. If an email or name is already in use, you'll see **"Email already in use"** or **"Name already in use"** — pick another one.

### Logging in

Enter your **Email** and **Password** and submit the form (pressing **Enter** works). If the credentials are wrong you'll see a generic error — NoteArray never reveals whether the email or the password was incorrect.

> **Too many attempts?** After several consecutive failed logins, NoteArray slows things down deliberately: the wait between attempts doubles each time (1s, 2s, 4s … up to 64s), and after **8 consecutive failures** the account is locked out for **15 minutes**. A successful login clears the count. You may also see **"too many attempts; try again in N seconds"** if the server is busy. Just wait and try again.

### Staying signed in

- You stay signed in for **7 days** on each device, even if the server restarts.
- You can be signed in on **multiple devices at once**; logging out only signs out the *current* device.
- If your session expires while you're using the app, you'll see **"Session expired. Please login again."** — just sign in again.

### Getting to know the header

Once signed in, the header at the top of every page is your control center:

| Header item | What it does |
|---|---|
| **Create Note** | Opens the note editor to write a new note |
| **List Notes** | Returns to your note list (clears the search; the Pinned / Untagged / attachments filters stay as you set them) |
| **Search Notes** | Toggles the search panel — text search with its scope checkboxes and the attachment / untagged filters (List Notes view only) |
| **Pinned** | Filters the list to your pinned notes (List Notes view only) |
| **Untagged** | Filters the list to notes with no tags (List Notes view only) |
| **Import** (upload icon) | Opens the Import notes modal (List Notes view only) |
| **Help/About** | Dropdown with **FAQ**, **Privacy Policy**, and **Security Notes** pages |
| **Your avatar** | Shows your initials; click it to open **Personal Settings** |

Notes:

- **Search Notes**, **Pinned**, **Untagged**, and **Import** are only enabled while you're on the **List Notes** view. Switch to that view first, then toggle them.
- On narrow screens, header items that don't fit collapse into a **More actions (…)** menu on the right.

### Screen layouts

- On wide screens (≥ 960 px) you're in **wide mode**: the NoteArray title shows in the header and the content is inset from the edges by a margin.
- On narrower screens — or if the header runs out of room — **compact mode** hides the title and uses the full width.
- On phones (≤ 768 px) the note list switches from a table to a **card grid**, with touch-friendly buttons and full-screen panels.

---

## 2. Creating & Writing Notes

### Opening the editor

Click **Create Note** in the header. The editor replaces the list and shows:

- a **Title** field (required, up to **255 characters**)
- a **Tags** field (see [Organizing](#4-organizing-tags--pinning))
- the rich-text editor, with the placeholder *"Write your note here, or drag and drop an image or PDF…"*
- an auto-save status line

A **Created: …** line appears under the title once the note exists, and the button changes from **Cancel** (creating) to **Close** (editing).

### Formatting toolbar

| Toolbar buttons | What they do |
|---|---|
| **B / I / U / S / Clear formatting** | Bold, italic, underline, strikethrough, remove all formatting |
| **H1, H2, H3** | Heading levels |
| **Bullet list / Ordered list** | Unordered and numbered lists |
| **Blockquote ("** ) | Quoted text |
| **Code / Code block** | Inline code or a fenced multi-line code block |
| **Link / Unlink** | Add a hyperlink to the selected text (http, https, or mailto) or remove one |

Markdown shortcuts work while typing — for example `**bold**`, `*italic*`, starting a line with `# `, `- `, `1. `, or `> `. Text alignment (left/center/right) is supported for headings and paragraphs.

### Click, link & HTML safety

- Links you paste that are not `http:`, `https:`, or `mailto:` (including anchors and relative paths in your notes) are blocked from the link fields — this is a deliberate safety feature.
- All note content is sanitized before storage, so scripts embedded in pasted HTML are stripped automatically. Nothing extra for you to do.

### Resizing the editor

Drag the small grip in the editor's bottom-right corner to make the writing area taller or shorter. The grip is
keyboard-accessible: focus it and use **↑ / ↓** (hold **Shift** for larger steps). The height is saved **per note**
and restored when you open that note on any device; it never changes the note's content, version or “Updated” date.

On a smaller screen, a height chosen on a desktop is capped at about 85% of the visible area; the note keeps its
saved height, and a drag starts from the size you can actually see (so it responds immediately). Only resize if you
want to replace the stored height for that note.

### Auto-save (no save button!)

There is **no manual save button** — NoteArray saves for you:

- **After 2 seconds of typing inactivity**, changes are saved.
- A brand-new note is **created automatically** on the first auto-save — you never need to press save first. The note only becomes real once the title (or a dropped file) gives it a non-blank name, so typing without a title yet won't create junk notes. Closing or navigating away also creates it if it has a title.
- The status line tells you exactly where you stand:
  - **"Changes are saved automatically."** — nothing pending
  - **"Unsaved changes..."** — the 2-second timer is running
  - **"Saving..."** — save in progress
  - **"✓ Saved at HH:MM:SS"** — saved; the timestamp is when the save completed
  - **"Auto-save failed…"** — your changes are *not* lost; continue editing and it retries automatically

### Closing safely

**Close/Cancel** is always safe: NoteArray waits for any save currently in flight and flushes remaining changes before the editor closes. The same flush runs when you navigate away — **Create Note**, **List Notes**, Personal Settings, Help/About, or the browser Back button — so the title, tags and content you just typed are saved first. If a save fails, the editor stays open instead of dropping your text.

A brand-new note that has a title is created when you leave (or close), not discarded. Only changed fields are sent to the server, keeping saves quick.

### Editing on two devices? Read this

Each note carries a hidden version counter. If another window or device saved the same note while you were editing:

- NoteArray **automatically merges changes that don't overlap** (e.g. you changed tags while the other session changed the content).
- If both sessions changed the **same field**, you'll see: *"Note was modified in another session. Close and reopen the note to load the latest version."* Closing and reopening the note gives you the newest saved version — your overlapping change doesn't silently get overwritten *by you*, and vice versa.

---

## 3. Images & Attachments

You can attach files directly inside a note by **drag-and-drop** or by **paste** (Ctrl/Cmd+V).

### What's supported

- **Images:** PNG, JPEG, GIF, WebP — inserted **inline** in the note content
- **PDFs:** inserted as a **file link** (shown as `📄 filename.pdf`) with a preview; they are *not* base64-buried in the note text

Each file can be at most **10 MB**. Unsupported types are rejected with: *"Only PNG, JPEG, GIF, WebP images and PDF files are supported."*

### How to attach

1. Open a note in the editor (or start a new one via **Create Note**).
2. Drag files from your desktop onto the editor, or paste an image from your clipboard.
3. Drop them anywhere in the note — a dashed outline shows the drop zone while dragging.

What happens next:

- **Images** appear inline in your note where you dropped them.
- **PDFs** appear as a `📄` file link.
- If the note has **no title yet**, the title is auto-filled from the **first dropped file's name** — so you can drop a file straight onto a fresh Create Note screen and skip typing a title.

After dropping, the note auto-saves like normal (see [Auto-save](#auto-save-no-save-button)).

### Viewing attachments

- Click a `📄` file link (or any attachment link) inside a note: a **preview modal** opens showing the document in an embedded frame.
- **Download** saves the file directly to your Downloads folder — it always saves, never opens a viewer, even in browsers whose PDF viewer intercepts navigations.
- **Open in New Tab** opens it in a full browser tab. **Close** returns to the note.

### Managing a note's attachments

The editor lists the files a note links to under **Attachments (n)**, below the Tags field. Each row shows the
filename and size with **Preview**, **Download** and **Delete**. The list follows the links in your text, so removing a
link removes its row (and the paperclip in the notes list follows too). The same file can be linked from several notes —
it then shows on each of them, and **Delete** refuses while another note still links it ("remove that link first").

Deleting from that list removes the file and its link from this note right away.

### Storage accounting

Attachments **count against your plan's storage quota** together with note text. Deleting a note reclaims the space its
files used straight away — except any file another note still links, which is kept (and stays linked there). Deleting
the whole account removes every note and attachment immediately. If you simply remove an attachment's link from a note,
the file is **cleaned up automatically** — the space comes back about a day later, which leaves room for undo or a
cut/paste mistake (see [Plans, Storage & Billing](#9-plans-storage--billing)).

---

## 4. Organizing: Tags & Pinning

### Adding tags while writing

In the editor's **Tags** field, type tags **separated by spaces** — for example:

```
joplin important work
```

- Tags are **auto-lowercased**.
- Up to **10,000 characters** of tags per note.
- While you type, tags preview as colored badges.
- Tags show in the notes list as badges under each note's title.

> **Why tags instead of folders?** Tags let a note belong to many categories at once. When you import from Joplin, your folder hierarchy is converted into tags automatically (see [Importing Notes](#7-importing-notes)).

### Pinning notes

Pin a note you use often:

1. On the **List Notes** view (card or table), find the **pin icon** next to the note.
2. Click it to pin (icon becomes filled). Click again to unpin.

The header **Pinned** button is a *filter toggle* — click it to show **only pinned notes**; click again to show everything. It combines with your search text and the Search Notes panel's options, and stays on until you click it off (or clear it with the **Clear all filters** button). Pinning does not change how the list is sorted (see [Sorting](#6-viewing-sorting--deleting) — sorting stays under your control).

### Finding untagged notes

The header **Untagged** button is a filter toggle like **Pinned**: click it to show **only notes with no tags**, click again to show everything. It combines with your search text, the Search Notes panel's options, and the **Pinned** button, and stays on until you click it off (or clear it with the **Clear all filters** button).

### Search Notes panel

Click **Search Notes** in the header (List Notes view) to open the search panel. It holds the text search and the list filters together:

- **Text search**: the search box with its **Search** button (Enter also works) and **Clear**.
- **Search Titles** / **Search Note Content** / **Search Tags**: choose where the text search looks. Tags are matched on **any part** of a tag — "sup" finds notes tagged *super-duper* or *supper*, and "grq" finds notes in folders *grq* and *grq/cobol* — so there is no separate tag picker. Notes with **no tags** are included in tag searches too: they have no tags to match, so the tag scope does not exclude them.
- **Find notes with attachments**: only notes that carry at least one attachment.

Search text and filters combine with AND logic, so you can search text and narrow the results by attachments or tags at the same time. Each active filter shows as a removable chip above the list.

The header's **Pinned** and **Untagged** buttons are click-on, click-off list filters that combine with whatever the panel is searching — see [Pinning notes](#pinning-notes) and [Finding untagged notes](#finding-untagged-notes).

### Active filters

Any active search/filters appear as **removable chips** above the list, plus an **"N active filter(s)"** badge and a **Clear all filters** button. Your filter state is remembered in your browser (localStorage), so reloading the page keeps your view. **List Notes** in the header clears the search text but leaves the filter toggles (Pinned, Untagged, attachments) exactly as you set them — use the chips, the **Clear all filters** button, or the header buttons themselves to clear them.

---

## 5. Searching Your Notes

Click **Search Notes** in the header (the same panel holds the attachment and untagged filters — see [Search Notes panel](#search-notes-panel)):

1. Type words into **"Search notes…"** and press **Enter** (or the **Search** button).
2. Choose **where** to look — at least one must be checked:
   - **Search Titles** — match words in note titles
   - **Search Note Content** — match words in note text
   - **Search Tags** — match tags by *part* of a tag: typing "sup" finds notes tagged
     like *super-duper* or *supper*, and typing "grq" finds notes in folders
     *grq* and *grq/cobol* (hierarchical tags are searched inside their paths too)
   - All three can be checked at once for a comprehensive search.
3. **Clear** resets the search.

If you try to search with no box checked, a **"How to search your notes"** help modal opens explaining the boxes. (The server also requires at least one — this is a safety net, not a bug.)

### How search behaves

- **Titles & content are word-based** — they match whole words, not arbitrary substrings.
- **Tags are partial-match** — the query can be any fragment inside a tag token: "sup" matches *super-duper*, "cob" matches *grq/cobol*.
- **Case-insensitive** — "meeting" matches "Meeting" and "MEETING".
- **Ignores common words** such as "the", which are too frequent to be useful.
- **Word endings are normalized** — searching "running" also matches "run" (titles and content).
- **Every word must appear** in the field you selected.

### Combining search with filters

You can pair a search with the Search Titles / Search Note Content / Search Tags scope boxes, the **Find notes with attachments** filter, the header **Pinned** and **Untagged** toggles, and the Pinned filter all at once — they combine with AND logic for powerful, narrow results. Result counts are shown above the list: *"Search results for '…'"* / *"Found N result(s)."*

---

## 6. Viewing, Sorting & Deleting

### The notes list

The default view is **List Notes**. A **card / list toggle** at the top right switches the display on any device (on
phones the card view suits best; the list view is a compact table). On desktop the list is a table:

- **Created / Updated / Title** columns
- Tags shown as badges under the title
- A **paperclip icon** next to the title — and in card view — marks notes that carry one or more attachments (hover for the count)
- Untitled notes show as italic *"Untitled"*
- Row actions: **Pin** (a pin/star icon button), **Edit**, and **Delete**

On mobile, the default is **cards** with the created/updated dates, a 2-line-clamped title, tags, and the same Pin/Edit/Delete actions (you can switch to the table with the toggle).

### Sorting

- **Default order:** Created, **newest first**.
- Click any column header (desktop) to sort by it; click again to reverse. Clicking a date column defaults to newest-first, and the Title column to A→Z — an arrow shows the current direction.
- Sorting is instant and client-side; no reload.

### Deleting

Clicking **Delete** asks for confirmation with the note's name: *"Delete note "TITLE"?"* — continue to delete it **permanently**. There is no undo and no trash folder, so be careful:

- Deletion is immediate; the note's text and any attachments vanish and their storage is freed at once.
- Deleting a note does **not** affect other notes.

---

## 7. Importing Notes

Click **Import** in the header (List Notes view) to open the **Import notes** modal. Two formats are supported:

### Joplin (.jex) — bring your whole notebook

1. Export your notes from **Joplin** as a JEX archive (Joplin's native export format: a single `.jex` file).
2. In NoteArray's Import modal, upload the `.jex` file (up to **50 MB**).
3. Wait for the result alert.

What you get:

- **Folder hierarchy → hierarchical tags**: each Joplin folder level becomes a `/`-joined segment —
  a note in `Work ▸ Projects ▸ Acme` is tagged `work/projects/acme` (folder names are lowercased,
  spaces become `_`). To find a whole folder, search its name with the **Search Tags** scope: `work`
  matches that folder and every sub-folder in it, because tags are matched on any part of the token
  (`grq` finds `grq/cobol` too). Sub-notebooks therefore still land in the right place.
- **Metadata tags** (`tags:` lines in Joplin's footer) are picked up too.
- **Title** comes from the first markdown heading, else the first content line.
- **Content** stays markdown, with Joplin's trailing metadata block (id, parent_id, etc.) stripped.
- **Attachments** are imported as well: the archive's `resources/` directory holds every attachment,
  and each `[name.ext](:/<resourceId>)` link in a note points at one of them. The importer creates
  the attachment, **names it after the link's display text** (e.g. `gpu-optimizer-camera-ready.pdf`),
  and rewrites the link to point at the imported copy — images render inline, everything else
  downloads when clicked. Images whose link text is empty are named `<resourceId>.<extension>`.
  A missing or oversized (over **10 MB**) resource keeps its dead link and is reported in the
  result list.
- Imports with no tags at all get a `joplin-import` tag automatically, so imported notes are easy to find.

> **Re-importing does not de-duplicate.** The importer treats every `.md` entry as a new note —
> importing the same JEX a second time creates a second copy of every note (and edits you made
> in NoteArray or Joplin don't change that). Import each notebook once; if you bring the same
> archive in twice, use the `joplin-import` tag to find and delete the unwanted copies.

### Markdown (.md) — single files

Upload one `.md` file (up to **10 MB**). The **first line becomes the title**, the rest becomes the note; it's tagged **`markdown-import`** so you can find it later and re-tag it.

### Reading the result

The modal shows *"Imported N note(s) [and N attachment(s)] [with N error(s)]."* — with a bulleted list of any per-note problems. If some notes failed, the rest still imported; fix the source and import only those notes individually (re-running the whole JEX would duplicate everything that already imported). Imports respect your plan's storage limit: if the import would overshoot your plan, **nothing is saved** and the error names your plan's limit (see the next section).

---

## 8. Personal Settings & Appearance

Click **your avatar** in the header to open **Personal Settings**.

### Your profile

A card shows your **Name, Email, plan badge, theme, last login, created/updated dates**, and your account ID (usefully when contacting support or calling APIs directly).

### Appearance: Light / Dark / Auto + colors

- **Light / Dark / Auto** buttons — Auto follows your operating system's preference.
- 14 **color palettes** (blue by default) — click any palette to apply instantly.

Your appearance choice is saved to your account and follows you across devices and sessions. If the account save fails (e.g. a dropped connection), you'll get a *"Theme not saved"* warning — the color still applies locally, and simply re-clicking will retry the save.

### Changing your password

Open the **Security** card and click **Change Password**:

1. Enter your **Current Password** — this is verified first, to protect you if someone else has your browser open.
2. Enter a **New Password** and **Verify New Password** (same policy as signup: 8+ chars, upper + lower + a number).
3. Submit.

**Important:** On success, NoteArray **signs you out everywhere** (all sessions on all devices are invalidated) and returns you to the login page. Sign in again with your new password. This is deliberate — it protects you if your old password was exposed.

### Logging out

A **Logout** button sits in the upper area of Personal Settings. It clears your session on the current device. (Other devices remain signed in — a password change, however, signs out *all* sessions.)

---

## 9. Plans, Storage & Billing

NoteArray has three tiers:

| Plan | Storage | Price |
|---|---|---|
| **Free** | 100 MB | Free |
| **Pro** | 1 GB | $2/month |
| **Premium** | 5 GB | $4/month |

Storage counts **note text + note attachments** combined.

> **Paid plans may be disabled.** The operator can hide the paid tiers (e.g. while Stripe billing is not yet
> configured). In that case the **Available Plans** card is not shown, and only the Free plan is available —
> existing paid subscriptions are unaffected.

### Watching your usage

The **Storage Used** card in Personal Settings shows:

- A badge like **"1.45 MB / 100 MB (Free)"** above a progress bar
- Your plan limit, the number of notes, and the percent used
- The bar has **two sections** — your note text and your attachments — with a legend underneath
  (e.g. *Notes: 1.30 MB · Attachments: 6.49 MB*) so you can see which one is filling your plan
- The bar turns **red at 90% or more** — a good signal to clean up or upgrade

### Upgrading or downgrading

1. In Personal Settings, find the **Available Plans** cards.
2. Click **Upgrade to Pro** (green), **Upgrade to Premium**, or **Downgrade** — each card shows a badge: **Current Plan**, **Pending Stripe**, **Upgrade**, or **Downgrade**.

What happens next:

- **Upgrading to Pro or Premium** sends you to a **Stripe checkout page** to enter payment. NoteArray marks your plan as *"Pending Stripe Checkout."* After payment, Stripe sends you back to the app; the plan is **verified with Stripe and activated automatically** — there is no confirmation button to click. If you cancel at checkout, click **Cancel Checkout** to stay on your old plan.
- **Downgrading to Free** cancels your paid Stripe subscription first and applies the Free plan once Stripe confirms the cancellation. If Stripe cannot confirm, your plan is left unchanged — retry later; no recurring charge is left running in the background.
- If a payment falls behind (*past due*), a red alert appears asking you to update your payment method.

Changing plans keeps all your notes; only your limit changes.

### When you hit your storage limit

If your notes reach your plan's limit:

- A yellow **"Storage Limit Reached"** banner appears above the list showing your exact usage, with an **Upgrade Plan** button.
- **Create Note** and **Import** are **disabled** in the header.
- You can still: **delete notes or attachments** (freeing space immediately), **shrink existing notes** (making a note smaller is always allowed), and **upgrade your plan**.
- The server enforces this limit too, so it's never just a UI suggestion.

Going over quota is not an emergency: your existing notes are safe, you just can't add more until you free space or upgrade.

### Deleting your account

The red **Delete Account** card at the bottom of Personal Settings:

- Type your **current password** — deletion is confirmed only if the password matches, so a signed-in session alone can never delete your account.
- **Free users:** confirm, and your account and **all notes are permanently deleted**. You're signed out immediately.
- **Pro/Premium users:** NoteArray cancels your **Stripe subscription first** and *waits for Stripe to confirm* the cancellation ("Awaiting Stripe subscription cancellation confirmation and deleting account…"), then deletes the account and signs you out. If Stripe can't confirm, the deletion refuses with an error so you're never left with an orphaned recurring charge — try again later.
- If you have a **pending upgrade checkout**, you must complete or cancel it before deleting.
- Deletion is permanent and cascades to all notes and attachments. There is no undo.

---

## 10. Privacy & Security

NoteArray is designed to collect as little as possible and protect what it keeps. (Short version here; full pages under **Help/About** in the app.)

### What we store (and don't)

- **Stored:** your **name**, **email**, a **hashed password** (never readable by anyone, including operators), your **theme color**, your **subscription state**, and **your notes, tags and attachments**.
- **Not stored:** no analytics, no telemetry, no advertising trackers, no third-party scripts.
- **One cookie only:** `NOTEARRAY_SESSION`, which keeps you signed in. It can't be read by scripts (HttpOnly) and is never shared across sites (SameSite=Strict). Sessions are stored in the database only as a **SHA-256 hash** — a leaked database copy can't be replayed to hijack accounts.

### How your notes are protected

- **Sanitization** — anything that looks like a script (`<script>`, `<iframe>`, `on*=` handlers, `javascript:` links) is stripped from note content before it's stored or displayed, and the link types are restricted to `http`, `https`, and `mailto`.
- **Login throttling** — brute-force attempts are slowed (exponential backoff up to 64 s) and accounts lock out for 15 minutes after 8 consecutive failures. Per-IP hourly budgets (120 logins, 30 signups) also apply. Throttling never reveals whether an email is registered.
- **Self-hosting option** — since you can run NoteArray yourself, your data never has to leave infrastructure you control.

### Your account deletions are real deletions

Deleting a note or your account removes the underlying data immediately — there is no soft-delete or recovery window, and no "stale" copy on the server afterwards.

---

## 11. Troubleshooting & Error Messages

Where messages come from and what to do:

| What you see | What it means | What to do |
|---|---|---|
| *"Session expired. Please login again."* | Your 7-day session ran out. | Sign in again. |
| *"invalid email or password"* | Wrong credentials. | Check for typos / Caps Lock. Wait out any throttle timer. |
| *"too many attempts; try again in N seconds"* | Throttling after repeated failures or heavy traffic. | Wait the stated time (or 15 min after 8 consecutive failures). |
| *"Note was modified in another session. Close and reopen the note to load the latest version."* | The same note was saved from another window/device while you edited. | Close and reopen the note to load the latest saved version. |
| *"Storage quota exceeded. Please upgrade your plan or delete notes to free up space."* | You hit your plan's storage limit (returned as **413**). | Delete notes or attachments to recover space, or upgrade your plan. |
| *"Only PNG, JPEG, GIF, WebP images and PDF files are supported"* | An attachment type outside the whitelist was dropped. | Convert the file, or link text describing it and attach a PDF where possible. |
| *"Attachment exceeds the maximum size of 10 MB"* | A single file was too big. | Compress / split it. |
| *"JEX file exceeds the maximum import size of 50 MB"* / *"Markdown file exceeds the maximum size of 10 MB"* | Import file too large. | Split the Joplin export into multiple notebooks and import them separately. |
| *"Imported N note(s) with N error(s)"* | Some notes failed during import (each listed in a bulleted message list). The rest imported normally. | Fix the failing entries in the source export and import just those notes as individual `.md` files — re-running the whole JEX duplicates the notes that already imported. |
| *"Email already in use"* / *"Name already in use"* | That email or name is already an account. | Pick a different one, or log in if it's yours. |
| *"password must be at least 8 characters"* (and similar) | Password policy failure. | Follow the rules shown on the form. |
| *"Current password is incorrect"* | The Current Password field didn't match. | Recheck the old password before changing it. |
| *"Passwords do not match"* | The New Password and Verify fields differ. | Re-type both carefully. |
| *"Auto-save failed…"* | A transient network/server error during auto-save. | Keep editing — it will retry. If it persists, copy your text somewhere safe and reload. |
| *"Failed to load notes: …"* | Note list couldn't be fetched. | Usually transient. Reload the page; sign in again if it also says *"Session expired"*. |
| *"Plan change failed"* / *"Stripe sync failed"* | The Stripe step couldn't complete. | Retry from Personal Settings; check your Stripe checkout session. |
| *"Payment not verified"* | The Stripe return URL had no checkout session id, so the plan could not be confirmed. | Click the plan again so the session can be verified, or contact support if you were charged. |
| *"Could not confirm subscription cancellation with Stripe. Your plan was not changed to Free."* | A downgrade needs Stripe to confirm the subscription is cancelled, and that call failed. | Retry the downgrade later; if it keeps failing, contact the operator. |
| *"Could not confirm subscription cancellation with Stripe. Your account was not deleted to prevent recurring charges."* | Account deletion on a paid plan stops when Stripe cannot confirm cancellation. | Retry later; the account and its notes are still intact. |
| *"Password is required to delete your account"* / *"Current password is incorrect"* | Account deletion needs your current password. | Re-enter it carefully. |
| *"Please cancel or complete your pending Stripe checkout before deleting your account."* | A plan upgrade is mid-flight. | Finish the Stripe checkout, or click **Cancel Checkout**, then delete the account. |
| *"A note import is already running; try again in a minute"* | Two imports are already running (the server allows two at a time). | Wait a minute and retry. |

### Where to find more help

- The in-app **Help/About** menu: **FAQ**, **Privacy Policy**, and **Security Notes**.
- Operators and developers: see the project's `README.md` for setup, configuration, and the full REST API.
