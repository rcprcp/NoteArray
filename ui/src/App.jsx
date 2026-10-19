import { useState, useCallback, useEffect, useLayoutEffect, useRef } from 'react'
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom'
import { Avatar, Box, Container, Group, Stack, Text, Title, UnstyledButton } from '@mantine/core'
import { IconChevronDown, IconUpload } from '@tabler/icons-react'
import LoginPage from './LoginPage'
import NotesPanel from './NotesPanel'
import UserMenu, { UserMenuItems, UserMenuDialogs } from './UserMenu'
import HeaderActions from './HeaderActions'

export default function App({ themeColor, onThemeChange, currentUser, onLogin, onLogout }) {
  const navigate = useNavigate()
  const [actions, setActions] = useState(null)
  const [headerOverflow, setHeaderOverflow] = useState(false)
  const [profileOpened, setProfileOpened] = useState(false)
  const [themeOpened, setThemeOpened] = useState(false)
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

  // Close the profile/theme dialogs when the user changes.
  useEffect(() => {
    setProfileOpened(false)
    setThemeOpened(false)
  }, [currentUser])

  if (!currentUser) {
    return <LoginPage onLogin={onLogin} />
  }

  const goToList = () => { navigate('/notes'); actions?.onReset?.() }

  const headerItems = [
    { key: 'list', label: 'List Notes', onClick: goToList },
    { key: 'create', label: 'Create Note', onClick: () => actions?.onCreate?.() },
    { key: 'tags', label: 'Tag Search', active: actions?.tagsOpen, toggle: true, onClick: () => actions?.onToggleTags?.() },
    { key: 'text', label: 'Text Search', active: actions?.searchOpen, toggle: true, onClick: () => actions?.onToggleSearch?.() },
    { key: 'pinned', label: 'Pinned', onClick: () => actions?.onSetPinned?.() },
    { key: 'untagged', label: 'Untagged', onClick: () => actions?.onSetUntagged?.() },
    { key: 'import', label: 'Import', icon: IconUpload, active: actions?.importOpen, toggle: true, onClick: () => actions?.onImport?.() },
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
              // Once anything is pushed into the overflow menu the title yields
              // its space entirely (kept mounted only so it can be measured).
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
              // Rendered inline while there is room for the avatar.
              element: (
                <UserMenu
                  user={currentUser}
                  themeColor={themeColor}
                  onProfile={() => setProfileOpened(true)}
                  onTheme={() => setThemeOpened(true)}
                  onLogout={onLogout}
                />
              ),
              // Measured at its natural width alongside the action buttons.
              measureElement: (
                <UnstyledButton aria-hidden="true" tabIndex={-1}>
                  <Group gap="xs" wrap="nowrap">
                    <Avatar radius="xl" color={themeColor} size="sm" />
                    <Text fw={500}>placeholder</Text>
                    <IconChevronDown size={14} />
                  </Group>
                </UnstyledButton>
              ),
              // Rendered inside the overflow dropdown when the avatar is hidden.
              menuItems: () => (
                <UserMenuItems
                  user={currentUser}
                  onProfile={() => setProfileOpened(true)}
                  onTheme={() => setThemeOpened(true)}
                  onLogout={onLogout}
                />
              ),
            }}
          />
        </Group>
      </Box>

      <UserMenuDialogs
        user={currentUser}
        themeColor={themeColor}
        onThemeChange={onThemeChange}
        profileOpened={profileOpened}
        onProfileClose={() => setProfileOpened(false)}
        themeOpened={themeOpened}
        onThemeClose={() => setThemeOpened(false)}
      />

      <Container fluid my="md" style={{ flex: 1, width: '100%', maxWidth: '100%' }}>
        <Routes>
          <Route path="/notes" element={<NotesPanel registerActions={handleRegister} />} />
          <Route path="*" element={<Navigate to="/notes" replace />} />
        </Routes>
      </Container>
    </Stack>
  )
}
