import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { ActionIcon, Alert, Box, Button, Checkbox, Group, List, Modal, Stack, Table, Text, TextInput, Title, UnstyledButton, Badge, FileInput, Loader, Card, SimpleGrid, Tooltip } from '@mantine/core'
import { IconCheck, IconChevronDown, IconChevronUp, IconDownload, IconLayoutGrid, IconList, IconPaperclip, IconPin, IconSearch, IconSelector, IconUpload, IconMenu2, IconX } from '@tabler/icons-react'
import { useMediaQuery } from '@mantine/hooks'
import { RichTextEditor } from '@mantine/tiptap'
import { Node, mergeAttributes } from '@tiptap/core'
import { useEditor } from '@tiptap/react'
import dompurify from 'dompurify'
import StarterKit from '@tiptap/starter-kit'
import Highlight from '@tiptap/extension-highlight'
import TextAlign from '@tiptap/extension-text-align'
import Placeholder from '@tiptap/extension-placeholder'
import { Markdown } from 'tiptap-markdown'
import { Link } from '@mantine/tiptap'
import { notesApi } from './api'
import { DEFAULT_FILTERS, buildApiFilters, describeFilters, sanitizeFilters } from './filters'
import './editor.css'

const EMPTY_FORM = { title: '', content: '', tags: '' }
const DEFAULT_SEARCH = { q: '', searchTitles: true, searchContent: true, searchTags: true }

const FILTERS_KEY = 'notearray.filters'
// Card/list display preference: 'auto' follows screen size, or an explicit override.
const VIEW_KEY = 'notearray.view'
const DEFAULT_VIEW_MODE = 'auto'

function readStorage(key, fallback) {
  try {
    const raw = window.localStorage.getItem(key)
    return raw ? JSON.parse(raw) : fallback
  } catch (e) {
    return fallback
  }
}

function writeStorage(key, value) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  } catch (e) {
    // storage unavailable (private mode / quota); preferences just won't persist
  }
}

// Milliseconds of typing inactivity before an existing note is auto-saved.
const AUTOSAVE_DELAY_MS = 2000

// Per-note editor height (CSS px), matching NoteResource/DB bounds.
const DEFAULT_EDITOR_HEIGHT = 400
const MIN_EDITOR_HEIGHT = 160
const MAX_EDITOR_HEIGHT = 1200
// Coalesce resize commits (pointer-up / arrow keys) before the API write.
const EDITOR_HEIGHT_SAVE_DELAY_MS = 400

function clampEditorHeight(height) {
  const value = Number(height)
  if (!Number.isFinite(value)) return DEFAULT_EDITOR_HEIGHT
  return Math.max(MIN_EDITOR_HEIGHT, Math.min(MAX_EDITOR_HEIGHT, Math.round(value)))
}

const ALLOWED_LINK_PROTOCOLS = new Set(['http:', 'https:', 'mailto:'])

function isAllowedLinkUri(url) {
  if (!url || typeof url !== 'string') return false
  const trimmed = url.trim().replace(/[\u0000-\u001F\u007F\s]+/g, '')
  if (!trimmed) return false
  // Allow same-page anchors and relative paths.
  if (trimmed.startsWith('#') || trimmed.startsWith('/') || trimmed.startsWith('?')) {
    return !trimmed.startsWith('//')
  }
  const schemeMatch = trimmed.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)/)
  if (schemeMatch) {
    return ALLOWED_LINK_PROTOCOLS.has(schemeMatch[1].toLowerCase())
  }
  // Bare hostnames/paths without an explicit scheme will get defaultProtocol ('https').
  return true
}

const ImageNode = Node.create({
  name: 'image',
  inline: false,
  group: 'block',
  draggable: true,
  addAttributes() {
    return {
      src: { default: null },
      alt: { default: null },
      title: { default: null },
    }
  },
  parseHTML() {
    return [{ tag: 'img[src]' }]
  },
  renderHTML({ HTMLAttributes }) {
    return [
      'img',
      mergeAttributes(
        { style: 'max-width: 100%; height: auto; border-radius: 6px; margin: 8px 0; display: block;' },
        HTMLAttributes,
      ),
    ]
  },
})

function isSupportedAttachmentFile(file) {
  if (!file) return false
  const type = (file.type || '').toLowerCase()
  if (
    type === 'image/png' ||
    type === 'image/jpeg' ||
    type === 'image/gif' ||
    type === 'image/webp' ||
    type === 'application/pdf'
  ) {
    return true
  }
  const name = (file.name || '').toLowerCase()
  return /\.(png|jpe?g|gif|webp|pdf)$/.test(name)
}

function deriveTitleFromFilename(filename) {
  if (!filename) return 'Untitled Attachment'
  const base = filename.replace(/\.[^/.]+$/, '').trim()
  return (base || filename).slice(0, 255)
}

function formatBytes(bytes) {
  const n = Number(bytes) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

/**
 * Remove every link/image pointing at an attachment from the markdown content —
 * both markdown forms (`![alt](url)`, `[label](url?download=1)`) and the HTML
 * anchors/images the drop handler inserts. Empty paragraphs left behind go too.
 */
export function stripAttachmentLinks(content, attachmentId) {
  if (!content || !attachmentId) return content
  const markdown = new RegExp(`!?\\[[^\\]]*\\]\\([^)]*/api/attachments/${attachmentId}[^)]*\\)`, 'g')
  const html = new RegExp(`<(?:img|a)\\b[^>]*/api/attachments/${attachmentId}[^>]*>(?:[^<]*</a>)?`, 'gi')
  return content
    .replace(markdown, '')
    .replace(html, '')
    .replace(/<p>\s*<\/p>/gi, '')
    .trim()
}

function MarkdownEditor({ value, onChange, onDropFiles, onPreviewAttachment, height = DEFAULT_EDITOR_HEIGHT, onHeightCommit }) {
  const [dragOver, setDragOver] = useState(false)
  const dropHandlerRef = useRef(onDropFiles)
  dropHandlerRef.current = onDropFiles
  const [liveHeight, setLiveHeight] = useState(() => clampEditorHeight(height))
  const liveHeightRef = useRef(liveHeight)
  const resizeStateRef = useRef(null)
  const [resizing, setResizing] = useState(false)

  // Upper bound for this device: never taller than 85% of the visible viewport.
  const visibleMaxHeight = () => {
    const viewport = (window.visualViewport && window.visualViewport.height) || window.innerHeight
    return Math.max(MIN_EDITOR_HEIGHT, Math.min(MAX_EDITOR_HEIGHT, Math.round(viewport * 0.85)))
  }

  useEffect(() => {
    if (resizeStateRef.current) return
    const next = clampEditorHeight(height)
    liveHeightRef.current = next
    setLiveHeight(next)
  }, [height])

  const beginResize = (event) => {
    event.preventDefault()
    // Start from the *visible* height, not the stored one: a note sized on a
    // desktop may be taller than this screen (CSS clamps it), and dragging
    // must respond immediately instead of consuming the clamped difference.
    const shell = event.currentTarget.parentElement
    const visible = shell ? Math.round(shell.getBoundingClientRect().height) : liveHeightRef.current
    resizeStateRef.current = {
      startY: event.clientY,
      startHeight: visible,
      maxHeight: visibleMaxHeight(),
    }
    setResizing(true)
    event.currentTarget.focus?.()
    try {
      event.currentTarget.setPointerCapture?.(event.pointerId)
    } catch (e) {
      // capture is best-effort
    }
  }

  const moveResize = (event) => {
    const state = resizeStateRef.current
    if (!state) return
    const next = Math.min(state.maxHeight, clampEditorHeight(state.startHeight + (event.clientY - state.startY)))
    liveHeightRef.current = next
    setLiveHeight(next)
  }

  const endResize = (event) => {
    const state = resizeStateRef.current
    if (!state) return
    resizeStateRef.current = null
    setResizing(false)
    try {
      event.currentTarget.releasePointerCapture?.(event.pointerId)
    } catch (e) {
      // capture is best-effort
    }
    // A click/no-op drag on a note stored taller than this screen must not
    // silently shrink the stored height.
    if (liveHeightRef.current !== state.startHeight) {
      onHeightCommit?.(liveHeightRef.current)
    }
  }

  const onResizeKeyDown = (event) => {
    const step = event.shiftKey ? 50 : 20
    const maxHeight = visibleMaxHeight()
    const base = Math.min(liveHeightRef.current, maxHeight)
    let next
    if (event.key === 'ArrowUp') next = base - step
    else if (event.key === 'ArrowDown') next = base + step
    else if (event.key === 'Home') next = MIN_EDITOR_HEIGHT
    else if (event.key === 'End') next = maxHeight
    else return
    event.preventDefault()
    next = Math.min(maxHeight, clampEditorHeight(next))
    liveHeightRef.current = next
    setLiveHeight(next)
    if (next !== base) onHeightCommit?.(next)
  }

  const editor = useEditor({
    // Tiptap 3 no longer re-renders the editor on every transaction by default;
    // Mantine's toolbar controls rely on it to reflect the active marks.
    shouldRerenderOnTransaction: true,
    extensions: [
      // StarterKit 3 bundles link/underline extensions. Keep underline, but use
      // Mantine's Link so RichTextEditor.Link/Unlink controls can manage it.
      (StarterKit.default || StarterKit).configure({ link: false }),
      ImageNode,
      Link.configure({
        openOnClick: false,
        autolink: true,
        defaultProtocol: 'https',
        isAllowedUri: (url) => isAllowedLinkUri(url),
        validate: (url) => isAllowedLinkUri(url),
      }),
      Highlight,
      TextAlign.configure({ types: ['heading', 'paragraph'] }),
      Placeholder.configure({
        placeholder: 'Write your note here, or drag and drop an image or PDF...',
      }),
      Markdown,
    ],
    content: dompurify.sanitize(value || '') || '',
    onUpdate: ({ editor }) => {
      const markdown = editor.storage.markdown?.getMarkdown ? editor.storage.markdown.getMarkdown() : editor.getText()
      onChange(markdown)
    },
    editorProps: {
      attributes: {
        spellcheck: 'true',
      },
      handleClick: (view, pos, event) => {
        const target = event?.target
        const anchor = target?.closest ? target.closest('a[href]') : null
        const href = anchor?.getAttribute('href')
        if (href && href.startsWith('/api/attachments/')) {
          event.preventDefault()
          onPreviewAttachment?.(href, anchor.textContent || 'Attachment')
          return true
        }
        return false
      },
      handleDrop: (view, event, _slice, moved) => {
        if (moved) return false
        const files = Array.from(event?.dataTransfer?.files || []).filter(isSupportedAttachmentFile)
        if (files.length === 0) return false
        event.preventDefault()
        setDragOver(false)
        dropHandlerRef.current?.(files, editor)
        return true
      },
      handlePaste: (_view, event) => {
        const files = Array.from(event?.clipboardData?.files || []).filter(isSupportedAttachmentFile)
        if (files.length === 0) return false
        event.preventDefault()
        dropHandlerRef.current?.(files, editor)
        return true
      },
    },
  })

  useEffect(() => {
    if (!editor) return
    const nextMarkdown = dompurify.sanitize(value || '') || ''
    const currentMarkdown = editor.storage.markdown?.getMarkdown ? editor.storage.markdown.getMarkdown() : editor.getText()
    if (currentMarkdown !== nextMarkdown) {
      editor.commands.setContent(nextMarkdown, { emitUpdate: false })
    }
  }, [editor, value])

  return (
    <div
      onDragOver={(e) => {
        if (e.dataTransfer?.types?.includes('Files')) {
          e.preventDefault()
          setDragOver(true)
        }
      }}
      onDragLeave={() => setDragOver(false)}
      onDrop={(e) => {
        setDragOver(false)
        const files = Array.from(e.dataTransfer?.files || []).filter(isSupportedAttachmentFile)
        if (files.length > 0 && !e.defaultPrevented) {
          e.preventDefault()
          dropHandlerRef.current?.(files, editor)
        }
      }}
      style={{ width: '100%' }}
    >
      <RichTextEditor editor={editor} style={{ width: "100%" }}>
        <RichTextEditor.Toolbar sticky stickyOffset={60}>
          <RichTextEditor.ControlsGroup>
            <RichTextEditor.Bold />
            <RichTextEditor.Italic />
            <RichTextEditor.Underline />
            <RichTextEditor.Strikethrough />
            <RichTextEditor.ClearFormatting />
          </RichTextEditor.ControlsGroup>

          <RichTextEditor.ControlsGroup>
            <RichTextEditor.H1 />
            <RichTextEditor.H2 />
            <RichTextEditor.H3 />
          </RichTextEditor.ControlsGroup>

          <RichTextEditor.ControlsGroup>
            <RichTextEditor.BulletList />
            <RichTextEditor.OrderedList />
            <RichTextEditor.Blockquote />
            <RichTextEditor.Code />
            <RichTextEditor.CodeBlock />
          </RichTextEditor.ControlsGroup>

          <RichTextEditor.ControlsGroup>
            <RichTextEditor.Link />
            <RichTextEditor.Unlink />
          </RichTextEditor.ControlsGroup>
        </RichTextEditor.Toolbar>

        <div className="notearray-editor-shell" style={{ height: liveHeight }}>
          <RichTextEditor.Content
            style={{
              height: '100%',
              minHeight: 0,
              overflowY: 'auto',
              border: dragOver
                ? '2px dashed var(--mantine-primary-color-filled)'
                : '1px solid var(--mantine-color-gray-3)',
              width: '100%',
            }}
          />
          <div
            className={resizing ? 'notearray-editor-grip is-resizing' : 'notearray-editor-grip'}
            role="separator"
            aria-orientation="horizontal"
            aria-label="Resize note editor"
            aria-valuemin={MIN_EDITOR_HEIGHT}
            aria-valuemax={MAX_EDITOR_HEIGHT}
            aria-valuenow={liveHeight}
            tabIndex={0}
            title="Drag to resize (or use ↑ / ↓)"
            onPointerDown={beginResize}
            onPointerMove={moveResize}
            onPointerUp={endResize}
            onPointerCancel={endResize}
            onKeyDown={onResizeKeyDown}
          />
        </div>
      </RichTextEditor>
    </div>
  )
}

// Clickable, sortable column header.
function SortableTh({ label, field, sort, onSort }) {
  const active = sort.field === field
  const Icon = !active ? IconSelector : sort.dir === 'asc' ? IconChevronUp : IconChevronDown
  return (
    <Table.Th aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <UnstyledButton onClick={() => onSort(field)}>
        <Group gap={4} wrap="nowrap">
          <Text fw={600} size="sm">{label}</Text>
          <Icon size={14} />
        </Group>
      </UnstyledButton>
    </Table.Th>
  )
}

// Small status line shown in the edit modal while auto-save is active.
function AutoSaveStatus({ status, lastSaved, errorMessage }) {
  if (status === 'saving') {
    return (
      <Group gap={6}>
        <Loader size="xs" />
        <Text size="xs" c="dimmed">Saving...</Text>
      </Group>
    )
  }
  if (status === 'error') {
    return (
      <Text size="xs" c="red">
        Auto-save failed{errorMessage ? `: ${errorMessage}` : '.'} Your changes are not saved yet; they will be retried when you continue editing.
      </Text>
    )
  }
  if (status === 'pending') {
    return <Text size="xs" c="dimmed">Unsaved changes...</Text>
  }
  if (status === 'saved' && lastSaved) {
    return (
      <Group gap={4}>
        <IconCheck size={14} color="green" />
        <Text size="xs" c="dimmed">Saved at {lastSaved.toLocaleTimeString()}</Text>
      </Group>
    )
  }
  return <Text size="xs" c="dimmed">Changes are saved automatically.</Text>
}

function PinButton({ note, onToggle }) {
  const Icon = IconPin
  return (
    <ActionIcon
      variant={note.pinned ? 'filled' : 'subtle'}
      size="lg"
      onClick={(e) => { e.stopPropagation(); onToggle(note) }}
      aria-label={note.pinned ? 'Unpin note' : 'Pin note'}
      aria-pressed={!!note.pinned}
    >
      <Icon size={16} />
    </ActionIcon>
  )
}

// Card view for a single note (used on mobile and when the user picks card view)

/** Paperclip indicator for notes that carry one or more attachments. */
export function AttachmentIcon({ count }) {
  if (!count || count < 1) return null
  const label = `${count} attachment${count === 1 ? '' : 's'}`
  return (
    <Tooltip label={label} withArrow>
      <IconPaperclip size={14} stroke={1.8} style={{ flexShrink: 0 }} aria-label={label} />
    </Tooltip>
  )
}

function NoteCard({ note, onEdit, onDelete, onTogglePin }) {
  return (
    <Card shadow="sm" padding="md" radius="md" withBorder onClick={() => onEdit(note)} style={{ cursor: 'pointer' }}>
      <Stack gap="xs">
        <Group justify="space-between" align="flex-start" wrap="nowrap" gap="xs">
          <Group gap="xs" wrap="wrap" style={{ minWidth: 0 }}>
            <Text fw={500} size="sm" c="dimmed" style={{ minWidth: 0 }}>Created: {new Date(note.createdAt).toLocaleDateString()}</Text>
            <Text fw={500} size="sm" c="dimmed" style={{ minWidth: 0 }}>Updated: {new Date(note.updatedAt).toLocaleDateString()}</Text>
          </Group>
          <Stack gap={4} align="flex-end">
            <Group gap="xs" wrap="nowrap">
              <PinButton note={note} onToggle={onTogglePin} />
              <Button size="xs" variant="light" w={64} onClick={(e) => { e.stopPropagation(); onEdit(note) }}>Edit</Button>
            </Group>
            <Group gap="xs" wrap="nowrap">
              <Box w={42} style={{ display: 'flex', justifyContent: 'center' }}>
                <AttachmentIcon count={note.attachmentCount} />
              </Box>
              <Button size="xs" variant="light" color="red" w={64} onClick={(e) => { e.stopPropagation(); onDelete(note) }}>Delete</Button>
            </Group>
          </Stack>
        </Group>
        <div>
          <Text fw={600} size="md" style={{ overflowWrap: 'anywhere' }}>
            {note.title || <Text c="dimmed" fs="italic">Untitled</Text>}
          </Text>
          {note.tags && (
            <Group gap="xs" mt="xs">
              {note.tags.split(' ').filter(t => t.length > 0).map((tag, idx) => (
                <Badge key={idx} size="sm" variant="light">{tag}</Badge>
              ))}
            </Group>
          )}
        </div>
      </Stack>
    </Card>
  )
}

const TEXT_FIELDS = ['title']

// Notes of the authenticated user. No owner selection needed.
export default function NotesPanel({ registerActions } = {}) {
  const navigate = useNavigate()
  const location = useLocation()
  const openCreateRef = useRef(null)
  const isMobile = useMediaQuery('(max-width: 768px)')
  const controlSize = isMobile ? 'xs' : 'sm'
  const inputSize = isMobile ? 'sm' : 'md'
  const modalSize = isMobile ? 'sm' : 'lg'
  const [notes, setNotes] = useState([])
  const [sort, setSort] = useState({ field: 'createdAt', dir: 'desc' })
  const [error, setError] = useState(null)
  const [formError, setFormError] = useState(null)
  const [editingNote, setEditingNote] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)

  // Auto-save state (only used when editing an existing note)
  const [autoSaveStatus, setAutoSaveStatus] = useState('idle') // idle | pending | saving | saved | error
  const [autoSaveError, setAutoSaveError] = useState(null)
  const [lastSaved, setLastSaved] = useState(null)
  // Per-note editor height (persisted through the dedicated endpoint).
  const [editorHeight, setEditorHeight] = useState(DEFAULT_EDITOR_HEIGHT)
  const editorHeightRef = useRef(DEFAULT_EDITOR_HEIGHT)
  editorHeightRef.current = editorHeight
  const heightSaveTimerRef = useRef(null)
  const autoSaveTimerRef = useRef(null)
  // Last values known to be persisted on the server for the note being edited.
  const baselineRef = useRef(null)
  // Promise of an in-flight auto-save request, so manual save/close can wait for it.
  const inFlightRef = useRef(null)
  // Promise of an in-flight "create note" request (debounced first save).
  const createInFlightRef = useRef(null)
  // Note created during this editor session, even if React state has not
  // re-rendered yet (used by the flush path when the user navigates quickly).
  const createdNoteRef = useRef(null)
  // Latest save error message, so the flush path can surface it too.
  const lastSaveErrorRef = useRef(null)
  // Stable handle for App's navigation guards (the function is recreated per render).
  const flushPendingChangesRef = useRef(async () => true)
  // True while a "create note" request is in flight; see the debounce effect below.
  const creatingRef = useRef(false)
  // True if at least one auto-save succeeded in the current edit session (list needs refresh).
  const autoSavedRef = useRef(false)

  // Search form state (what the user is typing/selecting)
  const [search, setSearch] = useState(DEFAULT_SEARCH)
  // Search that is currently applied to the list (null = no active search)
  const [activeSearch, setActiveSearch] = useState(null)
  const [helpOpened, setHelpOpened] = useState(false)
  const [createMode, setCreateMode] = useState(false)
  const [editMode, setEditMode] = useState(false)
  const [searchExpanded, setSearchExpanded] = useState(false)

  // Advanced filters (persisted). Tag filtering lives in the text search's
  // "Search Tags" scope, so no tag list has to be fetched.
  const [filters, setFilters] = useState(() => sanitizeFilters(readStorage(FILTERS_KEY, {})))
  // Card vs list display preference ('auto' follows screen size). Persisted.
  const [viewMode, setViewMode] = useState(() => readStorage(VIEW_KEY, DEFAULT_VIEW_MODE))
  useEffect(() => writeStorage(VIEW_KEY, viewMode), [viewMode])
  const cardView = viewMode === 'card' || (viewMode === 'auto' && isMobile)

  // Import states
  const [importModalOpened, setImportModalOpened] = useState(false)
  const [searchVisible, setSearchVisible] = useState(false)
  const [tagsVisible, setTagsVisible] = useState(false)
  // Search Text and Find by Tag are one panel: the "Search Notes" header button
  // opens/closes text search, its scope checkboxes and the tag filter together.
  const searchPanelOpen = searchVisible || tagsVisible
  const toggleSearchPanel = () => {
    const next = !searchPanelOpen
    setSearchVisible(next)
    setTagsVisible(next)
  }
  const [importLoading, setImportLoading] = useState(false)
  const [importResult, setImportResult] = useState(null)
  const [previewAttachment, setPreviewAttachment] = useState(null)
  // Attachments the note being edited is linked to (metadata only).
  const [noteAttachments, setNoteAttachments] = useState([])
  const [storageInfo, setStorageInfo] = useState({
    usedBytes: 0,
    limitBytes: 100 * 1024 * 1024,
    noteCount: 0,
    overQuota: false,
  })

  const load = useCallback(async (searchParams, filterState) => {
    try {
      const apiFilters = buildApiFilters(filterState)
      const [filtered, storage] = await Promise.all([
        notesApi.list(searchParams, apiFilters),
        notesApi.storage().catch(() => null),
      ])
      setNotes(filtered)
      if (storage) {
        const used = Number(storage.usedBytes) || 0
        const limit = Number(storage.limitBytes) || 100 * 1024 * 1024
        setStorageInfo({
          usedBytes: used,
          limitBytes: limit,
          noteCount: Number(storage.noteCount) || 0,
          overQuota: storage.overQuota != null ? !!storage.overQuota : used >= limit,
        })
      }
      setError(null)
    } catch (e) {
      if (e.status === 401) {
        setError('Session expired. Please login again.')
      } else {
        setError(`Failed to load notes: ${e.message}`)
      }
    }
  }, [])

  useEffect(() => {
    load(activeSearch, filters)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load, filters])

  const reload = () => load(activeSearch, filters)

  /**
   * "List Notes": back to the plain list — closes the editor/panels and clears
   * the search text. The Pinned / Untagged / attachments filters are *not*
   * touched: they are modifiers the user switches on and off themselves.
   */
  const resetAll = async () => {
    const saved = await flushPendingChangesRef.current()
    if (!saved) return // stay in the editor rather than dropping unsaved fields
    clearAutoSaveTimer()
    createdNoteRef.current = null
    autoSavedRef.current = false
    setSearchVisible(false)
    setTagsVisible(false)
    setImportModalOpened(false)
    setCreateMode(false)
    setEditMode(false)
    setActiveSearch(null)
    setSearch(DEFAULT_SEARCH)
    setError(null)
    setFormError(null)
    // filters are unchanged, so the filters effect will not reload for us.
    await load(null, filters)
  }

  const openCreate = async () => {
    if (storageInfo.overQuota) {
      setError(
        'You have reached your plan storage limit. Please delete unneeded notes to free up space or upgrade your plan in Personal Settings.',
      )
      return
    }
    const saved = await flushPendingChangesRef.current()
    if (!saved) return // keep the current draft/editor intact
    const needsReload = autoSavedRef.current
    clearAutoSaveTimer()
    baselineRef.current = null
    autoSavedRef.current = false
    createdNoteRef.current = null
    setAutoSaveStatus('idle')
    setLastSaved(null)
    setEditingNote(null)
    setForm(EMPTY_FORM)
    setFormError(null)
    setNoteAttachments([])
    setEditorHeight(DEFAULT_EDITOR_HEIGHT)
    setEditMode(false)
    setCreateMode(true)
    if (needsReload) await reload()
  }
  openCreateRef.current = openCreate

  // "Create Note" pressed while another page (Settings/Help) was showing:
  // App navigates to /notes with this state flag, and the newly mounted panel
  // opens a blank editor once.
  useEffect(() => {
    if (!location.state?.createNote) return
    navigate(location.pathname, { replace: true, state: null })
    openCreateRef.current?.()
  }, [location.state, location.pathname, navigate])

  useEffect(() => writeStorage(FILTERS_KEY, filters), [filters])
  // Re-register whenever the values the header reads change. registerActions is a
  // stable callback in App, so without these deps the handlers and the open/closed
  // button state would stay frozen at their first-render values forever.
  useEffect(() => {
    if (registerActions) {
      registerActions({
        onCreate: openCreate,
        onRefresh: reload,
        // Called by App before any route change / settings / help navigation.
        flush: () => flushPendingChangesRef.current(),
        onImport: () => {
          if (storageInfo.overQuota) {
            setError(
              'You have reached your plan storage limit. Please delete unneeded notes to free up space or upgrade your plan in Personal Settings.',
            )
            return
          }
          setImportModalOpened(true)
        },
        onToggleSearch: toggleSearchPanel,
        // Pinned / Untagged are click-on, click-off list filters. They combine
        // with whatever the Search Notes panel is searching, and stay on until
        // clicked off (or until List Notes resets everything).
        onSetPinned: () => {
          setFilters((f) => ({ ...f, collection: f.collection === 'pinned' ? null : 'pinned' }))
        },
        onSetUntagged: () => {
          setFilters((f) => ({ ...f, untagged: !f.untagged }))
        },
        onReset: resetAll,
        inEditor: createMode || editMode,
        createOpen: createMode,
        searchOpen: searchPanelOpen,
        pinnedActive: filters.collection === 'pinned',
        untaggedActive: !!filters.untagged,
        importOpen: importModalOpened,
        overQuota: storageInfo.overQuota,
      })
    }
  }, [registerActions, createMode, editMode, searchPanelOpen, importModalOpened, activeSearch, filters, storageInfo.overQuota])

  const updateFilters = (patch) => {
    setFilters((f) => ({ ...f, ...patch }))
  }

  const activeFilterItems = useMemo(() => describeFilters(filters), [filters])
  const activeFilterCount = activeFilterItems.length + (activeSearch ? 1 : 0)

  // The attachment list follows the links in the editor, so removing a link
  // hides its row immediately (the server-side list is reference-based too).
  const visibleAttachments = useMemo(
    () => noteAttachments.filter((a) => form.content.includes(`/api/attachments/${a.id}`)),
    [noteAttachments, form.content],
  )

  const togglePin = async (note) => {
    try {
      await notesApi.update(note.id, { pinned: !note.pinned })
      await reload()
    } catch (e) {
      setError(`Failed to update note: ${e.message}`)
    }
  }

  const runSearch = async () => {
    if (!search.q.trim()) {
      // Empty search text: clear the search and show all notes.
      setActiveSearch(null)
      await load(null, filters)
      return
    }
    if (!search.searchTitles && !search.searchContent && !search.searchTags) {
      setHelpOpened(true)
      return
    }
    setActiveSearch(search)
    await load(search, filters)
  }

  const clearAll = async () => {
    setSearch(DEFAULT_SEARCH)
    setActiveSearch(null)
    setFilters(DEFAULT_FILTERS)
    await load(null, DEFAULT_FILTERS)
  }

  const clearSearch = async () => {
    setSearch(DEFAULT_SEARCH)
    setActiveSearch(null)
    await load(null, filters)
  }

  const toggleSort = (field) =>
    setSort((s) =>
      s.field === field
        ? { field, dir: s.dir === 'asc' ? 'desc' : 'asc' }
        : { field, dir: TEXT_FIELDS.includes(field) ? 'asc' : 'desc' },
    )

  const sortedNotes = useMemo(() => {
    const factor = sort.dir === 'asc' ? 1 : -1
    const value = (n) =>
      TEXT_FIELDS.includes(sort.field)
        ? (n[sort.field] || '').toLowerCase()
        : new Date(n[sort.field]).getTime()
    return [...notes].sort((a, b) => {
      const x = value(a)
      const y = value(b)
      return x < y ? -factor : x > y ? factor : 0
    })
  }, [notes, sort])

  const clearAutoSaveTimer = () => {
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current)
      autoSaveTimerRef.current = null
    }
  }

  /** Update the editor height locally and persist it once the user stops dragging. */
  const handleEditorHeightCommit = (height) => {
    const next = clampEditorHeight(height)
    setEditorHeight(next)
    const noteId = editingNote?.id
    if (!noteId) return // a brand-new note is persisted after its create succeeds
    setEditingNote((prev) => (prev ? { ...prev, editorHeight: next } : prev))
    setNotes((prev) => prev.map((n) => (n.id === noteId ? { ...n, editorHeight: next } : n)))
    if (heightSaveTimerRef.current) clearTimeout(heightSaveTimerRef.current)
    heightSaveTimerRef.current = setTimeout(() => {
      heightSaveTimerRef.current = null
      notesApi.setEditorHeight(noteId, next).catch(() => {
        // Cosmetic preference: keep the drag even if the write fails.
      })
    }, EDITOR_HEIGHT_SAVE_DELAY_MS)
  }

  // Returns only the fields in `values` that differ from what is persisted on the server.
  const diffFromBaseline = (values) => {
    const base = baselineRef.current || { title: '', content: '', tags: '' }
    const body = {}
    const title = values.title.trim()
    const tags = values.tags.trim().toLowerCase()
    const baseTags = (base.tags || '').trim().toLowerCase()
    if (title !== base.title) body.title = title
    if (values.content !== base.content) body.content = values.content
    if (tags !== baseTags) body.tags = tags
    return body
  }



  // Selecting a note shows its title, tags and content for editing.
  const openEdit = (note) => {
    clearAutoSaveTimer()
    setEditorHeight(clampEditorHeight(note.editorHeight ?? DEFAULT_EDITOR_HEIGHT))
    baselineRef.current = {
      title: note.title || '',
      content: note.content || '',
      tags: (note.tags || '').toLowerCase(),
      version: note.version ?? 0,
    }
    autoSavedRef.current = false
    setAutoSaveStatus('idle')
    setLastSaved(null)
    setEditingNote(note)
    setForm({ title: note.title || '', content: note.content || '', tags: (note.tags || '').toLowerCase() })
    setFormError(null)
    setCreateMode(false)
    setEditMode(true)
    refreshNoteAttachments(note.id)
  }

  /** Reload the attachment list shown in the note view. */
  const refreshNoteAttachments = async (noteId) => {
    if (!noteId) {
      setNoteAttachments([])
      return
    }
    try {
      const list = await notesApi.listAttachments(noteId)
      setNoteAttachments(Array.isArray(list) ? list : [])
    } catch (e) {
      setNoteAttachments([])
    }
  }

  /** Delete an attachment (and its now-dangling link) from the note view. */
  const removeAttachment = async (attachment) => {
    if (!editingNote || !attachment) return
    if (!window.confirm(`Delete attachment "${attachment.filename}"? Its link is removed from this note.`)) return
    setFormError(null)
    try {
      await notesApi.deleteAttachment(attachment.id, editingNote.id)
    } catch (e) {
      setFormError(e.status === 409
        ? e.message
        : `Failed to delete attachment: ${e.message}`)
      return
    }
    const nextContent = stripAttachmentLinks(form.content, attachment.id)
    setForm((prev) => ({ ...prev, content: nextContent }))
    await performAutoSave(editingNote.id, {
      ...form,
      title: form.title.trim() || editingNote.title,
      content: nextContent,
    })
    await refreshNoteAttachments(editingNote.id)
    await reload()
  }

  // Persist pending changes of the note being edited (serialized one request at a time).
  const performAutoSave = useCallback(async (noteId, values) => {
    while (inFlightRef.current) {
      try {
        await inFlightRef.current
      } catch (e) {
        // previous error already handled
      }
    }

    const body = diffFromBaseline(values)
    if (Object.keys(body).length === 0) {
      setAutoSaveStatus('saved')
      return true
    }
    const expectedVersion = baselineRef.current?.version
    const payload = expectedVersion != null ? { ...body, version: expectedVersion } : body

    setAutoSaveStatus('saving')
    const runUpdate = async () => {
      try {
        return await notesApi.update(noteId, payload)
      } catch (err) {
        if (err.status !== 409) throw err
        // Optimistic lock conflict: fetch latest server state and merge if non-overlapping.
        const latest = await notesApi.get(noteId)
        const base = baselineRef.current || { title: '', content: '', tags: '' }
        const serverTags = (latest.tags || '').trim().toLowerCase()
        const baseTags = (base.tags || '').trim().toLowerCase()
        const overlapping =
          ('title' in body && (latest.title || '') !== base.title) ||
          ('content' in body && (latest.content || '') !== base.content) ||
          ('tags' in body && serverTags !== baseTags)
        if (overlapping) {
          const conflictErr = new Error(
            'Note was modified in another session. Close and reopen the note to load the latest version.',
          )
          conflictErr.status = 409
          throw conflictErr
        }
        baselineRef.current = {
          title: latest.title || '',
          content: latest.content || '',
          tags: serverTags,
          version: latest.version ?? 0,
        }
        return await notesApi.update(noteId, { ...body, version: latest.version })
      }
    }

    const request = runUpdate()
    inFlightRef.current = request
    try {
      const updated = await request
      // Record what the server now has (including the incremented version) so later diffs are relative to it.
      baselineRef.current = {
        ...(baselineRef.current || {}),
        ...body,
        version: updated?.version ?? baselineRef.current?.version,
      }
      autoSavedRef.current = true
      setLastSaved(new Date())
      setAutoSaveError(null)
      setAutoSaveStatus('saved')
      lastSaveErrorRef.current = null
      return true
    } catch (e) {
      const message = e.status === 401 ? 'Session expired. Please login again.' : (e.message || 'Auto-save failed')
      lastSaveErrorRef.current = message
      setAutoSaveError(message)
      setAutoSaveStatus('error')
      return false
    } finally {
      if (inFlightRef.current === request) inFlightRef.current = null
    }
  }, [])

  // Debounced auto-save: runs AUTOSAVE_DELAY_MS after the last edit to an existing note.
  useEffect(() => {
    if (!(createMode || editMode)) return undefined
    const body = diffFromBaseline(form)
    if (Object.keys(body).length === 0) return undefined
    // Never auto-save a blank title (the server rejects it).
    if (!form.title.trim()) return undefined

    setAutoSaveStatus('pending')
    setAutoSaveError(null)
    clearAutoSaveTimer()
    autoSaveTimerRef.current = setTimeout(async () => {
      autoSaveTimerRef.current = null
      if (editingNote) {
        performAutoSave(editingNote.id, form)
        return
      }
      // A create is already in flight (editingNote is still null until its response
      // lands). Without this guard, continued typing re-arms this debounce and a
      // second POST would duplicate the note. Any keystrokes made meanwhile are
      // picked up once setEditingNote below re-runs this effect.
      if (creatingRef.current) return
      creatingRef.current = true
      const createRequest = notesApi.create({ title: form.title.trim(), content: form.content, tags: form.tags.trim().toLowerCase() })
      createInFlightRef.current = createRequest
      try {
        const created = await createRequest
        createdNoteRef.current = created
        baselineRef.current = {
          title: created.title || form.title.trim() || '',
          content: created.content || form.content || '',
          tags: (created.tags || form.tags.trim().toLowerCase() || ''),
          version: created.version ?? 0,
        }
        setEditingNote(created)
        // A height chosen before the note existed is saved now that it has an id.
        if (editorHeightRef.current !== DEFAULT_EDITOR_HEIGHT) {
          notesApi.setEditorHeight(created.id, editorHeightRef.current).catch(() => {})
        }
        // Keep the form as-is: the user may have kept typing while the request was
        // in flight. The effect re-runs once editingNote is set and saves any
        // difference between the current form and the created note as an update.
        autoSavedRef.current = true
        lastSaveErrorRef.current = null
        setAutoSaveStatus('saved')
        setLastSaved(new Date())
        await reload()
        setCreateMode(false)
        setEditMode(true)
      } catch (e) {
        const message = e.status === 401 ? 'Session expired. Please login again.' : (e.message || 'Failed to save note')
        lastSaveErrorRef.current = message
        setAutoSaveError(message)
        setAutoSaveStatus('error')
        setFormError(message)
      } finally {
        if (createInFlightRef.current === createRequest) createInFlightRef.current = null
        creatingRef.current = false
      }
    }, AUTOSAVE_DELAY_MS)

    return clearAutoSaveTimer
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form, createMode, editMode, editingNote, performAutoSave])

  // Wait for any in-flight auto-save or create so manual save/close don't race with it.
  const waitForInFlight = async () => {
    for (;;) {
      const current = inFlightRef.current || createInFlightRef.current
      if (!current) break
      try {
        await current
      } catch (e) {
        // error already surfaced via autoSaveStatus
      }
      if ((inFlightRef.current || createInFlightRef.current) === current) break
    }
  }

  /**
   * Persist everything the editor currently holds, regardless of the debounce
   * timer or of whether the first (create) save has run yet. Used by Close,
   * "List Notes", "Create Note", navigating to Settings/Help, and unmounting.
   *
   * Returns true when the note is fully persisted (or there was nothing the
   * server would accept), false when saving failed — callers must then keep the
   * editor open instead of navigating and losing data.
   */
  const flushPendingChanges = async () => {
    if (!(createMode || editMode)) return true
    clearAutoSaveTimer()
    await waitForInFlight()

    // A brand-new note whose debounced first save has not run yet: create it now
    // with every field instead of throwing the draft away.
    let note = editingNote || createdNoteRef.current
    if (!note) {
      const title = form.title.trim()
      if (!title) return true // the server requires a title; nothing saveable
      setAutoSaveStatus('saving')
      try {
        const created = await notesApi.create({
          title,
          content: form.content,
          tags: form.tags.trim().toLowerCase(),
        })
        createdNoteRef.current = created
        baselineRef.current = {
          title: created.title || title,
          content: created.content || form.content || '',
          tags: created.tags || form.tags.trim().toLowerCase() || '',
          version: created.version ?? 0,
        }
        setEditingNote(created)
        note = created
        // Persist a height chosen before the note existed.
        if (editorHeightRef.current !== DEFAULT_EDITOR_HEIGHT) {
          notesApi.setEditorHeight(created.id, editorHeightRef.current).catch(() => {})
        }
        autoSavedRef.current = true
        lastSaveErrorRef.current = null
        setAutoSaveError(null)
        setLastSaved(new Date())
        setAutoSaveStatus('saved')
      } catch (e) {
        const message = e.status === 401 ? 'Session expired. Please login again.' : (e.message || 'Failed to save note')
        lastSaveErrorRef.current = message
        setAutoSaveError(message)
        setFormError(message)
        setAutoSaveStatus('error')
        return false
      }
    }

    if (!note) return true
    if (!form.title.trim()) return true // an update with a blank title would be rejected

    // Reuse the auto-save pipeline (serialized, optimistic-lock aware) so every
    // field — title, tags, content — goes out together.
    const saved = await performAutoSave(note.id, form)
    if (!saved) {
      const message = lastSaveErrorRef.current || 'Auto-save failed'
      setError(`Failed to save note: ${message}`)
    }
    return saved
  }
  flushPendingChangesRef.current = flushPendingChanges

  // Best-effort save if the editor is unmounted without an explicit navigation
  // (e.g. browser back/forward within the SPA). The page stays alive, so the
  // in-flight request still completes even though the component is gone.
  useEffect(() => () => {
    flushPendingChangesRef.current().catch(() => {})
  }, [])

  // A full page unload/refresh cannot await a request, so at least warn while
  // fields are pending instead of losing them silently.
  useEffect(() => {
    if (!(createMode || editMode)) return undefined
    const onBeforeUnload = (event) => {
      const hasNote = !!(editingNote || createdNoteRef.current)
      const dirty = hasNote
        ? Object.keys(diffFromBaseline(form)).length > 0
        : !!(form.title.trim() || form.content || form.tags.trim())
      if (!dirty) return
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [createMode, editMode, editingNote, form])

  const closeModal = async () => {
    if (!(createMode || editMode)) return
    const saved = await flushPendingChanges()
    if (!saved) return // keep the editor open so the user can retry/copy
    setEditMode(false)
    setCreateMode(false)
    setAutoSaveStatus('idle')
    createdNoteRef.current = null
    if (autoSavedRef.current) {
      autoSavedRef.current = false
      await reload()
    }
  }

  const previewAttachmentRef = useRef(null)
  previewAttachmentRef.current = previewAttachment
  const [downloadError, setDownloadError] = useState(null)
  const [downloading, setDownloading] = useState(false)

  // Download from the preview modal: fetch the bytes as a Blob and save via the
  // DOM `download` attribute, so the browser never opens a viewer instead.
  const handleDownloadAttachment = async () => {
    const target = previewAttachmentRef.current
    if (!target || downloading) return
    setDownloadError(null)
    setDownloading(true)
    try {
      await notesApi.download(target.url, target.label)
    } catch (e) {
      setDownloadError(e.message || 'Failed to download attachment')
    } finally {
      setDownloading(false)
    }
  }

  /** Deletes the note (after confirmation); returns true when it was removed. */
  const remove = async (note) => {
    if (!window.confirm(`Delete note "${note.title || 'Untitled'}"?`)) return false
    try {
      await notesApi.remove(note.id)
      setError(null)
      await reload()
      return true
    } catch (e) {
      if (e.status === 401) {
        setError('Session expired. Please login again.')
      } else {
        setError(`Failed to delete note: ${e.message}`)
      }
      return false
    }
  }

  /** Delete from the note view: on success leave the editor (its note is gone). */
  const removeFromEditor = async () => {
    if (!editingNote) return
    clearAutoSaveTimer()
    if (await remove(editingNote)) {
      setEditMode(false)
      setEditingNote(null)
    }
  }

  const handleDropFiles = async (files, editorInstance) => {
    if (!files || files.length === 0) return
    setFormError(null)
    clearAutoSaveTimer()

    let targetNote = editingNote
    if (!targetNote) {
      // If the note has no title yet, derive the title from the first dropped filename.
      const resolvedTitle = form.title.trim() || deriveTitleFromFilename(files[0].name)
      const resolvedTags = form.tags.trim().toLowerCase()
      if (!form.title.trim()) {
        setForm((prev) => ({ ...prev, title: resolvedTitle }))
      }
      setAutoSaveStatus('saving')
      try {
        const created = await notesApi.create({
          title: resolvedTitle,
          content: form.content || '',
          tags: resolvedTags,
        })
        baselineRef.current = {
          title: created.title || resolvedTitle,
          content: created.content || form.content || '',
          tags: created.tags || resolvedTags,
          version: created.version ?? 0,
        }
        targetNote = created
        setEditingNote(created)
        autoSavedRef.current = true
        setCreateMode(false)
        setEditMode(true)
      } catch (e) {
        setAutoSaveError(e.message || 'Failed to create note for attachment')
        setAutoSaveStatus('error')
        setFormError(e.message || 'Failed to create note for attachment')
        return
      }
    }

    setAutoSaveStatus('saving')
    try {
      for (const file of files) {
        const uploaded = await notesApi.uploadAttachment(targetNote.id, file)
        if (!uploaded) continue
        if (editorInstance) {
          if ((uploaded.contentType || '').startsWith('image/')) {
            editorInstance
              .chain()
              .focus()
              .insertContent({
                type: 'image',
                attrs: { src: uploaded.url, alt: uploaded.filename, title: uploaded.filename },
              })
              .run()
          } else {
            editorInstance
              .chain()
              .focus()
              .insertContent(
                `<p><a href="${uploaded.url}">📄 ${uploaded.filename}</a></p>`,
              )
              .run()
          }
        }
      }
      const nextMarkdown = editorInstance?.storage?.markdown?.getMarkdown
        ? editorInstance.storage.markdown.getMarkdown()
        : form.content
      setForm((prev) => ({ ...prev, content: nextMarkdown }))
      await performAutoSave(targetNote.id, {
        ...form,
        title: form.title.trim() || targetNote.title,
        content: nextMarkdown,
      })
      await refreshNoteAttachments(targetNote.id)
      await reload()
    } catch (e) {
      setAutoSaveError(e.message || 'Failed to upload attachment')
      setAutoSaveStatus('error')
      setFormError(e.message || 'Failed to upload attachment')
    }
  }

  const handleImportJoplin = async (file) => {
    if (!file) return
    setImportLoading(true)
    setImportResult(null)
    try {
      const result = await notesApi.importJoplin(file)
      setImportResult(result)
      await reload()
    } catch (e) {
      setImportResult({
        imported: 0,
        errors: 1,
        messages: [e.message],
      })
    } finally {
      setImportLoading(false)
    }
  }

  const handleImportMarkdown = async (file) => {
    if (!file) return
    setImportLoading(true)
    setImportResult(null)
    try {
      await notesApi.importMarkdown(file)
      setImportResult({
        imported: 1,
        errors: 0,
        attachments: 0,
        messages: ['Note imported successfully'],
      })
      await reload()
    } catch (e) {
      setImportResult({
        imported: 0,
        errors: 1,
        messages: [e.message],
      })
    } finally {
      setImportLoading(false)
    }
  }

  return (
    <Stack gap="md" p={{ base: 'sm', sm: 'md' }} style={{ width: '100%' }}>
      {/* Header */}


      <Stack gap="md" style={{ flex: 1, minWidth: 0, width: '100%' }}>
      {/* Search Section */}
      {!(createMode || editMode) && searchPanelOpen && (
      <Stack gap="xs">
        <Group align="flex-end" wrap="nowrap" gap={{ base: 'xs', sm: 'md' }}>
          <TextInput
            style={{ flex: 1 }}
            placeholder="Search notes..."
            leftSection={<IconSearch size={16} />}
            value={search.q}
            onChange={(e) => setSearch({ ...search, q: e.currentTarget.value })}
            onKeyDown={(e) => {
              if (e.key === 'Enter') runSearch()
            }}
            size={inputSize}
          />
          <Button onClick={runSearch} size={controlSize}>Search</Button>
          <Button variant="default" onClick={clearSearch} disabled={!activeSearch && !search.q} size={controlSize}>
            Clear
          </Button>
        </Group>

        <Stack gap={6}>
          <Checkbox
            label="Search Titles"
            checked={search.searchTitles}
            onChange={(e) => setSearch({ ...search, searchTitles: e.currentTarget.checked })}
          />
          <Checkbox
            label="Find notes with attachments"
            checked={!!filters.hasAttachments}
            onChange={(e) => updateFilters({ hasAttachments: e.currentTarget.checked })}
          />
          <Checkbox
            label="Search Note Content"
            checked={search.searchContent}
            onChange={(e) => setSearch({ ...search, searchContent: e.currentTarget.checked })}
          />
          <Checkbox
            label="Search Tags"
            checked={search.searchTags}
            onChange={(e) => setSearch({ ...search, searchTags: e.currentTarget.checked })}
          />
        </Stack>

        {!(createMode || editMode) && activeFilterCount > 0 && (
          <Group gap="xs" aria-label="Active filters">
            <Badge variant="outline" size="lg">{activeFilterCount} active filter{activeFilterCount === 1 ? '' : 's'}</Badge>
            {activeSearch && <Badge variant="filled" size="lg" rightSection={<ActionIcon size="xs" variant="transparent" color="white" onClick={clearSearch} aria-label={`Clear search`}><IconX size={12} /></ActionIcon>} style={{ textTransform: "none" }}>{`Search: ${activeSearch.q.trim()}`}</Badge>}
            {activeFilterItems.map((item) => (
              <Badge key={item.key} variant="filled" size="lg" rightSection={<ActionIcon size="xs" variant="transparent" color="white" onClick={() => updateFilters(item.clear)} aria-label={`Clear ${item.label}`}><IconX size={12} /></ActionIcon>} style={{ textTransform: "none" }}>{item.label}</Badge>
            ))}
            <Button variant="subtle" size="xs" onClick={clearAll}>Clear all filters</Button>
          </Group>
        )}
      </Stack>
      )}

      {/* Alerts */}
      {storageInfo.overQuota && (
        <Alert color="yellow" title="Storage Limit Reached">
          <Group justify="space-between" align="center" wrap="wrap" gap="xs">
            <Text size="sm">
              You have reached your plan&apos;s storage limit ({(storageInfo.usedBytes / (1024 * 1024)).toFixed(2)} MB of {(storageInfo.limitBytes / (1024 * 1024)).toFixed(0)} MB used). Delete unneeded notes to free up space, or upgrade your plan to create new notes.
            </Text>
            <Button size="xs" variant="light" onClick={() => navigate('/settings')}>
              Upgrade Plan
            </Button>
          </Group>
        </Alert>
      )}
      {error && (
        <Alert color="red" withCloseButton onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {!(createMode || editMode) && (
        <Group justify="space-between" align="flex-end" wrap="nowrap" gap="xs">
          <Stack gap="xs">
            {activeSearch && (
              <Text fw={600} size={isMobile ? 'md' : 'lg'}>
                Search results for "{activeSearch.q.trim()}"
              </Text>
            )}
            <Text size="sm" c="dimmed" role="status">
              Found {notes.length} result{notes.length === 1 ? '' : 's'}
            </Text>
          </Stack>
          <Group gap={4} wrap="nowrap" style={{ flex: '0 0 auto' }} role="group" aria-label="Notes display mode">
            <Tooltip label="Card view">
              <ActionIcon
                variant={viewMode === 'card' ? 'filled' : 'light'}
                size="lg"
                onClick={() => setViewMode(viewMode === 'card' ? DEFAULT_VIEW_MODE : 'card')}
                aria-label="Show note cards"
                aria-pressed={viewMode === 'card'}
              >
                <IconLayoutGrid size={16} />
              </ActionIcon>
            </Tooltip>
            <Tooltip label="List view">
              <ActionIcon
                variant={viewMode === 'list' ? 'filled' : 'light'}
                size="lg"
                onClick={() => setViewMode(viewMode === 'list' ? DEFAULT_VIEW_MODE : 'list')}
                aria-label="Show note list"
                aria-pressed={viewMode === 'list'}
              >
                <IconList size={16} />
              </ActionIcon>
            </Tooltip>
          </Group>
        </Group>
      )}

      {/* Notes Display */}
      {(createMode || editMode) ? (
        <Stack gap="md" style={{ width: '100%', flex: 1, minWidth: 0 }}>
          {formError && <Alert color="red" withCloseButton onClose={() => setFormError(null)}>{formError}</Alert>}
          <Group justify="space-between" align="flex-end" wrap="nowrap" gap="xs" style={{ width: '100%' }}>
            <TextInput label="Title" style={{ flex: 1, minWidth: 0 }} required maxLength={255} value={form.title} error={formError && !form.title.trim()} onChange={(e) => setForm({ ...form, title: e.currentTarget.value })} size={inputSize} />
            {editingNote && (
              <Button variant="light" color="red" size={inputSize} onClick={removeFromEditor} style={{ flex: '0 0 auto' }}>
                Delete Note
              </Button>
            )}
          </Group>
          {editingNote && <Text size="xs" c="dimmed">Created: {new Date(editingNote.createdAt).toLocaleString()}</Text>}
          <div>
            <TextInput label="Tags (space-delimited)" w="100%" style={{ width: "100%" }} placeholder="joplin important work" maxLength={10000} value={form.tags} onChange={(e) => setForm({ ...form, tags: e.currentTarget.value.toLowerCase() })} size={inputSize} />
            {form.tags && (
              <Group gap="xs" mt="xs">
                {form.tags.split(/\s+/).filter((t) => t.length > 0).map((t, i) => (
                  <Badge key={i} size="sm" variant="light">{t}</Badge>
                ))}
              </Group>
            )}
          </div>
          {editingNote && visibleAttachments.length > 0 && (
            <div>
              <Text fw={500} size="sm" mb={6}>Attachments ({visibleAttachments.length})</Text>
              <Stack gap={4}>
                {visibleAttachments.map((attachment) => (
                  <Group key={attachment.id} justify="space-between" align="center" wrap="nowrap" gap="xs">
                    <Text size="sm" style={{ minWidth: 0, overflowWrap: 'anywhere' }}>
                      {attachment.filename}{' '}
                      <Text span size="xs" c="dimmed">({formatBytes(attachment.byteSize)})</Text>
                    </Text>
                    <Group gap={4} wrap="nowrap" style={{ flex: '0 0 auto' }}>
                      <Button size="xs" variant="light" onClick={() => setPreviewAttachment({ url: attachment.url, label: attachment.filename })}>
                        Preview
                      </Button>
                      <Button size="xs" variant="light" onClick={() => notesApi.download(attachment.url, attachment.filename)}>
                        Download
                      </Button>
                      <Button size="xs" variant="light" color="red" onClick={() => removeAttachment(attachment)}>
                        Delete
                      </Button>
                    </Group>
                  </Group>
                ))}
              </Stack>
              <Text size="xs" c="dimmed" mt={4}>
                Deleting removes the file and its link from this note. Unused attachments are
                cleaned up automatically a day after their last link is removed.
              </Text>
            </div>
          )}
          <div style={{ width: "100%" }}>
            <Text fw={500} size="sm" mb={6}>Content (drag and drop images or PDFs into the editor)</Text>
            <div style={{ width: "100%" }}>
              <MarkdownEditor
                value={form.content}
                onChange={(c) => setForm((prev) => ({ ...prev, content: c }))}
                onDropFiles={handleDropFiles}
                onPreviewAttachment={(url, label) => setPreviewAttachment({ url, label })}
                height={editorHeight}
                onHeightCommit={handleEditorHeightCommit}
              />
            </div>
          </div>
          <AutoSaveStatus status={autoSaveStatus} lastSaved={lastSaved} errorMessage={autoSaveError} />
          <Group justify={isMobile ? 'flex-end' : 'space-between'} gap="xs">
            <Button variant="default" onClick={closeModal} size={inputSize}>{editingNote ? 'Close' : 'Cancel'}</Button>

          </Group>
        </Stack>
      ) : notes.length === 0 ? (
        <Text c="dimmed" ta="center" py="xl">{activeFilterCount > 0 ? 'No notes match your search or filters.' : 'You have no notes yet. Use Create Note or Import some notes from a backup.'}</Text>
      ) : cardView ? (
        // Card view (small screens by default, or when explicitly selected)
        <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md" style={{ width: "100%" }}>
          {sortedNotes.map((n) => (
            <NoteCard key={n.id} note={n} onEdit={openEdit} onDelete={remove} onTogglePin={togglePin} />
          ))}
        </SimpleGrid>
      ) : (
        // Table view
        <Table striped highlightOnHover withTableBorder style={{ width: "100%" }}>
          <Table.Thead>
            <Table.Tr>
              <SortableTh label="Created" field="createdAt" sort={sort} onSort={toggleSort} />
              <SortableTh label="Updated" field="updatedAt" sort={sort} onSort={toggleSort} />
              <SortableTh label="Title" field="title" sort={sort} onSort={toggleSort} />
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {sortedNotes.map((n) => (
              <Table.Tr key={n.id} style={{ cursor: 'pointer' }} onClick={() => openEdit(n)}>
                <Table.Td>{new Date(n.createdAt).toLocaleString()}</Table.Td>
                <Table.Td>{new Date(n.updatedAt).toLocaleString()}</Table.Td>
                <Table.Td>
                  <Stack gap="xs">
                    <Text style={{ minWidth: 0 }}>{n.title || <Text c="dimmed" fs="italic">Untitled</Text>}</Text>
                    {n.tags && (
                      <Group gap="xs">
                        {n.tags.split(' ').filter(t => t.length > 0).map((tag, idx) => (
                          <Badge key={idx} size="sm" variant="light">{tag}</Badge>
                        ))}
                      </Group>
                    )}
                  </Stack>
                </Table.Td>
                <Table.Td>
                  <Group gap="xs" wrap="nowrap">
                    <AttachmentIcon count={n.attachmentCount} />
                    <PinButton note={n} onToggle={togglePin} />
                    <Button size="xs" variant="light" w={64} onClick={(e) => { e.stopPropagation(); openEdit(n) }}>Edit</Button>
                    <Button size="xs" variant="light" color="red" w={64} onClick={(e) => { e.stopPropagation(); remove(n) }}>Delete</Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      </Stack>

      {/* Import Modal */}
      <Modal
        opened={importModalOpened}
        onClose={() => setImportModalOpened(false)}
        title="Import notes"
        size={modalSize}
      >
        <Stack>
          {importResult && (
            <Alert
              color={importResult.errors === 0 ? 'primary' : 'yellow'}
              withCloseButton
              onClose={() => setImportResult(null)}
            >
              <Stack gap="xs">
                <Text size="sm">
                  Imported {importResult.imported} note{importResult.imported === 1 ? '' : 's'}
                  {importResult.attachments > 0 && (
                    ` and ${importResult.attachments} attachment${importResult.attachments === 1 ? '' : 's'}`
                  )}
                  {importResult.errors > 0 && ` with ${importResult.errors} error${importResult.errors === 1 ? '' : 's'}`}
                </Text>
                {importResult.messages.length > 0 && (
                  <List size="sm">
                    {importResult.messages.map((msg, idx) => (
                      <List.Item key={idx}>{msg}</List.Item>
                    ))}
                  </List>
                )}
              </Stack>
            </Alert>
          )}

          {importLoading && (
            <Stack align="center" gap="md">
              <Loader />
              <Text c="dimmed" size="sm">Importing...</Text>
            </Stack>
          )}

          {!importLoading && !importResult && (
            <Stack gap="md">
              <Stack gap="sm">
                <Text fw={500} size="sm">Import from Joplin (.jex)</Text>
                <Text size="xs" c="dimmed">
                  Export from Joplin, then upload the .jex file. Folder hierarchy will be converted to tags, and
                  attachments referenced in your notes are imported and linked automatically.
                </Text>
                <FileInput
                  placeholder="Choose .jex file"
                  accept=".jex"
                  onChange={handleImportJoplin}
                  disabled={importLoading}
                  size="sm"
                />
              </Stack>

              <Stack gap="sm">
                <Text fw={500} size="sm">Import Markdown file (.md)</Text>
                <Text size="xs" c="dimmed">
                  Upload a single markdown file. The first line will be used as the title.
                </Text>
                <FileInput
                  placeholder="Choose .md file"
                  accept=".md"
                  onChange={handleImportMarkdown}
                  disabled={importLoading}
                  size="sm"
                />
              </Stack>
            </Stack>
          )}
        </Stack>
      </Modal>

      {/* Help Modal */}
      <Modal
        opened={helpOpened}
        onClose={() => setHelpOpened(false)}
        title="How to search your notes"
        size={modalSize}
      >
        <Stack>
          <Text size="sm">
            Choose what to search and which notes to keep. Please check at least one of the search
            boxes:
          </Text>
          <List spacing="xs" size="sm">
            <List.Item><b>Search Titles</b> – match words in note titles.</List.Item>
            <List.Item><b>Search Note Content</b> – match words in the body of your notes.</List.Item>
            <List.Item><b>Search Tags</b> – match tags by part of a word: "sup" finds
              tags like <i>super-duper</i> and <i>supper</i>, and "grq" finds notes in
              folders like <i>grq</i> and <i>grq/cobol</i>. Notes with no tags are
              included too.</List.Item>
          </List>
          <Text size="sm">
            Check any combination to search titles, content and tags together. Then type your
            search words and press <b>Search</b> (or Enter).
          </Text>
          <Text size="sm">
            <b>Find notes with attachments</b> keeps only notes that have files. The header's
            <b>Pinned</b> and <b>Untagged</b> buttons are filters that combine with your search.
          </Text>
          <Text size="xs" c="dimmed">
            Titles and content are word-based and ignore case, common words (like "the"), and word
            endings (for example, "running" also matches "run"); tags match on any part of the tag.
            All of your words must appear in the same field. Clear the search box and press
            Search, or press Clear, to see all notes again. Tag filters from the same panel are
            combined with the text search.
          </Text>
          <Group justify="flex-end">
            <Button onClick={() => setHelpOpened(false)} size="sm">Got it</Button>
          </Group>
        </Stack>
      </Modal>

      {/* Attachment / PDF Preview Modal */}
      <Modal
        opened={!!previewAttachment}
        onClose={() => {
          setPreviewAttachment(null)
          setDownloadError(null)
        }}
        title={previewAttachment?.label || 'Attachment Preview'}
        size="xl"
      >
        {previewAttachment && (
          <Stack gap="sm">
            <iframe
              src={previewAttachment.url}
              title={previewAttachment.label || 'Attachment'}
              style={{ width: '100%', height: '70vh', border: '1px solid var(--mantine-color-default-border)', borderRadius: 6 }}
            />
            {downloadError && (
              <Alert color="red" withCloseButton onClose={() => setDownloadError(null)}>
                Download failed: {downloadError}
              </Alert>
            )}
            <Group justify="flex-end">
              <Button
                leftSection={<IconDownload size={14} />}
                variant="light"
                size="sm"
                loading={downloading}
                onClick={handleDownloadAttachment}
              >
                Download
              </Button>
              <Button
                component="a"
                href={previewAttachment.url}
                target="_blank"
                rel="noopener noreferrer"
                variant="light"
                size="sm"
              >
                Open in New Tab
              </Button>
              <Button onClick={() => setPreviewAttachment(null)} size="sm">
                Close
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Stack>
  )
}
