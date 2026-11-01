// Small fetch wrapper for the Quarkus REST API.
// Credentials (cookies) are included automatically.
import { requestReauth } from './sessionExpiry'

// Endpoints whose 401 means "these credentials are wrong", not "your session
// expired". They must never open the re-authentication prompt.
const AUTH_PATHS = [/^\/api\/users\/login(\/|$)/, /^\/api\/users\/logout$/, /^\/api\/users$/]

function isAuthPath(path) {
  return AUTH_PATHS.some((pattern) => pattern.test(path))
}

async function request(path, options = {}, retried = false) {
  const headers = { ...(options.headers || {}) }
  // Default to JSON for string bodies unless the caller set a Content-Type.
  // Binary/FormData bodies keep their explicit header (or none, to let the browser set it).
  if (!headers['Content-Type'] && typeof options.body === 'string') {
    headers['Content-Type'] = 'application/json'
  }
  const res = await fetch(path, {
    ...options,
    headers,
    credentials: 'include',
  })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    let code = null
    try {
      const text = await res.text()
      if (text) {
        try {
          const parsed = JSON.parse(text)
          if (parsed && typeof parsed === 'object') {
            code = typeof parsed.code === 'string' ? parsed.code : null
            message = parsed.message || parsed.error || (code === 'session_expired' ? 'Session expired' : code) || text
          } else {
            message = text
          }
        } catch (e) {
          // Plain-text error (the login endpoints use this).
          message = text
        }
      }
    } catch (e) {
      // ignore body read errors
    }

    // A 401 on a protected endpoint means the session cookie is no longer
    // valid. Keep the SPA mounted, ask for credentials, then replay the
    // request once with the new cookie. Login/logout endpoints are excluded.
    if (res.status === 401 && !retried && !isAuthPath(path) && options.retryOnReauth !== false) {
      const ok = await requestReauth()
      if (ok) return request(path, options, true)
    }

    const err = new Error(message)
    err.status = res.status
    err.code = code
    throw err
  }
  if (res.status === 204) return null
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

/** Raw fetch for blob downloads that still gets the re-auth + retry behavior. */
async function fetchWithReauth(url, options = {}, retried = false) {
  const res = await fetch(url, { ...options, credentials: 'include' })
  if (res.status === 401 && !retried) {
    const ok = await requestReauth()
    if (ok) return fetchWithReauth(url, options, true)
  }
  return res
}

export const billingApi = {
  config: () => request('/api/billing/config'),
}

export const usersApi = {
  getCurrentUser: (options) => request('/api/users/me', options),
  login: (email, password) => request('/api/users/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  loginTotp: (challenge, code, recoveryCode) => request('/api/users/login/totp', {
    method: 'POST',
    body: JSON.stringify({ challenge, code: code || null, recoveryCode: recoveryCode || null }),
  }),
  totpStatus: () => request('/api/users/me/totp'),
  totpSetup: (currentPassword) => request('/api/users/me/totp/setup', {
    method: 'POST',
    body: JSON.stringify({ currentPassword }),
  }),
  totpEnable: (code) => request('/api/users/me/totp/enable', {
    method: 'POST',
    body: JSON.stringify({ code }),
  }),
  totpDisable: (currentPassword, code, recoveryCode) => request('/api/users/me/totp/disable', {
    method: 'POST',
    body: JSON.stringify({ currentPassword, code: code || null, recoveryCode: recoveryCode || null }),
  }),
  regenerateRecoveryCodes: (code, recoveryCode) => request('/api/users/me/totp/recovery-codes', {
    method: 'POST',
    body: JSON.stringify({ code: code || null, recoveryCode: recoveryCode || null }),
  }),
  logout: () => request('/api/users/logout', { method: 'POST' }),
  create: (user) => request('/api/users', { method: 'POST', body: JSON.stringify(user) }),
  update: (id, user) => request(`/api/users/${id}`, { method: 'PUT', body: JSON.stringify(user) }),
  checkoutPlan: (id, plan) => request(`/api/users/${id}/plan/checkout`, { method: 'POST', body: JSON.stringify({ plan }) }),
  syncPlan: (id, payload) => request(`/api/users/${id}/plan/sync`, { method: 'POST', body: JSON.stringify(payload) }),
  remove: (id, currentPassword, totpCode, recoveryCode) => request(`/api/users/${id}`, {
    method: 'DELETE',
    body: JSON.stringify({ currentPassword, totpCode: totpCode || null, recoveryCode: recoveryCode || null }),
  }),
  list: () => request('/api/users'),
}

function buildNoteListParams(search, filters) {
  const params = new URLSearchParams()
  const q = search && search.q ? search.q.trim() : ''
  if (q) {
    params.set('q', q)
    params.set('searchTitles', String(!!search.searchTitles))
    params.set('searchContent', String(!!search.searchContent))
    params.set('searchTags', String(!!search.searchTags))
  }
  if (filters) {
    ;(filters.tags || []).forEach((t) => params.append('tags', t))
    ;(filters.tagPrefixes || []).forEach((t) => params.append('tagPrefix', t))
    if (filters.untagged) params.set('untagged', 'true')
    if (filters.hasAttachments) params.set('hasAttachments', 'true')
    if (filters.pinned) params.set('pinned', 'true')
    for (const key of ['createdFrom', 'createdTo', 'updatedFrom', 'updatedTo']) {
      if (filters[key]) params.set(key, filters[key])
    }
  }
  return params
}

export const notesApi = {
  // Optional search: { q, searchTitles, searchContent }
  // Optional filters: { tags: [], untagged, pinned, createdFrom, createdTo, updatedFrom, updatedTo }
  list: (search, filters) => {
    const params = buildNoteListParams(search, filters)
    const qs = params.toString()
    return request(qs ? `/api/notes?${qs}` : '/api/notes')
  },
  /**
   * One page of notes for infinite scroll. Returns
   * `{ items, nextCursor, hasMore }`.
   */
  listPage: (search, filters, options = {}) => {
    const params = buildNoteListParams(search, filters)
    params.set('limit', String(options.limit ?? 50))
    if (options.cursor) params.set('cursor', options.cursor)
    params.set('sort', options.sort || 'createdAt')
    params.set('order', options.order || 'desc')
    return request(`/api/notes/page?${params.toString()}`, { signal: options.signal })
  },
  get: (id) => request(`/api/notes/${id}`),
  storage: () => request('/api/notes/storage'),
  create: (note) => request('/api/notes', { method: 'POST', body: JSON.stringify(note) }),
  update: (id, note) => request(`/api/notes/${id}`, { method: 'PUT', body: JSON.stringify(note) }),
  setEditorHeight: (id, height) => request(`/api/notes/${id}/editor-height`, { method: 'PUT', body: JSON.stringify({ height }) }),
  remove: (id) => request(`/api/notes/${id}`, { method: 'DELETE' }),
  importJoplin: (file) => {
    return request('/api/notes/import/joplin', {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: file,
      credentials: 'include',
    })
  },
  importMarkdown: (file) => {
    return request('/api/notes/import/markdown', {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: file,
      credentials: 'include',
    })
  },
  uploadAttachment: (noteId, file) => {
    const qs = new URLSearchParams({ filename: file.name || 'attachment' }).toString()
    return request(`/api/notes/${noteId}/attachments?${qs}`, {
      method: 'POST',
      headers: { 'Content-Type': file.type || 'application/octet-stream' },
      body: file,
      credentials: 'include',
    })
  },
  // Attachments this note links to (metadata only; bytes come from /api/attachments/{id}).
  listAttachments: (noteId) => request(`/api/notes/${noteId}/attachments`),
  /**
   * Download a ZIP of every note (one JSON file each) plus every attachment
   * owned by the signed-in user. The archive is built server-side and saved via
   * a Blob, like attachment downloads. The browser's IANA time zone is sent so
   * the filename uses the user's local time.
   */
  exportAll: async () => {
    const params = new URLSearchParams()
    try {
      const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone
      if (timezone) params.set('timezone', timezone)
    } catch (e) {
      // Intl unavailable — the server falls back to UTC
    }
    const qs = params.toString()
    const res = await fetchWithReauth(qs ? `/api/notes/export?${qs}` : '/api/notes/export')
    if (!res.ok) {
      let message = `${res.status} ${res.statusText}`
      try {
        const text = await res.text()
        if (text) message = text
      } catch (e) {
        // ignore body read errors
      }
      const err = new Error(message)
      err.status = res.status
      throw err
    }
    const blob = await res.blob()
    let filename = 'notearray-export.zip'
    try {
      const cd = res.headers.get('Content-Disposition') || ''
      const m = cd.match(/filename="?([^";]+)"?/i)
      if (m && m[1].trim()) filename = m[1].trim()
    } catch (e) {
      // header not accessible — keep the fallback name
    }
    filename = filename.replace(/[^A-Za-z0-9._ -]/g, '_').trim() || 'notearray-export.zip'
    const objectUrl = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = objectUrl
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(objectUrl)
  },
  /**
   * Delete an attachment. `fromNote` names the note the caller is removing the
   * link from, so its own reference does not block the delete; a 409 means
   * another note still links the file.
   */
  deleteAttachment: (attachmentId, fromNote) => {
    const qs = fromNote ? `?fromNote=${fromNote}` : ''
    return request(`/api/attachments/${attachmentId}${qs}`, { method: 'DELETE' })
  },
  /**
   * Download an attachment as a file, never a viewer navigation.
   * Fetches the bytes as a Blob and triggers a save via an anchor with the
   * `download` attribute — behavior identical across browsers, regardless of
   * the server's Content-Disposition or the browser's PDF viewer settings.
   */
  download: async (url, fallbackName) => {
    // Force download mode on the server so the body arrives as
    // application/octet-stream (see AttachmentResource.download): Firefox's
    // built-in PDF handling otherwise saves AND re-opens even blob downloads
    // typed application/pdf (Bugzilla 1756980).
    const fetchUrl = url.includes('?') ? `${url}&download=1` : `${url}?download=1`
    const res = await fetchWithReauth(fetchUrl)
    if (!res.ok) {
      let message = `${res.status} ${res.statusText}`
      try {
        const text = await res.text()
        if (text) message = text
      } catch (e) {
        // ignore body read errors
      }
      const err = new Error(message)
      err.status = res.status
      throw err
    }
    // Client-side backstop: mark the Blob octet-stream even if a proxy or old
    // server rewrote the content type, so no browser routes it to a PDF viewer.
    const buffer = await res.arrayBuffer()
    const blob = new Blob([buffer], { type: 'application/octet-stream' })
    // Prefer the filename from Content-Disposition, else the caller's label.
    let filename = typeof fallbackName === 'string' && fallbackName.trim() ? fallbackName.trim() : 'attachment'
    try {
      const cd = res.headers.get('Content-Disposition') || ''
      const m = cd.match(/filename="?([^";]+)"?/i)
      if (m && m[1].trim()) filename = m[1].trim()
    } catch (e) {
      // header not accessible — keep the fallback name
    }
    // Never let server-controlled text smuggle quotes/newlines into the DOM attribute.
    filename = filename.replace(/[^A-Za-z0-9._ -]/g, '_').trim() || 'attachment'
    const objectUrl = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = objectUrl
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(objectUrl)
  },
}
