import { useMemo } from 'react'
import {
  Card,
  Checkbox,
  MultiSelect,
  Stack,
} from '@mantine/core'

export const DEFAULT_FILTERS = {
  tags: [],
  tagPrefixes: [],
  collection: null,
  untagged: false,
}

// Value prefix used inside the MultiSelect to mark folder (hierarchical) options.
export const PREFIX_OPTION = 'prefix:'

/**
 * Every selectable ancestor of hierarchical tags: for the tag
 * `work/projects/acme` the selectable prefixes are `work`, `work/projects`
 * and `work/projects/acme` itself. Plain (non-hierarchical) tags add nothing.
 */
export function collectPrefixes(allTags) {
  const prefixes = new Set()
  for (const tag of allTags || []) {
    const parts = String(tag).split('/')
    if (parts.length < 2) continue
    for (let i = 1; i <= parts.length; i++) {
      prefixes.add(parts.slice(0, i).join('/'))
    }
  }
  return [...prefixes]
}

export function buildApiFilters(f) {
  const out = { tags: f.tags || [] }
  const prefixes = (f.tagPrefixes || []).filter((p) => p && typeof p === 'string')
  if (prefixes.length > 0) out.tagPrefixes = prefixes
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
  ;(f.tagPrefixes || []).forEach((p) =>
    items.push({
      key: `tagPrefix:${p}`,
      label: `Folder: ${p}`,
      clear: { tagPrefixes: (f.tagPrefixes || []).filter((x) => x !== p) },
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

export function TagMultiSelect({
  tags,
  value,
  onChange,
  onSearchChange,
  prefixes = [],
  prefixValue = [],
}) {
  const data = [
    {
      group: 'Tags',
      items: (tags || []).map((t) => ({ value: t, label: t })),
    },
  ]
  if ((prefixes || []).length > 0) {
    data.push({
      group: 'Folders (include sub-folders)',
      items: prefixes.map((p) => ({ value: `${PREFIX_OPTION}${p}`, label: p })),
    })
  }
  // A stored selection that no longer appears in the option list (tags were
  // re-tagged or removed) must stay selectable or Mantine drops the chip.
  const known = new Set(data.flatMap((g) => g.items.map((it) => it.value)))
  const missing = [...(value || []), ...(prefixValue || [])
    .map((p) => `${PREFIX_OPTION}${p}`)]
    .filter((v) => v && !known.has(v))
  if (missing.length > 0) {
    data.push({ group: 'Other selections', items: missing.map((v) => ({ value: v, label: v.replace(PREFIX_OPTION, '') })) })
  }
  return (
    <MultiSelect
      label="Tags"
      placeholder="Search tags"
      data={data}
      value={[...(value || []), ...(prefixValue || []).map((p) => `${PREFIX_OPTION}${p}`)]}
      onChange={(selected) => {
        onChange({
          tags: selected.filter((v) => !v.startsWith(PREFIX_OPTION)),
          tagPrefixes: selected
            .filter((v) => v.startsWith(PREFIX_OPTION))
            .map((v) => v.slice(PREFIX_OPTION.length)),
        })
      }}
      searchable
      clearable
      nothingFoundMessage="No tags"
      onSearchChange={onSearchChange}
    />
  )
}

export default function FilterPanel({
  filters,
  onChange,
  allTags,
}) {
  const untaggedActive = !!filters.untagged || filters.collection === 'untagged'
  const prefixes = useMemo(() => collectPrefixes(allTags), [allTags])

  const handleUntaggedChange = (checked) => {
    onChange({
      untagged: checked,
      ...(checked ? { tags: [], tagPrefixes: [] } : {}),
      ...(filters.collection === 'untagged' ? { collection: null } : {}),
    })
  }

  const handleSelectionChange = ({ tags, tagPrefixes }) => {
    onChange({
      tags,
      tagPrefixes,
      ...((tags.length > 0 || tagPrefixes.length > 0) && untaggedActive
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
          prefixes={prefixes}
          prefixValue={filters.tagPrefixes || []}
          onChange={handleSelectionChange}
          onSearchChange={handleTagSearchInput}
        />
      </Stack>
    </Card>
  )
}
