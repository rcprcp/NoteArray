// Small fetch wrapper for the Quarkus REST API.
// Credentials (cookies) are included automatically.
async function request(path, options = {}) {
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
  if (res.status === 204) return null
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

export const usersApi = {
  getCurrentUser: () => request('/api/users/me'),
  login: (email, password) => request('/api/users/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  logout: () => request('/api/users/logout', { method: 'POST' }),
  create: (user) => request('/api/users', { method: 'POST', body: JSON.stringify(user) }),
  update: (id, user) => request(`/api/users/${id}`, { method: 'PUT', body: JSON.stringify(user) }),
  checkoutPlan: (id, plan) => request(`/api/users/${id}/plan/checkout`, { method: 'POST', body: JSON.stringify({ plan }) }),
  syncPlan: (id, payload) => request(`/api/users/${id}/plan/sync`, { method: 'POST', body: JSON.stringify(payload) }),
  remove: (id, currentPassword) => request(`/api/users/${id}`, { method: 'DELETE', body: JSON.stringify({ currentPassword }) }),
  list: () => request('/api/users'),
}

export const notesApi = {
  // Optional search: { q, searchTitles, searchContent }
  // Optional filters: { tags: [], untagged, pinned, createdFrom, createdTo, updatedFrom, updatedTo }
  list: (search, filters) => {
    const params = new URLSearchParams()
    const q = search && search.q ? search.q.trim() : ''
    if (q) {
      params.set('q', q)
      params.set('searchTitles', String(!!search.searchTitles))
      params.set('searchContent', String(!!search.searchContent))
    }
    if (filters) {
      ;(filters.tags || []).forEach((t) => params.append('tags', t))
      if (filters.untagged) params.set('untagged', 'true')
      if (filters.pinned) params.set('pinned', 'true')
      for (const key of ['createdFrom', 'createdTo', 'updatedFrom', 'updatedTo']) {
        if (filters[key]) params.set(key, filters[key])
      }
    }
    const qs = params.toString()
    return request(qs ? `/api/notes?${qs}` : '/api/notes')
  },
  get: (id) => request(`/api/notes/${id}`),
  storage: () => request('/api/notes/storage'),
  create: (note) => request('/api/notes', { method: 'POST', body: JSON.stringify(note) }),
  update: (id, note) => request(`/api/notes/${id}`, { method: 'PUT', body: JSON.stringify(note) }),
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
}
