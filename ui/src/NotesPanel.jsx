import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ActionIcon, Alert, Box, Button, Collapse, Checkbox, Group, List, Modal, Stack, Table, Text, TextInput, Title, UnstyledButton, Badge, FileInput, Loader, Card, SimpleGrid, Tooltip } from '@mantine/core'
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
import FilterPanel, { DEFAULT_FILTERS, buildApiFilters, describeFilters } from './FilterPanel'

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

function MarkdownEditor({ value, onChange, onDropFiles, onPreviewAttachment }) {
  const [dragOver, setDragOver] = useState(false)
  const dropHandlerRef = useRef(onDropFiles)
  dropHandlerRef.current = onDropFiles

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

        <RichTextEditor.Content
          style={{
            minHeight: 400,
            maxHeight: '70vh',
            overflowY: 'auto',
            border: dragOver
              ? '2px dashed var(--mantine-primary-color-filled)'
              : '1px solid var(--mantine-color-gray-3)',
            width: '100%',
          }}
        />
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
              <Button size="xs" variant="light" onClick={(e) => { e.stopPropagation(); onEdit(note) }}>Edit</Button>
            </Group>
            <Group gap="xs" wrap="nowrap">
              <Box w={42} style={{ display: 'flex', justifyContent: 'center' }}>
                <AttachmentIcon count={note.attachmentCount} />
              </Box>
              <Button size="xs" variant="light" color="red" onClick={(e) => { e.stopPropagation(); onDelete(note) }}>Delete</Button>
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
  const autoSaveTimerRef = useRef(null)
  // Last values known to be persisted on the server for the note being edited.
  const baselineRef = useRef(null)
  // Promise of an in-flight auto-save request, so manual save/close can wait for it.
  const inFlightRef = useRef(null)
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

  // Advanced filters (persisted) and the unfiltered note list
  // used for tag options and smart collection counts.
  const [filters, setFilters] = useState(() => ({ ...DEFAULT_FILTERS, ...readStorage(FILTERS_KEY, {}) }))
  const [allNotes, setAllNotes] = useState([])
  const [filtersOpen, setFiltersOpen] = useState(false)
  // Card vs list display preference ('auto' follows screen size). Persisted.
  const [viewMode, setViewMode] = useState(() => readStorage(VIEW_KEY, DEFAULT_VIEW_MODE))
  useEffect(() => writeStorage(VIEW_KEY, viewMode), [viewMode])
  const cardView = viewMode === 'card' || (viewMode === 'auto' && isMobile)

  // Import states
  const [importModalOpened, setImportModalOpened] = useState(false)
  const [searchVisible, setSearchVisible] = useState(false)
  const [tagsVisible, setTagsVisible] = useState(false)
  const [importLoading, setImportLoading] = useState(false)
  const [importResult, setImportResult] = useState(null)
  const [previewAttachment, setPreviewAttachment] = useState(null)
  const [storageInfo, setStorageInfo] = useState({
    usedBytes: 0,
    limitBytes: 100 * 1024 * 1024,
    noteCount: 0,
    overQuota: false,
  })

  const load = useCallback(async (searchParams, filterState) => {
    try {
      const apiFilters = buildApiFilters(filterState)
      const [filtered, everything, storage] = await Promise.all([
        notesApi.list(searchParams, apiFilters),
        notesApi.list(),
        notesApi.storage().catch(() => null),
      ])
      setNotes(filtered)
      setAllNotes(everything)
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

  const resetAll = () => {
    clearAutoSaveTimer()
    setSearchVisible(false)
    setTagsVisible(false)
    setImportModalOpened(false)
    setCreateMode(false)
    setEditMode(false)
    setActiveSearch(null)
    setSearch(DEFAULT_SEARCH)
    setFilters({ ...DEFAULT_FILTERS })
    setError(null)
    setFormError(null)
  }

  const openCreate = () => {
    if (storageInfo.overQuota) {
      setError(
        'You have reached your plan storage limit. Please delete unneeded notes to free up space or upgrade your plan in Personal Settings.',
      )
      return
    }
    clearAutoSaveTimer()
    baselineRef.current = null
    autoSavedRef.current = false
    setAutoSaveStatus('idle')
    setLastSaved(null)
    setEditingNote(null)
    setForm(EMPTY_FORM)
    setFormError(null)
    setEditMode(false)
    setCreateMode(true)
  }

  useEffect(() => writeStorage(FILTERS_KEY, filters), [filters])
  // Re-register whenever the values the header reads change. registerActions is a
  // stable callback in App, so without these deps the handlers and the open/closed
  // button state would stay frozen at their first-render values forever.
  useEffect(() => {
    if (registerActions) {
      registerActions({
        onCreate: openCreate,
        onRefresh: reload,
        onImport: () => {
          if (storageInfo.overQuota) {
            setError(
              'You have reached your plan storage limit. Please delete unneeded notes to free up space or upgrade your plan in Personal Settings.',
            )
            return
          }
          setImportModalOpened(true)
        },
        onToggleSearch: () => setSearchVisible((v) => !v),
        onToggleTags: () => setTagsVisible((v) => !v),
        onSetPinned: () => {
          if (filters.collection === 'pinned') {
            setFilters((f) => ({ ...f, collection: null }))
          } else {
            resetAll()
            setFilters({ ...DEFAULT_FILTERS, collection: 'pinned' })
            setTagsVisible(false)
            setSearchVisible(false)
          }
        },
        onReset: resetAll,
        inEditor: createMode || editMode,
        createOpen: createMode,
        searchOpen: searchVisible,
        tagsOpen: tagsVisible,
        pinnedActive: filters.collection === 'pinned',
        importOpen: importModalOpened,
        overQuota: storageInfo.overQuota,
      })
    }
  }, [registerActions, createMode, editMode, searchVisible, tagsVisible, importModalOpened, activeSearch, filters, storageInfo.overQuota])

  const updateFilters = (patch) => {
    setFilters((f) => ({ ...f, ...patch }))
  }

  const activeFilterItems = useMemo(() => describeFilters(filters), [filters])
  const activeFilterCount = activeFilterItems.length + (activeSearch ? 1 : 0)

  const allTags = useMemo(() => {
    const set = new Set(filters.tags)
    allNotes.forEach((n) => (n.tags || '').split(' ').filter(Boolean).forEach((t) => set.add(t)))
    return [...set].sort((a, b) => a.localeCompare(b))
  }, [allNotes, filters.tags])

  const collectionCounts = useMemo(() => ({
    untagged: allNotes.filter((n) => !(n.tags || '').trim()).length,
    pinned: allNotes.filter((n) => n.pinned).length,
  }), [allNotes])

  const resultCounts = useMemo(() => ({
    pinned: notes.filter((n) => n.pinned).length,
    untagged: notes.filter((n) => !(n.tags || '').trim()).length,
  }), [notes])

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
      return
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
    } catch (e) {
      setAutoSaveError(e.status === 401 ? 'Session expired. Please login again.' : (e.message || 'Auto-save failed'))
      setAutoSaveStatus('error')
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
      try {
        const created = await notesApi.create({ title: form.title.trim(), content: form.content, tags: form.tags.trim().toLowerCase() })
        baselineRef.current = {
          title: created.title || form.title.trim() || '',
          content: created.content || form.content || '',
          tags: (created.tags || form.tags.trim().toLowerCase() || ''),
          version: created.version ?? 0,
        }
        setEditingNote(created)
        // Keep the form as-is: the user may have kept typing while the request was
        // in flight. The effect re-runs once editingNote is set and saves any
        // difference between the current form and the created note as an update.
        autoSavedRef.current = true
        setAutoSaveStatus('saved')
        setLastSaved(new Date())
        await reload()
        setCreateMode(false)
        setEditMode(true)
      } catch (e) {
        setAutoSaveError(e.message || 'Failed to save note')
        setAutoSaveStatus('error')
        setFormError(e.message || 'Failed to save note')
      } finally {
        creatingRef.current = false
      }
    }, AUTOSAVE_DELAY_MS)

    return clearAutoSaveTimer
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form, createMode, editMode, editingNote, performAutoSave])

  // Wait for any in-flight auto-save so manual save/close don't race with it.
  const waitForInFlight = async () => {
    while (inFlightRef.current) {
      const current = inFlightRef.current
      try {
        await current
      } catch (e) {
        // error already surfaced via autoSaveStatus
      }
      if (inFlightRef.current === current) break
    }
  }

  const closeModal = async () => {
    if ((createMode || editMode) && !editingNote) {
      clearAutoSaveTimer()
      setCreateMode(false)
      setEditMode(false)
      setAutoSaveStatus('idle')
      setFormError(null)
      return
    }
    clearAutoSaveTimer()
    await waitForInFlight()
    // Flush any edits still waiting on the debounce timer so nothing typed is lost.
    if (editingNote && form.title.trim()) {
      const body = diffFromBaseline(form)
      if (Object.keys(body).length > 0) {
        try {
          const expectedVersion = baselineRef.current?.version
          const payload = expectedVersion != null ? { ...body, version: expectedVersion } : body
          await notesApi.update(editingNote.id, payload)
          autoSavedRef.current = true
        } catch (e) {
          setError(`Failed to save note: ${e.message}`)
        }
      }
    }
    setEditMode(false)
    setCreateMode(false)
    setAutoSaveStatus('idle')
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

  const remove = async (note) => {
    if (!window.confirm(`Delete note "${note.title || 'Untitled'}"?`)) return
    try {
      await notesApi.remove(note.id)
      setError(null)
      await reload()
    } catch (e) {
      if (e.status === 401) {
        setError('Session expired. Please login again.')
      } else {
        setError(`Failed to delete note: ${e.message}`)
      }
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

  const filterPanel = (
    <FilterPanel
      filters={filters}
      onChange={updateFilters}
      allTags={allTags}
      collectionCounts={collectionCounts}
    />
  )

  return (
    <Stack gap="md" p={{ base: 'sm', sm: 'md' }} style={{ width: '100%' }}>
      {/* Header */}


      <Stack gap="md" style={{ flex: 1, minWidth: 0, width: '100%' }}>
      {/* Search Section */}
      {!(createMode || editMode) && (searchVisible || tagsVisible) && (
      <Stack gap="xs">
        {searchVisible && (
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
          <Button onClick={runSearch} size={controlSize}>Search Text</Button>
          <Button variant="default" onClick={clearSearch} disabled={!activeSearch && !search.q} size={controlSize}>
            Clear
          </Button>
        </Group>
        )}

        {searchVisible && (
          <Group gap="lg">
            <Checkbox
              label="Search Titles"
              checked={search.searchTitles}
              onChange={(e) => setSearch({ ...search, searchTitles: e.currentTarget.checked })}
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
          </Group>
        )}
        {tagsVisible && (
          <Collapse expanded={tagsVisible}>{filterPanel}</Collapse>
        )}

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
          <TextInput label="Title" w="100%" style={{ width: "100%" }} required maxLength={255} value={form.title} error={formError && !form.title.trim()} onChange={(e) => setForm({ ...form, title: e.currentTarget.value })} size={inputSize} />
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
          <div style={{ width: "100%" }}>
            <Text fw={500} size="sm" mb={6}>Content (drag and drop images or PDFs into the editor)</Text>
            <div style={{ width: "100%" }}>
              <MarkdownEditor
                value={form.content}
                onChange={(c) => setForm((prev) => ({ ...prev, content: c }))}
                onDropFiles={handleDropFiles}
                onPreviewAttachment={(url, label) => setPreviewAttachment({ url, label })}
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
                    <Button size="xs" variant="light" onClick={(e) => { e.stopPropagation(); openEdit(n) }}>Edit</Button>
                    <Button size="xs" variant="light" color="red" onClick={(e) => { e.stopPropagation(); remove(n) }}>Delete</Button>
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
            Choose where to look before searching. Please check at least one of the boxes:
          </Text>
          <List spacing="xs" size="sm">
            <List.Item><b>Search Titles</b> – match words in note titles.</List.Item>
            <List.Item><b>Search Note Content</b> – match words in the body of your notes.</List.Item>
            <List.Item><b>Search Tags</b> – match tags by part of a word: "sup" finds
              tags like <i>super-duper</i> and <i>supper</i>, and "grq" finds notes in
              folders like <i>grq</i> and <i>grq/cobol</i>.</List.Item>
          </List>
          <Text size="sm">
            Check both to search titles and content together. Then type your search words and
            press <b>Search Text</b> (or Enter).
          </Text>
          <Text size="xs" c="dimmed">
            Search is word-based and ignores case, common words (like "the"), and word endings
            (for example, "running" also matches "run"). All of your words must appear in the
            same field. Clear the search box and press Search Text, or press Clear, to see all notes again.
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
