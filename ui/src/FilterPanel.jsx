import {
  Card,
  Checkbox,
  MultiSelect,
  Stack,
} from '@mantine/core'

export const DEFAULT_FILTERS = {
  tags: [],
  collection: null,
  untagged: false,
}

export function buildApiFilters(f) {
  const out = { tags: f.tags || [] }
  if (f.untagged || f.collection === 'untagged') out.untagged = true
  if (f.collection === 'pinned') out.pinned = true
  return out
}

export function describeFilters(f) {
  const items = []
  ;(f.tags || []).forEach((t) =>
    items.push({
      key: `tag:${t}`,
      label: `Tag: ${t}`,
      clear: { tags: (f.tags || []).filter((x) => x !== t) },
    }),
  )
  if (f.collection === 'pinned') {
    items.push({ key: 'collection', label: 'Pinned', clear: { collection: null } })
  }
  if (f.untagged || f.collection === 'untagged') {
    items.push({
      key: 'untagged',
      label: 'Untagged',
      clear: {
        untagged: false,
        ...(f.collection === 'untagged' ? { collection: null } : {}),
      },
    })
  }
  return items
}

export function TagMultiSelect({ tags, value, onChange, onSearchChange }) {
  return (
    <MultiSelect
      label="Tags"
      placeholder="Search tags"
      data={(tags || []).concat((value || []).filter((t) => !(tags || []).includes(t)))}
      value={value || []}
      onChange={onChange}
      onSearchChange={onSearchChange}
      searchable
      clearable
      nothingFoundMessage="No tags"
    />
  )
}

export default function FilterPanel({
  filters,
  onChange,
  allTags,
}) {
  const untaggedActive = !!filters.untagged || filters.collection === 'untagged'

  const handleUntaggedChange = (checked) => {
    onChange({
      untagged: checked,
      ...(checked ? { tags: [] } : {}),
      ...(filters.collection === 'untagged' ? { collection: null } : {}),
    })
  }

  const handleTagsChange = (tags) => {
    onChange({
      tags,
      ...(tags.length > 0 && untaggedActive
        ? {
            untagged: false,
            ...(filters.collection === 'untagged' ? { collection: null } : {}),
          }
        : {}),
    })
  }

  const handleTagSearchInput = (query) => {
    if (query && query.trim().length > 0 && untaggedActive) {
      onChange({
        untagged: false,
        ...(filters.collection === 'untagged' ? { collection: null } : {}),
      })
    }
  }

  return (
    <Card withBorder padding="md" radius="md">
      <Stack gap="md">
        <Checkbox
          label="Find notes with no tags"
          checked={untaggedActive}
          onChange={(e) => handleUntaggedChange(e.currentTarget.checked)}
        />

        <TagMultiSelect
          tags={allTags}
          value={filters.tags || []}
          onChange={handleTagsChange}
          onSearchChange={handleTagSearchInput}
        />
      </Stack>
    </Card>
  )
}
