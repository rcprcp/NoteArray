import { Box, Card, Divider, Stack, Text, Title } from '@mantine/core'
import MarkdownIt from 'markdown-it'
import dompurify from 'dompurify'
import userGuideSource from '../../USER_GUIDE.md?raw'
import './help.css'

/**
 * Render the shipped USER_GUIDE.md inside the app. Raw HTML is disabled in
 * markdown-it and the result is sanitized again with DOMPurify; heading ids
 * are generated so the guide's own "Contents" anchors keep working.
 */
function slugifyHeading(text) {
  return String(text || '')
    .toLowerCase()
    .trim()
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .replace(/\s/g, '-')
}

function guideBody(markdown) {
  return markdown
    .replace(/^# .*(?:\r?\n)+/, '') // page title is rendered by the card
    .replace(/^\*\*Last updated:[^\n]*(?:\r?\n)+/, '')
    .replace(/^---(?:\r?\n)+/, '')
}

const markdown = new MarkdownIt({ html: false, linkify: true })

markdown.core.ruler.push('notearray_heading_ids', (state) => {
  const seen = new Map()
  for (let i = 0; i < state.tokens.length; i += 1) {
    const token = state.tokens[i]
    if (token.type !== 'heading_open') continue
    const inline = state.tokens[i + 1]
    const base = slugifyHeading(inline?.content)
    const count = seen.get(base) || 0
    seen.set(base, count + 1)
    token.attrSet('id', count === 0 ? base : `${base}-${count}`)
  }
})

markdown.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const href = tokens[idx].attrGet('href') || ''
  if (/^https?:/i.test(href)) {
    tokens[idx].attrSet('target', '_blank')
    tokens[idx].attrSet('rel', 'noopener noreferrer')
  }
  return self.renderToken(tokens, idx, options)
}

const USER_GUIDE_HTML = dompurify.sanitize(
  markdown.render(guideBody(userGuideSource)),
  { ADD_ATTR: ['target'] },
)

const PAGES = {
  faq: {
    title: 'Frequently Asked Questions (FAQ)',
    updated: 'November 2026',
    sections: [
      {
        heading: 'What happens if my session expires while I am editing?',
        body: 'NoteArray keeps the editor open and saves your current draft on this device. A "Session expired" dialog asks you to sign in again — including your two-factor code or a recovery code — and then automatically saves the note. If you close or reload the browser before signing in, reopen NoteArray and choose Restore on the "Unsaved draft on this device" banner. Drafts are kept for 7 days and are discarded when you sign out explicitly.',
      },
      {
        heading: 'I am signed in on two devices. Does signing in again on one sign me out of the other?',
        body: 'No. Each device has its own session, so re-authenticating on one device creates a new session for that device only; the other device stays signed in on its existing session until its own 7-day session expires or is revoked. Changing your password signs out every device, and enabling or disabling two-factor authentication signs out every other device. If you are using two tabs in the same browser, they share the session cookie, so signing in again in one tab lets the other continue without a separate prompt.',
      },
      {
        heading: 'How do TODO lists work?',
        body: 'TODO Lists in the header is a standalone task manager, separate from notes. Create a list, add items, and check them off. Items can be nested, dragged to reorder, given a priority (None/Low/Medium/High) and an optional due date (overdue items turn red). Use All/Open/Done to filter, and Link note to attach a note.',
      },
      {
        heading: 'Can I convert a note checklist into a TODO list?',
        body: 'Yes. Open the note and click Create TODO list next to Delete Note. NoteArray converts every - [ ] / - [x] line (including indented sub-items) into a standalone TODO list linked to that note; the note itself is unchanged. If the note has no checklist items, you will see an error instead.',
      },
      {
        heading: 'How are my notes saved?',
        body: 'NoteArray automatically saves your changes after two seconds of typing inactivity. When you close a note or navigate away (Create Note, List Notes, Settings, Help, browser Back), pending edits are flushed first so your work is not lost. A brand-new note with a title is created on the way out too.',
      },
      {
        heading: 'Can I make the note editor bigger or smaller?',
        body: 'Yes. Drag the small grip in the bottom-right corner of the editor, or focus it and use the up/down arrow keys (hold Shift for larger steps). The height is saved per note and restored when you reopen it on any device. On a phone, a height chosen on a desktop is capped at about 85% of the visible screen — the note keeps its saved height, and a drag starts from the size you can actually see. Only the height is adjustable; the editor always fits the available width.',
      },
      {
        heading: 'How do I enable two-factor authentication?',
        body: 'Open Personal Settings → Security and click Enable on the Two-factor authentication card. Confirm your password, scan the QR code with Google Authenticator (or copy the manual key), enter the 6-digit code, and save the recovery codes shown once. On the next login a correct password asks for the code or a recovery code. Password changes and account deletion also require it.',
      },
      {
        heading: 'I lost my phone or my recovery codes — can I still sign in?',
        body: 'Use one of the recovery codes you saved when enabling two-factor authentication. If you have neither the authenticator nor the codes, ask the server operator to reset two-factor authentication after verifying your identity; there is no self-service bypass.',
      },
      {
        heading: 'How do tags and search work?',
        body: 'Tags are space-delimited words attached to each note (for example: "work project-alpha urgent"). Use the Search Notes panel to search note titles, content and tags (tick Search Tags to match tags by part of a word, or "Find notes with attachments" to keep only notes with files). The header Pinned and Untagged buttons are click-on, click-off filters that combine with your search.',
      },
      {
        heading: 'Can I import or export my notes?',
        body: 'Yes. Click Import/Export in the top navigation bar while on the List Notes view. The Import / Export view opens in the main pane. The top half imports notes: a Joplin .jex archive (ZIP or TAR up to 50 MB) or individual .md Markdown files (up to 10 MB), with Joplin folder names and metadata tags preserved as NoteArray tags. The bottom half exports all of your notes as one JSON file per note plus every attachment, packed into a single ZIP archive.',
      },
      {
        heading: 'How do I customize my theme or change my password?',
        body: 'Click your user avatar in the upper-right corner of the header to open Personal Settings. From there you can switch between Light, Dark, and Auto appearance modes, pick from 14 color palettes, or update your password.',
      },
    ],
  },
  guide: {
    title: "NoteArray User's Guide",
    updated: 'October 2026',
    html: USER_GUIDE_HTML,
  },
  privacy: {
    title: 'Privacy Policy',
    updated: 'October 2026',
    sections: [
      {
        heading: 'Data We Store',
        body: 'NoteArray stores only the account information you provide at signup (display name, normalized email address, password hash, and theme preference) along with the notes and tags you create or import. We do not collect analytics, telemetry, or advertising identifiers.',
      },
      {
        heading: 'Session & Cookie Usage',
        body: 'Authentication uses a single strictly necessary session cookie (NOTEARRAY_SESSION) marked HttpOnly and SameSite=Strict. No third-party tracking cookies or external scripts are loaded by the application.',
      },
      {
        heading: 'Data Ownership & Deletion',
        body: 'Your notes belong solely to your account. Deleting a note permanently removes it from the database, and deleting your user account cascades deletion across all of your notes and active sessions.',
      },
    ],
  },
  security: {
    title: 'Security Notes',
    updated: 'October 2026',
    sections: [
      {
        heading: 'Password Hashing & Policy',
        body: 'Passwords are hashed using BCrypt and are never stored or logged in plaintext. All accounts enforce a minimum of 8 characters including at least one uppercase letter, one lowercase letter, and one digit. Changing your password requires verifying your current password first and immediately invalidates all existing sessions.',
      },
      {
        heading: 'Login Throttling & Brute-Force Protection',
        body: 'Failed login attempts trigger per-account exponential backoff (1s doubling up to 64s, followed by a 15-minute lockout after 8 consecutive failures) as well as per-IP hourly request budgets on both login and signup endpoints.',
      },
      {
        heading: 'XSS, CSRF & Transport Hardening',
        body: 'Note content is sanitized on ingest and on render with DOMPurify, and editor links are restricted to http, https, and mailto protocols. All responses emit strict Content-Security-Policy (CSP), X-Frame-Options, X-Content-Type-Options, Referrer-Policy, and Permissions-Policy headers, and state-changing requests enforce SameSite=Strict cookies alongside Origin and Sec-Fetch-Site verification.',
      },
    ],
  },
}

export default function HelpPage({ pageKey, wideMode = true }) {
  const page = PAGES[pageKey] || PAGES.faq

  return (
    <Box style={{ width: '100%' }}>
      <Card
        withBorder={wideMode}
        shadow={wideMode ? 'sm' : undefined}
        p={wideMode ? 'lg' : 'xs'}
        radius="md"
      >
        <Stack gap="md">
          <div>
            <Title order={2} size="h3">{page.title}</Title>
            <Text size="xs" c="dimmed">Last updated: {page.updated}</Text>
          </div>

          <Divider />

          {page.html ? (
            <Box
              className="help-markdown"
              dangerouslySetInnerHTML={{ __html: page.html }}
            />
          ) : (
            <Stack gap="lg">
              {page.sections.map((section, idx) => (
                <div key={idx}>
                  <Title order={3} size="h5" mb={4}>
                    {section.heading}
                  </Title>
                  <Text size="sm" style={{ lineHeight: 1.6 }}>
                    {section.body}
                  </Text>
                </div>
              ))}
            </Stack>
          )}
        </Stack>
      </Card>
    </Box>
  )
}
