import { useState, useCallback, useLayoutEffect, useRef } from 'react'
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom'
import { Avatar, Box, Container, Group, Menu, Stack, Text, Title, UnstyledButton } from '@mantine/core'
import { IconUpload, IconUser } from '@tabler/icons-react'
import LoginPage from './LoginPage'
import NotesPanel from './NotesPanel'
import HeaderActions from './HeaderActions'
import PersonalSettings from './PersonalSettings'

function initials(name) {
  const parts = (name || '').trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  return (parts[0][0] + (parts[1]?.[0] || '')).toUpperCase()
}

export default function App({ themeColor, onThemeChange, currentUser, onLogin, onLogout }) {
  const navigate = useNavigate()
  const [actions, setActions] = useState(null)
  const [headerOverflow, setHeaderOverflow] = useState(false)
  const headerRef = useRef(null)
  const titleRef = useRef(null)
  const [headerWidth, setHeaderWidth] = useState(0)
  const [titleWidth, setTitleWidth] = useState(0)
  const handleRegister = useCallback((a) => setActions(a), [])

  // Track the header's own width (independent of its children) and the title's
  // natural width, so the overflow decision does not depend on whether the
  // title is currently rendered — otherwise the two would oscillate.
  useLayoutEffect(() => {
    const el = headerRef.current
    if (!el) return undefined
    const update = () => setHeaderWidth(el.clientWidth)
    update()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(update)
    ro.observe(el)
    return () => ro.disconnect()
  }, [currentUser])

  useLayoutEffect(() => {
    const el = titleRef.current
    if (el) setTitleWidth(el.getBoundingClientRect().width)
  }, [currentUser, headerWidth])

  // Room the action row may use: the header's inner width, minus the title and
  // the gap beside it. Measured from stable elements, so it never changes just
  // because items were moved into the overflow menu.
  const headerInner = Math.max(0, headerWidth - 32) // p="md" => 16px padding each side
  const availableActionsWidth = Math.max(0, headerInner - titleWidth - 8)

  const handleLoginSuccess = async () => {
    await onLogin()
    navigate('/notes', { replace: true })
  }

  if (!currentUser) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage onLogin={handleLoginSuccess} />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    )
  }

  const goToList = () => {
    navigate('/notes')
    actions?.onReset?.()
  }

  const openSettings = () => {
    navigate('/settings')
  }

  const headerItems = [
    { key: 'list', label: 'List Notes', onClick: goToList },
    { key: 'create', label: 'Create Note', onClick: () => { navigate('/notes'); actions?.onCreate?.() } },
    { key: 'tags', label: 'Tag Search', active: actions?.tagsOpen, toggle: true, onClick: () => { navigate('/notes'); actions?.onToggleTags?.() } },
    { key: 'text', label: 'Text Search', active: actions?.searchOpen, toggle: true, onClick: () => { navigate('/notes'); actions?.onToggleSearch?.() } },
    { key: 'pinned', label: 'Pinned', onClick: () => { navigate('/notes'); actions?.onSetPinned?.() } },
    { key: 'untagged', label: 'Untagged', onClick: () => { navigate('/notes'); actions?.onSetUntagged?.() } },
    { key: 'import', label: 'Import', icon: IconUpload, active: actions?.importOpen, toggle: true, onClick: () => { navigate('/notes'); actions?.onImport?.() } },
  ]

  return (
    <Stack gap={0} style={{ minHeight: '100vh', overflowX: 'clip' }}>
      <Box
        ref={headerRef}
        component="header"
        p="md"
        style={{ borderBottom: '1px solid var(--mantine-color-default-border)', overflowX: 'clip' }}
      >
        <Group justify="space-between" align="center" wrap="nowrap" gap="xs">
          <Title
            ref={titleRef}
            order={1}
            size="h3"
            style={{
              cursor: 'pointer',
              flex: '0 0 auto',
              whiteSpace: 'nowrap',
              visibility: headerOverflow ? 'hidden' : 'visible',
              width: headerOverflow ? 0 : undefined,
              overflow: 'hidden',
            }}
            aria-hidden={headerOverflow}
            onClick={goToList}
          >
            NoteArray
          </Title>

          <HeaderActions
            items={headerItems}
            availableWidth={availableActionsWidth}
            onOverflowChange={setHeaderOverflow}
            userMenu={{
              // Rendered inline while there is room for the avatar. Clicking
              // opens Personal Settings in the main area (no dropdown).
              element: (
                <UnstyledButton aria-label="Personal settings" onClick={openSettings}>
                  <Group gap="xs" wrap="nowrap">
                    <Avatar radius="xl" color={themeColor} size="sm">
                      {initials(currentUser.name)}
                    </Avatar>
                    <Text fw={500} visibleFrom="sm">{currentUser.name}</Text>
                  </Group>
                </UnstyledButton>
              ),
              measureElement: (
                <UnstyledButton aria-hidden="true" tabIndex={-1}>
                  <Group gap="xs" wrap="nowrap">
                    <Avatar radius="xl" color={themeColor} size="sm" />
                    <Text fw={500}>{currentUser.name || 'placeholder'}</Text>
                  </Group>
                </UnstyledButton>
              ),
              menuItems: () => (
                <Menu.Item leftSection={<IconUser size={16} />} onClick={openSettings}>
                  Personal settings
                </Menu.Item>
              ),
            }}
          />
        </Group>
      </Box>

      <Container fluid my="md" style={{ flex: 1, width: '100%', maxWidth: '100%' }}>
        <Routes>
          <Route path="/notes" element={<NotesPanel registerActions={handleRegister} />} />
          <Route
            path="/settings"
            element={
              <PersonalSettings
                user={currentUser}
                themeColor={themeColor}
                onThemeChange={onThemeChange}
                onLogout={onLogout}
              />
            }
          />
          <Route path="*" element={<Navigate to="/notes" replace />} />
        </Routes>
      </Container>
    </Stack>
  )
}
