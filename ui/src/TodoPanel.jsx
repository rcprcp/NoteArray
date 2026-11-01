import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Checkbox,
  Group,
  Loader,
  Menu,
  Modal,
  Progress,
  Stack,
  Text,
  TextInput,
  Tooltip,
  UnstyledButton,
} from '@mantine/core'
import { useMediaQuery } from '@mantine/hooks'
import { notifications } from '@mantine/notifications'
import {
  IconArrowLeft,
  IconCalendar,
  IconCheck,
  IconChevronDown,
  IconChevronUp,
  IconDots,
  IconExternalLink,
  IconGripVertical,
  IconLink,
  IconNote,
  IconPin,
  IconPinnedOff,
  IconPlus,
  IconSubtask,
  IconTrash,
  IconX,
} from '@tabler/icons-react'
import { notesApi, todoApi } from './api'

const PRIORITIES = ['NONE', 'LOW', 'MEDIUM', 'HIGH']
const PRIORITY_LABELS = { NONE: 'None', LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' }
const PRIORITY_COLORS = { NONE: 'gray', LOW: 'blue', MEDIUM: 'yellow', HIGH: 'red' }

function localToday() {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

function dueColor(item) {
  if (!item.dueDate || item.checked) return 'gray'
  if (item.dueDate < localToday()) return 'red'
  if (item.dueDate === localToday()) return 'yellow'
  return 'blue'
}

export default function TodoPanel() {
  const navigate = useNavigate()
  const location = useLocation()
  const isMobile = useMediaQuery('(max-width: 768px)')

  const [lists, setLists] = useState([])
  const [listsLoading, setListsLoading] = useState(true)
  const listsLoadedRef = useRef(false)
  const [listsError, setListsError] = useState(null)
  const [selectedId, setSelectedId] = useState(null)

  const [detail, setDetail] = useState(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState(null)
  const [filter, setFilter] = useState('all')
  const [titleDraft, setTitleDraft] = useState('')

  const [newItemText, setNewItemText] = useState('')
  const [addingItem, setAddingItem] = useState(false)
  const newItemInputRef = useRef(null)
  // Set when a new list is created, so focus moves to its "Add an item" field
  // once the detail request has populated the panel.
  const focusNewItemRef = useRef(false)
  const [subItemParentId, setSubItemParentId] = useState(null)
  const [subItemText, setSubItemText] = useState('')
  const [editingItemId, setEditingItemId] = useState(null)
  const [editingText, setEditingText] = useState('')
  const [dueEditingId, setDueEditingId] = useState(null)
  const [dueDraft, setDueDraft] = useState('')
  const [busyItemId, setBusyItemId] = useState(null)

  const [draggedId, setDraggedId] = useState(null)
  const [dragOverId, setDragOverId] = useState(null)

  const [createOpen, setCreateOpen] = useState(false)
  const [newListTitle, setNewListTitle] = useState('')
  const [creatingList, setCreatingList] = useState(false)

  const [linkOpen, setLinkOpen] = useState(false)
  const [noteQuery, setNoteQuery] = useState('')
  const [noteResults, setNoteResults] = useState([])
  const [noteLoading, setNoteLoading] = useState(false)

  const loadLists = useCallback(async () => {
    // Only the first load shows the sidebar spinner; background refreshes keep
    // the cards visible so toggling an item does not make the whole panel flash.
    if (!listsLoadedRef.current) setListsLoading(true)
    setListsError(null)
    try {
      const data = await todoApi.list()
      setLists(Array.isArray(data) ? data : [])
    } catch (e) {
      setListsError(e.message || 'Failed to load TODO lists')
    } finally {
      listsLoadedRef.current = true
      setListsLoading(false)
    }
  }, [])

  const loadDetail = useCallback(async (id, silent = false) => {
    if (!id) {
      setDetail(null)
      return
    }
    if (!silent) setDetailLoading(true)
    setDetailError(null)
    try {
      const data = await todoApi.get(id)
      setDetail(data)
      setTitleDraft(data?.title || '')
    } catch (e) {
      setDetail(null)
      setDetailError(e.message || 'Failed to load this TODO list')
    } finally {
      if (!silent) setDetailLoading(false)
    }
  }, [])

  useEffect(() => {
    loadLists()
  }, [loadLists])

  // A list can be opened from a note ("Create TODO list from checklist").
  useEffect(() => {
    const listId = location.state?.listId
    if (!listId) return
    setSelectedId(listId)
    navigate(location.pathname, { replace: true, state: null })
  }, [location.state, location.pathname, navigate])

  // Keep a valid selection as lists are created/deleted. Wait for the first
  // load so a deep-linked list id is not cleared before it arrives.
  useEffect(() => {
    if (listsLoading) return
    if (selectedId && lists.some((list) => list.id === selectedId)) return
    setSelectedId(lists.length > 0 ? lists[0].id : null)
  }, [lists, selectedId, listsLoading])

  useEffect(() => {
    loadDetail(selectedId)
  }, [selectedId, loadDetail])

  // After creating a list, wait for its detail to render, then put the cursor
  // in the item input so the user can start typing immediately.
  useEffect(() => {
    if (!focusNewItemRef.current) return
    if (!detail || detail.id !== selectedId) return
    focusNewItemRef.current = false
    newItemInputRef.current?.focus()
  }, [detail, selectedId])

  // Note picker for "Link note".
  useEffect(() => {
    if (!linkOpen) return undefined
    let active = true
    const timer = setTimeout(async () => {
      setNoteLoading(true)
      try {
        const query = noteQuery.trim()
        const search = query
          ? { q: query, searchTitles: true, searchContent: true, searchTags: true }
          : null
        const page = await notesApi.listPage(search, null, { limit: 50 })
        if (active) setNoteResults(Array.isArray(page?.items) ? page.items : [])
      } catch (e) {
        if (active) setNoteResults([])
      } finally {
        if (active) setNoteLoading(false)
      }
    }, 250)
    return () => {
      active = false
      clearTimeout(timer)
    }
  }, [linkOpen, noteQuery])

  const selectedSummary = useMemo(
    () => lists.find((list) => list.id === selectedId) || null,
    [lists, selectedId],
  )

  const itemsById = useMemo(
    () => new Map((detail?.items || []).map((item) => [item.id, item])),
    [detail],
  )

  const depthOf = useCallback((item) => {
    let depth = 0
    let parentId = item.parentId
    const seen = new Set()
    while (parentId && itemsById.has(parentId) && !seen.has(parentId)) {
      seen.add(parentId)
      depth += 1
      parentId = itemsById.get(parentId).parentId
    }
    return depth
  }, [itemsById])

  const visibleItems = useMemo(() => {
    const items = detail?.items || []
    if (filter === 'all') return items
    return items.filter((item) => (filter === 'done' ? item.checked : !item.checked))
  }, [detail, filter])

  const total = detail?.items?.length || 0
  const done = detail?.items?.filter((item) => item.checked).length || 0
  const progress = total ? Math.round((done / total) * 100) : 0

  const notifyError = (title, error) => {
    notifications.show({
      color: 'red',
      title,
      message: error?.message || 'Please try again.',
    })
  }

  const patchItem = async (item, patch) => {
    if (!detail) return false
    setBusyItemId(item.id)
    try {
      const updated = await todoApi.updateItem(detail.id, item.id, {
        ...patch,
        version: item.version,
      })
      setDetail((current) => current
        ? { ...current, items: current.items.map((row) => (row.id === item.id ? updated : row)) }
        : current)
      loadLists()
      return true
    } catch (e) {
      notifyError('Could not update item', e)
      await loadDetail(detail.id)
      return false
    } finally {
      setBusyItemId(null)
    }
  }

  const toggleItem = async (item) => {
    if (!detail || busyItemId === item.id) return
    setDetail((current) => current
      ? { ...current, items: current.items.map((row) => (row.id === item.id ? { ...row, checked: !row.checked } : row)) }
      : current)
    setBusyItemId(item.id)
    try {
      const updated = await todoApi.updateItem(detail.id, item.id, {
        checked: !item.checked,
        version: item.version,
      })
      setDetail((current) => current
        ? { ...current, items: current.items.map((row) => (row.id === item.id ? updated : row)) }
        : current)
      loadLists()
    } catch (e) {
      notifyError('Could not update item', e)
      await loadDetail(detail.id)
    } finally {
      setBusyItemId(null)
    }
  }

  const saveItemText = async (item) => {
    if (editingItemId !== item.id) return
    const text = editingText.trim()
    setEditingItemId(null)
    if (!text || text === item.text) return
    await patchItem(item, { text })
  }

  const savePriority = async (item, priority) => {
    if (priority === item.priority) return
    await patchItem(item, { priority })
  }

  const saveDueDate = async (item, value) => {
    setDueEditingId(null)
    const dueDate = (value || '').trim()
    if (dueDate === (item.dueDate || '')) return
    await patchItem(item, { dueDate })
  }

  const addItem = async (parentId = null) => {
    if (!detail) return
    const text = (parentId ? subItemText : newItemText).trim()
    if (!text) return
    setAddingItem(true)
    try {
      await todoApi.addItem(detail.id, { text, parentId })
      if (parentId) {
        setSubItemParentId(null)
        setSubItemText('')
      } else {
        setNewItemText('')
      }
      await loadDetail(detail.id, true)
      await loadLists()
      // Keep the cursor in the main add field so several items can be typed
      // in a row without clicking back into it.
      if (!parentId) {
        requestAnimationFrame(() => newItemInputRef.current?.focus())
      }
    } catch (e) {
      notifyError('Could not add item', e)
    } finally {
      setAddingItem(false)
    }
  }

  const deleteItem = async (item) => {
    if (!detail) return
    const children = (detail.items || []).some((row) => row.parentId === item.id)
    if (!window.confirm(`Delete "${item.text}"${children ? ' and its sub-items' : ''}?`)) return
    setBusyItemId(item.id)
    try {
      await todoApi.removeItem(detail.id, item.id)
      await loadDetail(detail.id, true)
      await loadLists()
    } catch (e) {
      notifyError('Could not delete item', e)
    } finally {
      setBusyItemId(null)
    }
  }

  const saveTitle = async () => {
    if (!detail) return
    const title = titleDraft.trim()
    if (!title || title === detail.title) {
      setTitleDraft(detail.title || '')
      return
    }
    try {
      const updated = await todoApi.update(detail.id, { title, version: detail.version })
      setDetail(updated)
      setTitleDraft(updated.title || '')
      await loadLists()
    } catch (e) {
      notifyError('Could not rename list', e)
      await loadDetail(detail.id)
    }
  }

  const togglePin = async () => {
    if (!detail) return
    try {
      const updated = await todoApi.update(detail.id, {
        pinned: !detail.pinned,
        version: detail.version,
      })
      setDetail(updated)
      await loadLists()
    } catch (e) {
      notifyError('Could not update list', e)
    }
  }

  const deleteList = async () => {
    if (!detail) return
    if (!window.confirm(`Delete TODO list "${detail.title}" and all of its items?`)) return
    try {
      await todoApi.remove(detail.id)
      setDetail(null)
      // The selection effect picks the next list once the refreshed list arrives.
      await loadLists()
    } catch (e) {
      notifyError('Could not delete list', e)
    }
  }

  const createList = async (event) => {
    event?.preventDefault()
    const title = newListTitle.trim()
    if (!title) return
    setCreatingList(true)
    try {
      const created = await todoApi.create({ title })
      setCreateOpen(false)
      setNewListTitle('')
      focusNewItemRef.current = true
      await loadLists()
      setSelectedId(created.id)
    } catch (e) {
      notifyError('Could not create list', e)
    } finally {
      setCreatingList(false)
    }
  }

  const linkNote = async (note) => {
    if (!detail) return
    try {
      const updated = await todoApi.linkNote(detail.id, note.id)
      setDetail(updated)
      setLinkOpen(false)
      await loadLists()
    } catch (e) {
      notifyError('Could not link note', e)
    }
  }

  const unlinkNote = async () => {
    if (!detail) return
    try {
      const updated = await todoApi.linkNote(detail.id, null)
      setDetail(updated)
      setLinkOpen(false)
      await loadLists()
    } catch (e) {
      notifyError('Could not unlink note', e)
    }
  }

  const openLinkedNote = () => {
    if (detail?.noteId) {
      navigate('/notes', { state: { openNoteId: detail.noteId } })
    }
  }

  const isDescendant = (item, ancestorId, byId) => {
    let cursor = item.parentId
    const seen = new Set()
    while (cursor && !seen.has(cursor)) {
      if (cursor === ancestorId) return true
      seen.add(cursor)
      cursor = byId.get(cursor)?.parentId || null
    }
    return false
  }

  const siblingIndex = (item) => {
    const siblings = (detail?.items || []).filter((row) => row.parentId === item.parentId)
    return siblings.findIndex((row) => row.id === item.id)
  }

  const canMoveUp = (item) => siblingIndex(item) > 0

  const canMoveDown = (item) => {
    const siblings = (detail?.items || []).filter((row) => row.parentId === item.parentId)
    const index = siblingIndex(item)
    return index >= 0 && index < siblings.length - 1
  }

  /** Keyboard/mobile fallback for drag: swap this item's subtree with its sibling. */
  const moveSibling = async (item, direction) => {
    if (!detail) return
    const siblings = detail.items.filter((row) => row.parentId === item.parentId)
    const index = siblings.findIndex((row) => row.id === item.id)
    const target = siblings[index + direction]
    if (!target) return

    const byId = new Map(detail.items.map((row) => [row.id, row]))
    const blockFor = (node) => detail.items.filter(
      (row) => row.id === node.id || isDescendant(row, node.id, byId),
    )
    const blockA = blockFor(item)
    const blockB = blockFor(target)
    const aStart = detail.items.findIndex((row) => row.id === item.id)
    const bStart = detail.items.findIndex((row) => row.id === target.id)
    if (aStart < 0 || bStart < 0) return

    const next = aStart < bStart
      ? [
        ...detail.items.slice(0, aStart),
        ...blockB,
        ...detail.items.slice(aStart + blockA.length, bStart),
        ...blockA,
        ...detail.items.slice(bStart + blockB.length),
      ]
      : [
        ...detail.items.slice(0, bStart),
        ...blockA,
        ...detail.items.slice(bStart + blockB.length, aStart),
        ...blockB,
        ...detail.items.slice(aStart + blockA.length),
      ]

    try {
      const updated = await todoApi.reorder(
        detail.id,
        next.map((row) => ({ id: row.id, parentId: row.parentId })),
      )
      setDetail(updated)
      await loadLists()
    } catch (e) {
      notifyError('Could not reorder items', e)
      await loadDetail(detail.id)
    }
  }

  const handleDrop = async (target, after) => {
    if (!detail || !draggedId || draggedId === target.id) {
      setDraggedId(null)
      setDragOverId(null)
      return
    }
    const byId = new Map((detail.items || []).map((item) => [item.id, item]))
    const dragged = byId.get(draggedId)
    if (!dragged || isDescendant(target, dragged.id, byId)) {
      setDraggedId(null)
      setDragOverId(null)
      return
    }

    // Move the dragged item together with its subtree as one block.
    const blockIds = new Set([dragged.id])
    let grew = true
    while (grew) {
      grew = false
      for (const item of detail.items) {
        if (!blockIds.has(item.id) && item.parentId && blockIds.has(item.parentId)) {
          blockIds.add(item.id)
          grew = true
        }
      }
    }
    const block = detail.items.filter((item) => blockIds.has(item.id))
    const remaining = detail.items.filter((item) => !blockIds.has(item.id))
    const targetIndex = remaining.findIndex((item) => item.id === target.id)
    if (targetIndex < 0) {
      setDraggedId(null)
      setDragOverId(null)
      return
    }

    const insertAt = targetIndex + (after ? 1 : 0)
    const moved = block.map((item, index) => (
      index === 0 ? { ...item, parentId: target.parentId } : item
    ))
    const next = [
      ...remaining.slice(0, insertAt),
      ...moved,
      ...remaining.slice(insertAt),
    ]

    setDraggedId(null)
    setDragOverId(null)
    try {
      const updated = await todoApi.reorder(
        detail.id,
        next.map((item) => ({ id: item.id, parentId: item.parentId })),
      )
      setDetail(updated)
      await loadLists()
    } catch (e) {
      notifyError('Could not reorder items', e)
      await loadDetail(detail.id)
    }
  }

  return (
    <Stack gap="md" p={{ base: 'sm', sm: 'md' }} style={{ width: '100%' }}>
      {listsError && (
        <Alert color="red" withCloseButton onClose={() => setListsError(null)}>
          {listsError}
        </Alert>
      )}

      <Group align="flex-start" gap="md" wrap="nowrap" style={{ width: '100%' }}>
        {(!isMobile || !selectedId) && (
          <Card withBorder padding="md" style={{ width: isMobile ? '100%' : 340, flex: '0 0 auto' }}>
            <Stack gap="sm">
              <Group justify="space-between">
                <Text fw={600} size="lg">TODO Lists</Text>
                <Button size="xs" leftSection={<IconPlus size={14} />} onClick={() => setCreateOpen(true)}>
                  New
                </Button>
              </Group>

              {listsLoading ? (
                <Group justify="center" py="md"><Loader size="sm" /></Group>
              ) : lists.length === 0 ? (
                <Text c="dimmed" size="sm">No TODO lists yet. Create one to get started.</Text>
              ) : (
                <Stack gap="xs">
                  {lists.map((list) => {
                    const listProgress = list.totalCount
                      ? Math.round(((list.totalCount - list.openCount) / list.totalCount) * 100)
                      : 0
                    return (
                      <UnstyledButton
                        key={list.id}
                        onClick={() => setSelectedId(list.id)}
                        style={{ width: '100%', textAlign: 'left' }}
                      >
                        <Card
                          withBorder
                          padding="sm"
                          style={{
                            borderColor: selectedId === list.id ? 'var(--mantine-color-blue-filled)' : undefined,
                            backgroundColor: selectedId === list.id ? 'var(--mantine-color-blue-light)' : undefined,
                          }}
                        >
                          <Stack gap={6}>
                            <Group justify="space-between" wrap="nowrap" gap="xs">
                              <Text fw={500} size="sm" lineClamp={1}>{list.title}</Text>
                              {list.pinned && <IconPin size={14} />}
                            </Group>
                            <Progress value={listProgress} size="sm" />
                            <Group gap="xs" wrap="wrap">
                              <Text size="xs" c="dimmed">{list.openCount} open</Text>
                              {list.overdueCount > 0 && (
                                <Badge size="xs" color="red">{list.overdueCount} overdue</Badge>
                              )}
                              {list.noteTitle && (
                                <Badge size="xs" variant="light" color="gray">{list.noteTitle}</Badge>
                              )}
                            </Group>
                          </Stack>
                        </Card>
                      </UnstyledButton>
                    )
                  })}
                </Stack>
              )}
            </Stack>
          </Card>
        )}

        {(!isMobile || selectedId) && (
          <Card withBorder padding="md" style={{ flex: 1, minWidth: 0 }}>
            {detailLoading ? (
              <Group justify="center" py="xl"><Loader /></Group>
            ) : detailError ? (
              <Alert color="red">{detailError}</Alert>
            ) : !detail ? (
              <Text c="dimmed">Select a TODO list, or create a new one.</Text>
            ) : (
              <Stack gap="md">
                <Group justify="space-between" align="center" wrap="wrap" gap="xs">
                  <Group gap="xs" style={{ flex: 1, minWidth: 0 }}>
                    {isMobile && (
                      <ActionIcon variant="subtle" onClick={() => setSelectedId(null)} aria-label="Back to lists">
                        <IconArrowLeft size={18} />
                      </ActionIcon>
                    )}
                    <TextInput
                      value={titleDraft}
                      onChange={(e) => setTitleDraft(e.currentTarget.value)}
                      onBlur={saveTitle}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') e.currentTarget.blur()
                        if (e.key === 'Escape') {
                          setTitleDraft(detail.title || '')
                          e.currentTarget.blur()
                        }
                      }}
                      variant="unstyled"
                      size="lg"
                      fw={600}
                      aria-label="TODO list title"
                      style={{ flex: 1, minWidth: 0 }}
                    />
                    <Tooltip label={detail.pinned ? 'Unpin list' : 'Pin list'}>
                      <ActionIcon
                        variant={detail.pinned ? 'filled' : 'subtle'}
                        onClick={togglePin}
                        aria-label={detail.pinned ? 'Unpin list' : 'Pin list'}
                      >
                        {detail.pinned ? <IconPin size={16} /> : <IconPinnedOff size={16} />}
                      </ActionIcon>
                    </Tooltip>
                  </Group>
                  <Group gap="xs">
                    {detail.noteId && (
                      <Button
                        size="xs"
                        variant="light"
                        leftSection={<IconExternalLink size={14} />}
                        onClick={openLinkedNote}
                      >
                        {detail.noteTitle || 'Open note'}
                      </Button>
                    )}
                    <Button
                      size="xs"
                      variant="light"
                      leftSection={<IconLink size={14} />}
                      onClick={() => { setLinkOpen(true); setNoteQuery('') }}
                    >
                      {detail.noteId ? 'Change note' : 'Link note'}
                    </Button>
                    <Tooltip label="Delete list">
                      <ActionIcon color="red" variant="subtle" onClick={deleteList} aria-label="Delete list">
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Tooltip>
                  </Group>
                </Group>

                <Group justify="space-between" gap="xs">
                  <Text size="sm" c="dimmed">{done} of {total} done</Text>
                  {selectedSummary?.overdueCount > 0 && (
                    <Badge color="red">{selectedSummary.overdueCount} overdue</Badge>
                  )}
                </Group>
                <Progress value={progress} size="md" />

                <Group gap="xs">
                  <Button size="xs" variant={filter === 'all' ? 'filled' : 'light'} onClick={() => setFilter('all')}>
                    All
                  </Button>
                  <Button size="xs" variant={filter === 'open' ? 'filled' : 'light'} onClick={() => setFilter('open')}>
                    Open
                  </Button>
                  <Button size="xs" variant={filter === 'done' ? 'filled' : 'light'} onClick={() => setFilter('done')}>
                    Done
                  </Button>
                </Group>

                <Group align="flex-end" gap="xs">
                  <TextInput
                    ref={newItemInputRef}
                    placeholder="Add an item..."
                    value={newItemText}
                    onChange={(e) => setNewItemText(e.currentTarget.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault()
                        addItem(null)
                      }
                    }}
                    style={{ flex: 1 }}
                  />
                  <Button
                    leftSection={<IconPlus size={14} />}
                    onClick={() => addItem(null)}
                    loading={addingItem}
                    disabled={!newItemText.trim()}
                  >
                    Add
                  </Button>
                </Group>

                {visibleItems.length === 0 ? (
                  <Text c="dimmed" size="sm">
                    {total === 0 ? 'No items yet. Add one above.' : 'No items match this filter.'}
                  </Text>
                ) : (
                  <Stack gap={4}>
                    {visibleItems.map((item) => {
                      const depth = depthOf(item)
                      const busy = busyItemId === item.id
                      return (
                        <Box key={item.id}>
                          <Group
                            wrap="nowrap"
                            gap="xs"
                            onDragOver={(e) => {
                              e.preventDefault()
                              setDragOverId(item.id)
                            }}
                            onDragLeave={() => setDragOverId((current) => (current === item.id ? null : current))}
                            onDrop={(e) => {
                              e.preventDefault()
                              const rect = e.currentTarget.getBoundingClientRect()
                              handleDrop(item, e.clientY > rect.top + rect.height / 2)
                            }}
                            style={{
                              paddingLeft: depth * 18,
                              paddingTop: 4,
                              paddingBottom: 4,
                              borderRadius: 6,
                              backgroundColor: dragOverId === item.id
                                ? 'var(--mantine-color-blue-light)'
                                : undefined,
                              opacity: busy ? 0.6 : 1,
                            }}
                          >
                            <Box
                              draggable
                              onDragStart={(e) => {
                                e.dataTransfer.effectAllowed = 'move'
                                setDraggedId(item.id)
                              }}
                              onDragEnd={() => {
                                setDraggedId(null)
                                setDragOverId(null)
                              }}
                              title="Drag to reorder"
                              style={{ cursor: 'grab', display: 'flex', alignItems: 'center' }}
                            >
                              <IconGripVertical size={16} />
                            </Box>
                            <Checkbox
                              checked={item.checked}
                              onChange={() => toggleItem(item)}
                              disabled={busy}
                              aria-label={item.checked ? `Mark "${item.text}" open` : `Mark "${item.text}" done`}
                            />
                            {editingItemId === item.id ? (
                              <TextInput
                                autoFocus
                                size="xs"
                                value={editingText}
                                onChange={(e) => setEditingText(e.currentTarget.value)}
                                onBlur={() => saveItemText(item)}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter') e.currentTarget.blur()
                                  if (e.key === 'Escape') setEditingItemId(null)
                                }}
                                style={{ flex: 1 }}
                              />
                            ) : (
                              <UnstyledButton
                                onClick={() => {
                                  setEditingItemId(item.id)
                                  setEditingText(item.text)
                                }}
                                style={{ flex: 1, minWidth: 0 }}
                              >
                                <Text
                                  size="sm"
                                  td={item.checked ? 'line-through' : undefined}
                                  c={item.checked ? 'dimmed' : undefined}
                                  style={{ overflowWrap: 'anywhere' }}
                                >
                                  {item.text}
                                </Text>
                              </UnstyledButton>
                            )}
                            {item.priority !== 'NONE' && (
                              <Badge size="xs" color={PRIORITY_COLORS[item.priority]}>
                                {PRIORITY_LABELS[item.priority]}
                              </Badge>
                            )}
                            {item.dueDate && (
                              <Badge size="xs" color={dueColor(item)}>{item.dueDate}</Badge>
                            )}
                            <Menu position="bottom-end" withArrow>
                              <Menu.Target>
                                <ActionIcon variant="subtle" size="sm" aria-label="Item actions">
                                  <IconDots size={16} />
                                </ActionIcon>
                              </Menu.Target>
                              <Menu.Dropdown>
                                <Menu.Label>Priority</Menu.Label>
                                {PRIORITIES.map((priority) => (
                                  <Menu.Item
                                    key={priority}
                                    onClick={() => savePriority(item, priority)}
                                    leftSection={item.priority === priority ? <IconCheck size={14} /> : null}
                                  >
                                    {PRIORITY_LABELS[priority]}
                                  </Menu.Item>
                                ))}
                                <Menu.Divider />
                                <Menu.Item
                                  leftSection={<IconSubtask size={14} />}
                                  onClick={() => {
                                    setSubItemParentId(item.id)
                                    setSubItemText('')
                                  }}
                                >
                                  Add sub-item
                                </Menu.Item>
                                <Menu.Item
                                  leftSection={<IconCalendar size={14} />}
                                  onClick={() => {
                                    setDueEditingId(item.id)
                                    setDueDraft(item.dueDate || '')
                                  }}
                                >
                                  Set due date
                                </Menu.Item>
                                <Menu.Divider />
                                <Menu.Item
                                  leftSection={<IconChevronUp size={14} />}
                                  disabled={!canMoveUp(item)}
                                  onClick={() => moveSibling(item, -1)}
                                >
                                  Move up
                                </Menu.Item>
                                <Menu.Item
                                  leftSection={<IconChevronDown size={14} />}
                                  disabled={!canMoveDown(item)}
                                  onClick={() => moveSibling(item, 1)}
                                >
                                  Move down
                                </Menu.Item>
                                <Menu.Divider />
                                <Menu.Item
                                  color="red"
                                  leftSection={<IconTrash size={14} />}
                                  onClick={() => deleteItem(item)}
                                >
                                  Delete
                                </Menu.Item>
                              </Menu.Dropdown>
                            </Menu>
                          </Group>

                          {subItemParentId === item.id && (
                            <Group gap="xs" style={{ paddingLeft: (depth + 1) * 18 + 24, paddingTop: 4 }}>
                              <TextInput
                                autoFocus
                                size="xs"
                                placeholder="Sub-item..."
                                value={subItemText}
                                onChange={(e) => setSubItemText(e.currentTarget.value)}
                                onKeyDown={(e) => {
                                  if (e.key === 'Enter') {
                                    e.preventDefault()
                                    addItem(item.id)
                                  }
                                  if (e.key === 'Escape') setSubItemParentId(null)
                                }}
                                style={{ flex: 1 }}
                              />
                              <Button size="xs" onClick={() => addItem(item.id)} loading={addingItem}>
                                Add
                              </Button>
                              <ActionIcon size="sm" variant="subtle" onClick={() => setSubItemParentId(null)} aria-label="Cancel">
                                <IconX size={14} />
                              </ActionIcon>
                            </Group>
                          )}

                          {dueEditingId === item.id && (
                            <Group gap="xs" style={{ paddingLeft: (depth + 1) * 18 + 24, paddingTop: 4 }}>
                              <TextInput
                                autoFocus
                                size="xs"
                                type="date"
                                value={dueDraft}
                                onChange={(e) => setDueDraft(e.currentTarget.value)}
                              />
                              <Button size="xs" onClick={() => saveDueDate(item, dueDraft)}>Save</Button>
                              <Button size="xs" variant="subtle" onClick={() => saveDueDate(item, '')}>Clear</Button>
                              <ActionIcon size="sm" variant="subtle" onClick={() => setDueEditingId(null)} aria-label="Cancel">
                                <IconX size={14} />
                              </ActionIcon>
                            </Group>
                          )}
                        </Box>
                      )
                    })}
                  </Stack>
                )}

                {detail.noteId && (
                  <Group gap="xs">
                    <IconNote size={14} />
                    <Text size="xs" c="dimmed">
                      Linked to note: {detail.noteTitle || 'Untitled'}
                    </Text>
                  </Group>
                )}
              </Stack>
            )}
          </Card>
        )}
      </Group>

      <Modal opened={createOpen} onClose={() => setCreateOpen(false)} title="New TODO list" centered>
        <form onSubmit={createList}>
          <Stack>
            <TextInput
              label="Title"
              placeholder="Groceries, Project plan, ..."
              required
              value={newListTitle}
              onChange={(e) => setNewListTitle(e.currentTarget.value)}
              maxLength={255}
              data-autofocus
            />
            <Group justify="flex-end">
              <Button variant="subtle" type="button" onClick={() => setCreateOpen(false)}>Cancel</Button>
              <Button type="submit" loading={creatingList}>Create</Button>
            </Group>
          </Stack>
        </form>
      </Modal>

      <Modal opened={linkOpen} onClose={() => setLinkOpen(false)} title="Link a note" centered size="lg">
        <Stack>
          <TextInput
            placeholder="Search notes..."
            value={noteQuery}
            onChange={(e) => setNoteQuery(e.currentTarget.value)}
          />
          {noteLoading ? (
            <Group justify="center" py="md"><Loader size="sm" /></Group>
          ) : noteResults.length === 0 ? (
            <Text size="sm" c="dimmed">No notes found.</Text>
          ) : (
            <Stack gap={4} style={{ maxHeight: 360, overflowY: 'auto' }}>
              {noteResults.map((note) => (
                <UnstyledButton
                  key={note.id}
                  onClick={() => linkNote(note)}
                  style={{ padding: 8, borderRadius: 6 }}
                >
                  <Text size="sm" fw={500} lineClamp={1}>{note.title || 'Untitled'}</Text>
                  {note.tags && <Text size="xs" c="dimmed" lineClamp={1}>{note.tags}</Text>}
                </UnstyledButton>
              ))}
            </Stack>
          )}
          {detail?.noteId && (
            <Group justify="flex-end">
              <Button color="red" variant="subtle" onClick={unlinkNote}>Unlink current note</Button>
            </Group>
          )}
        </Stack>
      </Modal>
    </Stack>
  )
}
