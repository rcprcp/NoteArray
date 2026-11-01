// Single-flight gate between the API client and the re-authentication modal.
//
// When a request fails with 401, `requestReauth()` opens one prompt (even if
// many requests fail at once) and resolves `true` after a successful sign-in or
// `false` when the user declines. The API client then retries the failed
// request once. Keeping the SPA mounted means the editor's content, cursor and
// scroll position are never lost.

const CHANNEL_NAME = 'notearray-auth'

let enabled = false
let listeners = new Set()
let pending = null
let pendingFinish = null
let channel = null

try {
  if (typeof BroadcastChannel !== 'undefined') {
    channel = new BroadcastChannel(CHANNEL_NAME)
    channel.onmessage = (event) => {
      const type = event?.data?.type
      if (type === 'reauth-success') pendingFinish?.(true)
      else if (type === 'logout') pendingFinish?.(false)
    }
  }
} catch (e) {
  channel = null
}

/** Re-auth only makes sense while the app believes a user is signed in. */
export function setReauthEnabled(value) {
  enabled = !!value
  if (!enabled) pendingFinish?.(false)
}

export function isReauthEnabled() {
  return enabled
}

/** Subscribe the modal. Returns an unsubscribe function for `useEffect`. */
export function onSessionExpired(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * Ask the user to sign in again. Concurrent callers share one prompt.
 * Resolves `false` immediately when re-auth is disabled or nobody is listening
 * (for example during the initial `/api/users/me` probe).
 */
export function requestReauth() {
  if (!enabled || listeners.size === 0) return Promise.resolve(false)
  if (pending) return pending

  const promise = new Promise((resolve) => {
    let settled = false
    const finish = (ok) => {
      if (settled) return
      settled = true
      if (pendingFinish === finish) pendingFinish = null
      resolve(!!ok)
    }
    pendingFinish = finish
    for (const listener of listeners) {
      try {
        listener(finish)
      } catch (e) {
        finish(false)
      }
    }
  })
  pending = promise
  promise.finally(() => {
    if (pending === promise) pending = null
  })
  return promise
}

/** Resolve the outstanding prompt (the modal calls this after success/cancel). */
export function resolveReauth(ok) {
  pendingFinish?.(ok)
}

/** Tell other tabs that the shared cookie is valid again. */
export function broadcastReauthSuccess() {
  try {
    channel?.postMessage({ type: 'reauth-success' })
  } catch (e) {
    // BroadcastChannel is optional; the current tab still proceeds.
  }
}

/** Tell other tabs that the user explicitly signed out. */
export function broadcastLogout() {
  try {
    channel?.postMessage({ type: 'logout' })
  } catch (e) {
    // ignore
  }
}
