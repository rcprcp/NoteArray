/**
 * Search-panel filters: persisted state, API serialization and the
 * "active filter" chips. Tag filtering itself is done through the text
 * search's "Search Tags" scope, so there is no tag picker any more.
 */
export const DEFAULT_FILTERS = {
  collection: null,
  untagged: false,
  hasAttachments: false,
}

/** Keep only known keys, so stale stored filters (e.g. old tag picks) are dropped. */
export function sanitizeFilters(stored) {
  const known = {}
  for (const key of Object.keys(DEFAULT_FILTERS)) {
    if (stored && key in stored) known[key] = stored[key]
  }
  return { ...DEFAULT_FILTERS, ...known }
}

export function buildApiFilters(f) {
  const out = {}
  if (f.untagged || f.collection === 'untagged') out.untagged = true
  if (f.hasAttachments) out.hasAttachments = true
  if (f.collection === 'pinned') out.pinned = true
  return out
}

export function describeFilters(f) {
  const items = []
  if (f.collection === 'pinned') {
    items.push({ key: 'collection', label: 'Pinned', clear: { collection: null } })
  }
  if (f.hasAttachments) {
    items.push({
      key: 'attachments',
      label: 'With attachments',
      clear: { hasAttachments: false },
    })
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
