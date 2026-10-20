import React, { useEffect, useState } from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { Notifications, notifications } from '@mantine/notifications'
import App from './App'
import { usersApi } from './api'
import '@mantine/core/styles.css'
import '@mantine/tiptap/styles.css'
import '@mantine/notifications/styles.css'

const THEME_KEY = 'notearray-theme-color'
const LEGACY_THEME_KEY = 'notary-theme-color'

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

// SPA shell. Session is managed via HttpOnly cookies.
function Root() {
  const [themeColor, setThemeColor] = useState(getInitialThemeColor)
  const [currentUser, setCurrentUser] = useState(null)
  const [isLoading, setIsLoading] = useState(true)

  // Check if user is already logged in (session cookie exists)
  useEffect(() => {
    const checkSession = async () => {
      try {
        const user = await usersApi.getCurrentUser()
        setCurrentUser(user)
        if (user?.themeColor) {
          setThemeColor(user.themeColor)
          localStorage.setItem(THEME_KEY, user.themeColor)
        }
      } catch (e) {
        // Not logged in or session expired
        setCurrentUser(null)
      } finally {
        setIsLoading(false)
      }
    }
    checkSession()
  }, [])

  const handleLogin = async () => {
    try {
      const user = await usersApi.getCurrentUser()
      setCurrentUser(user)
      if (user?.themeColor) {
        setThemeColor(user.themeColor)
        localStorage.setItem(THEME_KEY, user.themeColor)
      }
    } catch (e) {
      // Surface to the login page, which displays it in its error alert.
      throw new Error('Logged in, but loading your account failed. Please try again.')
    }
  }

  const handleLogout = async () => {
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
          onPlanChange={changePlan}
          onPlanSync={syncPlan}
          currentUser={currentUser}
          onLogin={handleLogin}
          onLogout={handleLogout}
        />
      </BrowserRouter>
    </MantineProvider>
  )
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>,
)
