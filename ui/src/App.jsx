import { useState, useCallback, useLayoutEffect, useRef } from 'react'
import { Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Avatar, Box, Container, Group, Menu, Stack, Text, Title, UnstyledButton } from '@mantine/core'
import { IconUpload, IconUser } from '@tabler/icons-react'
import LoginPage from './LoginPage'
import NotesPanel from './NotesPanel'
import HeaderActions from './HeaderActions'
import PersonalSettings from './PersonalSettings'
import HelpPage from './HelpPage'

// Adjustable breakpoint (in px) and side margin for switching between
// wideMode (NoteArray title shown + 10% side margins on all pages) and
// compactMode (NoteArray title hidden + full-width pages).
export const WIDE_MODE_MIN_WIDTH = 960
export const WIDE_MODE_SIDE_MARGIN = '10%'

function initials(name) {
  const parts = (name || '').trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  return (parts[0][0] + (parts[1]?.[0] || '')).toUpperCase()
}

export default function App({ themeColor, onThemeChange, onPlanChange, onPlanSync, currentUser, onLogin, onLogout }) {
  const navigate = useNavigate()
  const location = useLocation()
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
  const wideMode = !headerOverflow && (headerWidth === 0 || headerWidth >= WIDE_MODE_MIN_WIDTH)
  const compactMode = !wideMode

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

  const listOnlyDisabled = location.pathname !== '/notes' || !!actions?.inEditor

  const headerItems = [
    { key: 'create', label: 'Create Note', active: actions?.createOpen, disabled: !!actions?.overQuota, onClick: () => { navigate('/notes'); actions?.onCreate?.() } },
    { key: 'list', label: 'List Notes', onClick: goToList },
    { key: 'search', label: 'Search Notes', active: actions?.searchOpen, toggle: true, disabled: listOnlyDisabled, onClick: () => actions?.onToggleSearch?.() },
    { key: 'pinned', label: 'Pinned', active: actions?.pinnedActive, toggle: true, disabled: listOnlyDisabled, onClick: () => actions?.onSetPinned?.() },
    { key: 'untagged', label: 'Untagged', active: actions?.untaggedActive, toggle: true, disabled: listOnlyDisabled, onClick: () => actions?.onSetUntagged?.() },
    { key: 'import', label: 'Import', icon: IconUpload, active: actions?.importOpen, toggle: true, disabled: listOnlyDisabled || !!actions?.overQuota, onClick: () => actions?.onImport?.() },
    {
      key: 'help',
      label: 'Help/About',
      active: location.pathname.startsWith('/help/'),
      menu: [
        { key: 'faq', label: 'FAQ', active: location.pathname === '/help/faq', onClick: () => navigate('/help/faq') },
        { key: 'privacy', label: 'Privacy Policy', active: location.pathname === '/help/privacy', onClick: () => navigate('/help/privacy') },
        { key: 'security', label: 'Security Notes', active: location.pathname === '/help/security', onClick: () => navigate('/help/security') },
      ],
    },
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
              visibility: compactMode ? 'hidden' : 'visible',
              width: compactMode ? 0 : undefined,
              overflow: 'hidden',
            }}
            aria-hidden={compactMode}
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

      <Container
        fluid
        my="md"
        style={{
          flex: 1,
          width: '100%',
          maxWidth: '100%',
          paddingLeft: wideMode ? WIDE_MODE_SIDE_MARGIN : undefined,
          paddingRight: wideMode ? WIDE_MODE_SIDE_MARGIN : undefined,
        }}
      >
        <Routes>
          <Route path="/notes" element={<NotesPanel registerActions={handleRegister} wideMode={wideMode} />} />
          <Route
            path="/settings"
            element={
              <PersonalSettings
                user={currentUser}
                themeColor={themeColor}
                onThemeChange={onThemeChange}
                onPlanChange={onPlanChange}
                onPlanSync={onPlanSync}
                onLogout={onLogout}
                wideMode={wideMode}
              />
            }
          />
          <Route path="/help/faq" element={<HelpPage pageKey="faq" wideMode={wideMode} />} />
          <Route path="/help/privacy" element={<HelpPage pageKey="privacy" wideMode={wideMode} />} />
          <Route path="/help/security" element={<HelpPage pageKey="security" wideMode={wideMode} />} />
          <Route path="*" element={<Navigate to="/notes" replace />} />
        </Routes>
      </Container>
    </Stack>
  )
}
