import React, { useCallback, useEffect, useState } from 'react'
import ReactDOM from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import App from './App'
import { usersApi } from './api'
import '@mantine/core/styles.css'
import '@mantine/tiptap/styles.css'

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
      }
    } catch (e) {
      console.error('Failed to load user after login', e)
    }
  }

  const handleLogout = async () => {
    try {
      await usersApi.logout()
    } catch (e) {
      console.error('Logout error', e)
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
      console.error('Failed to save theme to user record', e)
    }
  }

  if (isLoading) {
    return <div>Loading...</div>
  }

  return (
    <MantineProvider theme={{ primaryColor: themeColor }}>
      <HashRouter>
        <App
          themeColor={themeColor}
          onThemeChange={changeTheme}
          currentUser={currentUser}
          onLogin={handleLogin}
          onLogout={handleLogout}
        />
      </HashRouter>
    </MantineProvider>
  )
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>,
)
