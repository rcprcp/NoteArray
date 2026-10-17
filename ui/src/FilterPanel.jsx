import { useState } from 'react'
import {
  ActionIcon,
  Button,
  Card,
  Group,
  MultiSelect,
  Stack,
  Text,
  TextInput,
  Tooltip,
} from '@mantine/core'
import { IconTrash } from '@tabler/icons-react'

export const DEFAULT_FILTERS = {
  tags: [],
  collection: null,
}

export function buildApiFilters(f) {
  const out = { tags: f.tags || [] }
  if (f.collection === 'untagged') out.untagged = true
  if (f.collection === 'pinned') out.pinned = true
  return out
}

export function describeFilters(f) {
  const items = []
  ;(f.tags || []).forEach((t) => items.push({ key: `tag:${t}`, label: `Tag: ${t}`, clear: { tags: (f.tags || []).filter((x) => x !== t) } }))
  if (f.collection === 'pinned') items.push({ key: 'collection', label: 'Pinned', clear: { collection: null } })
  if (f.collection === 'untagged') items.push({ key: 'collection', label: 'Untagged', clear: { collection: null } })
  return items
}

export function TagMultiSelect({ tags, value, onChange }) {
  const [input, setInput] = useState('')
  const add = () => {
    const v = input.trim().toLowerCase()
    if (!v) return
    const parts = v.split(/\s+/).filter(Boolean)
    const next = [...(value || [])]
    parts.forEach((p) => {
      if (!next.includes(p)) next.push(p)
    })
    onChange(next)
    setInput('')
  }
  return (
    <Stack gap="xs">
      <Text fw={600} size="sm">Tags</Text>
      <Group gap="xs" wrap="nowrap">
        <TextInput
          style={{ flex: 1 }}
          placeholder="Enter tag(s)"
          value={input}
          onChange={(e) => setInput(e.currentTarget.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add() } }}
        />
        <Button onClick={add}>Add</Button>
      </Group>
      <MultiSelect
        data={(tags || []).concat((value || []).filter((t) => !(tags || []).includes(t)))}
        value={value || []}
        onChange={onChange}
        searchable
        clearable
        nothingFoundMessage="No tags"
      />
    </Stack>
  )
}

const COLLECTIONS = [
  { key: 'pinned', label: 'Pinned' },
  { key: 'untagged', label: 'Untagged' },
]

export default function FilterPanel({
  filters,
  onChange,
  allTags,
  collectionCounts,
  saved,
  canSave,
  onSaveSearch,
  onApplySaved,
  onDeleteSaved,
}) {
  const [searchName, setSearchName] = useState('')

  const save = () => {
    const name = searchName.trim()
    if (!name || !canSave) return
    onSaveSearch(name)
    setSearchName('')
  }

  return (
    <Card withBorder padding="md" radius="md">
      <Stack gap="md">
        <TagMultiSelect tags={allTags} value={filters.tags || []} onChange={(tags) => onChange({ tags })} />

        <Stack gap="xs">
          <Text fw={600} size="sm">Collections</Text>
          <Group gap="xs">
            {COLLECTIONS.map((c) => {
              const active = filters.collection === c.key
              const count = collectionCounts ? collectionCounts[c.key] : null
              return (
                <Button
                  key={c.key}
                  variant={active ? 'filled' : 'light'}
                  size="xs"
                  onClick={() => onChange({ collection: active ? null : c.key })}
                  rightSection={count != null ? (
                    <Text size="xs" c={active ? 'white' : 'dimmed'}>{count}</Text>
                  ) : null}
                >
                  {c.label}
                </Button>
              )
            })}
          </Group>
        </Stack>

        <Stack gap="xs">
          <Text fw={600} size="sm">Saved searches</Text>
          {(saved || []).length === 0 ? (
            <Text size="xs" c="dimmed">No saved searches yet.</Text>
          ) : (
            <Stack gap={4}>
              {(saved || []).map((s) => (
                <Group key={s.id} gap="xs" wrap="nowrap">
                  <Button
                    variant="subtle"
                    size="xs"
                    fullWidth
                    justify="flex-start"
                    styles={{ label: { overflow: 'hidden', textOverflow: 'ellipsis' } }}
                    onClick={() => onApplySaved(s)}
                  >
                    {s.name}
                  </Button>
                  <Tooltip label="Delete saved search">
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      size="sm"
                      aria-label={`Delete saved search ${s.name}`}
                      onClick={() => onDeleteSaved(s.id)}
                    >
                      <IconTrash size={14} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
              ))}
            </Stack>
          )}
          <Group gap="xs" wrap="nowrap">
            <TextInput
              style={{ flex: 1 }}
              size="xs"
              placeholder="Name current search"
              value={searchName}
              onChange={(e) => setSearchName(e.currentTarget.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); save() } }}
            />
            <Button size="xs" disabled={!canSave || !searchName.trim()} onClick={save}>
              Save search
            </Button>
          </Group>
        </Stack>
      </Stack>
    </Card>
  )
}
