// Local draft journal.
//
// Notes can be large (up to ~10 MB), so IndexedDB is the primary store.
// `sessionStorage` is a best-effort fallback for browsers or private modes where
// IndexedDB is unavailable; it survives a reload but not closing the tab.
//
// Drafts are keyed by user + note, or by a per-editor draft id for a note that
// has not been created on the server yet. They are deleted after a successful
// save, on explicit sign-out, and after DRAFT_MAX_AGE_MS.

const DB_NAME = 'notearray-drafts'
const DB_VERSION = 1
const STORE = 'drafts'
const FALLBACK_PREFIX = 'notearray.draft.'

export const DRAFT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000

let dbPromise = null

function hasIndexedDb() {
  try {
    return typeof indexedDB !== 'undefined' && indexedDB !== null
  } catch (e) {
    return false
  }
}

function openDb() {
  if (!hasIndexedDb()) return Promise.resolve(null)
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve) => {
    let request
    try {
      request = indexedDB.open(DB_NAME, DB_VERSION)
    } catch (e) {
      resolve(null)
      return
    }
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE)) {
        const store = db.createObjectStore(STORE, { keyPath: 'key' })
        store.createIndex('userId', 'userId', { unique: false })
        store.createIndex('updatedAt', 'updatedAt', { unique: false })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => resolve(null)
    request.onblocked = () => resolve(null)
  })
  return dbPromise
}

/**
 * Run one transaction and resolve with the request's result on commit.
 * `commitNow` starts the commit immediately, which matters when this runs from
 * a `pagehide`/`visibilitychange` handler and the page may be discarded.
 */
function run(db, mode, action, commitNow = false) {
  return new Promise((resolve, reject) => {
    let transaction
    try {
      transaction = db.transaction(STORE, mode)
    } catch (e) {
      reject(e)
      return
    }
    const store = transaction.objectStore(STORE)
    let request
    try {
      request = action(store)
    } catch (e) {
      reject(e)
      return
    }
    if (commitNow && mode === 'readwrite' && typeof transaction.commit === 'function') {
      try {
        transaction.commit()
      } catch (e) {
        // Older browsers without IDBTransaction.commit() auto-commit normally.
      }
    }
    transaction.oncomplete = () => resolve(request?.result)
    transaction.onerror = () => reject(transaction.error || new Error('Draft transaction failed'))
    transaction.onabort = () => reject(transaction.error || new Error('Draft transaction aborted'))
  })
}

function fallbackKey(key) {
  return FALLBACK_PREFIX + key
}

function fallbackPut(record) {
  try {
    sessionStorage.setItem(fallbackKey(record.key), JSON.stringify(record))
  } catch (e) {
    // storage full or unavailable — the in-memory editor state is still intact
  }
}

function fallbackGet(key) {
  try {
    const raw = sessionStorage.getItem(fallbackKey(key))
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

function fallbackDelete(key) {
  try {
    sessionStorage.removeItem(fallbackKey(key))
  } catch (e) {
    // ignore
  }
}

function fallbackList() {
  const records = []
  try {
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const key = sessionStorage.key(i)
      if (!key || !key.startsWith(FALLBACK_PREFIX)) continue
      try {
        const record = JSON.parse(sessionStorage.getItem(key))
        if (record?.key) records.push(record)
      } catch (e) {
        // skip malformed entries
      }
    }
  } catch (e) {
    // storage unavailable
  }
  return records
}

/** Stable id for a note that has not been created yet. */
export function newDraftId() {
  try {
    if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  } catch (e) {
    // fall through
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/** Key for one user's draft of an existing note, or of an unsaved new note. */
export function draftKey(userId, noteId, draftId) {
  if (!userId) return null
  return noteId ? `${userId}:note:${noteId}` : `${userId}:new:${draftId || 'unknown'}`
}

export async function putDraft(draft) {
  if (!draft?.key) return null
  const record = { ...draft, updatedAt: draft.updatedAt || Date.now() }
  const db = await openDb()
  if (!db) {
    fallbackPut(record)
    return record
  }
  try {
    await run(db, 'readwrite', (store) => store.put(record), true)
  } catch (e) {
    fallbackPut(record)
  }
  return record
}

export async function getDraft(key) {
  if (!key) return null
  const db = await openDb()
  if (!db) return fallbackGet(key)
  try {
    return (await run(db, 'readonly', (store) => store.get(key))) || null
  } catch (e) {
    return fallbackGet(key)
  }
}

export async function deleteDraft(key) {
  if (!key) return
  const db = await openDb()
  if (!db) {
    fallbackDelete(key)
    return
  }
  try {
    await run(db, 'readwrite', (store) => store.delete(key), true)
  } catch (e) {
    fallbackDelete(key)
  }
}

/** Most recently edited drafts for a user, newest first. */
export async function listDrafts(userId) {
  if (!userId) return []
  const db = await openDb()
  let records
  if (!db) {
    records = fallbackList()
  } else {
    try {
      records = await run(db, 'readonly', (store) => store.index('userId').getAll(userId))
    } catch (e) {
      records = fallbackList()
    }
  }
  const cutoff = Date.now() - DRAFT_MAX_AGE_MS
  return (records || [])
    .filter((record) => record && record.updatedAt >= cutoff)
    .sort((a, b) => b.updatedAt - a.updatedAt)
}

/** Remove every draft belonging to a user (explicit sign-out / account switch). */
export async function clearUserDrafts(userId) {
  if (!userId) return
  const db = await openDb()
  if (!db) {
    fallbackList()
      .filter((record) => record.userId === userId)
      .forEach((record) => fallbackDelete(record.key))
    return
  }
  try {
    await run(db, 'readwrite', (store) => {
      const index = store.index('userId')
      const request = index.openKeyCursor(IDBKeyRange.only(userId))
      request.onsuccess = () => {
        const cursor = request.result
        if (!cursor) return
        store.delete(cursor.primaryKey)
        cursor.continue()
      }
      return request
    })
  } catch (e) {
    // best effort
  }
}

/** Drop drafts older than the retention window. */
export async function purgeExpired(maxAgeMs = DRAFT_MAX_AGE_MS) {
  const cutoff = Date.now() - maxAgeMs
  const db = await openDb()
  if (!db) {
    fallbackList()
      .filter((record) => record.updatedAt < cutoff)
      .forEach((record) => fallbackDelete(record.key))
    return
  }
  try {
    const records = await run(db, 'readonly', (store) => store.getAll())
    const expired = (records || [])
      .filter((record) => record && record.updatedAt < cutoff)
      .map((record) => record.key)
    if (expired.length === 0) return
    await run(db, 'readwrite', (store) => {
      expired.forEach((key) => store.delete(key))
    })
  } catch (e) {
    // best effort
  }
}
