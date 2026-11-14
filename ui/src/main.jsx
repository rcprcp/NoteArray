import React, { useEffect, useRef, useState } from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { Notifications, notifications } from '@mantine/notifications'
import App from './App'
import ReauthModal from './ReauthModal'
import { billingApi, usersApi } from './api'
import { broadcastLogout, broadcastReauthSuccess, onSessionExpired, setReauthEnabled } from './sessionExpiry'
import { clearUserDrafts } from './draftStore'
import '@mantine/core/styles.css'
import '@mantine/tiptap/styles.css'
import '@mantine/notifications/styles.css'

const THEME_KEY = 'notearray-theme-color'
const LEGACY_THEME_KEY = 'notary-theme-color'
const HOUR_CYCLE_KEY = 'notearray-hour-cycle'

function getInitialThemeColor() {
  const savedColor = localStorage.getItem(THEME_KEY)
  if (savedColor) return savedColor

  const legacyColor = localStorage.getItem(LEGACY_THEME_KEY)
  if (legacyColor) {
    localStorage.setItem(THEME_KEY, legacyColor)
    return legacyColor
  }

  return 'blue'
}

function getInitialHourCycle() {
  return localStorage.getItem(HOUR_CYCLE_KEY) || 'auto'
}

// SPA shell. Session is managed via HttpOnly cookies.
function Root() {
  const [themeColor, setThemeColor] = useState(getInitialThemeColor)
  const [hourCycle, setHourCycle] = useState(getInitialHourCycle)
  const [currentUser, setCurrentUser] = useState(null)
  const [isLoading, setIsLoading] = useState(true)
  // Feature switch sourced from GET /api/billing/config. Defaults to hidden:
  // an unreachable/older backend must not advertise plans it cannot sell.
  const [billingEnabled, setBillingEnabled] = useState(false)
  // In-place re-authentication: a 401 on a protected call opens this modal
  // instead of navigating away (so the editor keeps its content).
  const [reauthOpened, setReauthOpened] = useState(false)
  const reauthFinishRef = useRef(null)

  // Re-auth is only meaningful while the app has a signed-in user; the initial
  // /api/users/me probe must fall through to the login page instead.
  useEffect(() => {
    setReauthEnabled(!!currentUser)
  }, [currentUser])

  useEffect(() => onSessionExpired((finish) => {
    reauthFinishRef.current = finish
    setReauthOpened(true)
  }), [])

  // Check if user is already logged in (session cookie exists) and load the
  // public client config in parallel, before the first real render.
  useEffect(() => {
    let active = true

    const checkSession = async () => {
      try {
        const user = await usersApi.getCurrentUser({ retryOnReauth: false })
        if (!active) return
        setCurrentUser(user)
        if (user?.themeColor) {
          setThemeColor(user.themeColor)
          localStorage.setItem(THEME_KEY, user.themeColor)
        }
        if (user?.hourCycle) {
          setHourCycle(user.hourCycle)
          localStorage.setItem(HOUR_CYCLE_KEY, user.hourCycle)
        }
      } catch (e) {
        // Not logged in or session expired
        if (active) setCurrentUser(null)
      }
    }

    const loadBillingConfig = async () => {
      try {
        const config = await billingApi.config()
        if (active) setBillingEnabled(config?.enabled === true)
      } catch (e) {
        if (active) setBillingEnabled(false)
      }
    }

    Promise.allSettled([checkSession(), loadBillingConfig()]).finally(() => {
      if (active) setIsLoading(false)
    })

    return () => {
      active = false
    }
  }, [])

  const handleLogin = async () => {
    try {
      const user = await usersApi.getCurrentUser({ retryOnReauth: false })
      setCurrentUser(user)
      if (user?.themeColor) {
        setThemeColor(user.themeColor)
        localStorage.setItem(THEME_KEY, user.themeColor)
      }
      if (user?.hourCycle) {
        setHourCycle(user.hourCycle)
        localStorage.setItem(HOUR_CYCLE_KEY, user.hourCycle)
      }
    } catch (e) {
      // Surface to the login page, which displays it in its error alert.
      throw new Error('Logged in, but loading your account failed. Please try again.')
    }
  }

  // Re-auth succeeded: refresh the account (the new cookie is already set),
  // close the modal, and release every queued request so it retries once.
  const handleReauthSuccess = async () => {
    await handleLogin()
    setReauthOpened(false)
    const finish = reauthFinishRef.current
    reauthFinishRef.current = null
    finish?.(true)
    // Other tabs share the cookie: let their queued requests retry too.
    broadcastReauthSuccess()
  }

  // User declined: keep the editor open and let the draft journal protect the
  // content; the failed request surfaces its normal error.
  const handleReauthCancel = () => {
    setReauthOpened(false)
    const finish = reauthFinishRef.current
    reauthFinishRef.current = null
    finish?.(false)
  }

  const handleLogout = async () => {
    const userId = currentUser?.id
    // Disable re-auth first: a logout must not open the "session expired"
    // prompt, and the editor's unmount must not re-journal the draft after the
    // explicit sign-out has cleared it.
    setReauthEnabled(false)
    broadcastLogout()
    try {
      await usersApi.logout()
    } catch (e) {
      // The local session is cleared regardless; just tell the user.
      notifications.show({
        color: 'red',
        title: 'Logout failed',
        message: 'The server could not end the session; you have been signed out locally.',
      })
    }
    // An explicit sign-out discards that user's local drafts. Session expiry
    // does not call this path, so an unexpected expiry keeps the draft.
    if (userId) clearUserDrafts(userId).catch(() => {})
    setCurrentUser(null)
  }

  // Apply immediately, then persist to the current user's record.
  const changeTheme = async (color) => {
    setThemeColor(color)
    localStorage.setItem(THEME_KEY, color)
    if (!currentUser) return
    try {
      await usersApi.update(currentUser.id, { themeColor: color })
      // Update local state
      setCurrentUser({ ...currentUser, themeColor: color })
    } catch (e) {
      notifications.show({
        color: 'red',
        title: 'Theme not saved',
        message: 'Your color choice applies now but could not be saved to your account.',
      })
    }
  }

  // Apply immediately, then persist to the current user's record.
  const changeHourCycle = async (cycle) => {
    setHourCycle(cycle)
    localStorage.setItem(HOUR_CYCLE_KEY, cycle)
    if (!currentUser) return
    try {
      await usersApi.update(currentUser.id, { hourCycle: cycle })
      setCurrentUser({ ...currentUser, hourCycle: cycle })
    } catch (e) {
      notifications.show({
        color: 'red',
        title: 'Time format not saved',
        message: 'Your clock preference applies now but could not be saved to your account.',
      })
    }
  }

  const changePlan = async (plan) => {
    if (!currentUser) return
    try {
      const res = await usersApi.checkoutPlan(currentUser.id, plan)
      if (res?.user) {
        setCurrentUser(res.user)
      }
      if (res?.checkoutUrl) {
        window.location.href = res.checkoutUrl
      }
    } catch (e) {
      notifications.show({
        color: 'red',
        title: 'Plan change failed',
        message: e.message || 'Your plan selection could not be processed.',
      })
    }
  }

  const syncPlan = async (payload) => {
    if (!currentUser) return
    try {
      const updated = await usersApi.syncPlan(currentUser.id, payload)
      if (updated) {
        setCurrentUser(updated)
      }
    } catch (e) {
      notifications.show({
        color: 'red',
        title: 'Stripe sync failed',
        message: e.message || 'Could not synchronize subscription state.',
      })
    }
  }

  if (isLoading) {
    return <div>Loading...</div>
  }

  return (
    <MantineProvider theme={{ primaryColor: themeColor }} defaultColorScheme="auto">
      <Notifications position="top-center" />
      <BrowserRouter>
        <App
          themeColor={themeColor}
          onThemeChange={changeTheme}
          hourCycle={hourCycle}
          onHourCycleChange={changeHourCycle}
          onPlanChange={changePlan}
          onPlanSync={syncPlan}
          billingEnabled={billingEnabled}
          currentUser={currentUser}
          onLogin={handleLogin}
          onLogout={handleLogout}
        />
      </BrowserRouter>
      <ReauthModal
        opened={reauthOpened && !!currentUser}
        user={currentUser}
        onSuccess={handleReauthSuccess}
        onCancel={handleReauthCancel}
      />
    </MantineProvider>
  )
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>,
)
