import {
  Button,
  Card,
  Group,
  MultiSelect,
  Stack,
  Text,
} from '@mantine/core'

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
  return (
    <MultiSelect
      label="Tags"
      placeholder="Search tags"
      data={(tags || []).concat((value || []).filter((t) => !(tags || []).includes(t)))}
      value={value || []}
      onChange={onChange}
      searchable
      clearable
      nothingFoundMessage="No tags"
    />
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
}) {
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
      </Stack>
    </Card>
  )
}
