import { useState, useCallback } from 'react'
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom'
import { Box, Container, Group, Button, Stack, Title } from '@mantine/core'
import { IconUpload } from '@tabler/icons-react'
import LoginPage from './LoginPage'
import NotesPanel from './NotesPanel'
import UserMenu from './UserMenu'

export default function App({ themeColor, onThemeChange, currentUser, onLogin, onLogout }) {
  const navigate = useNavigate()
  const [actions, setActions] = useState(null)
  const handleRegister = useCallback((a) => setActions(a), [])

  if (!currentUser) {
    return <LoginPage onLogin={onLogin} />
  }

  return (
    <Stack gap={0} style={{ minHeight: '100vh' }}>
      <Box component="header" p="md" style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}>
        <Group justify="space-between" align="center" wrap="nowrap">
          <Group gap="lg" wrap="nowrap">
            <Title
              order={1}
              size="h3"
              style={{ cursor: 'pointer' }}
              onClick={() => { navigate('/notes'); actions?.onReset?.(); }}
            >
              NoteArray
            </Title>
            <Group gap={{ base: 'xs', sm: 'md' }} wrap="nowrap">
              <Button variant="light" onClick={() => { navigate('/notes'); actions?.onReset?.(); }}>
                List Notes
              </Button>
              <Button variant="light" onClick={() => actions?.onCreate?.()}>Create Note</Button>
              <Button variant={actions?.tagsOpen ? "filled" : "light"} onClick={() => actions?.onToggleTags?.()} aria-expanded={actions?.tagsOpen}>
                Tag Search
              </Button>
              <Button variant={actions?.searchOpen ? "filled" : "light"} onClick={() => actions?.onToggleSearch?.()} aria-expanded={actions?.searchOpen}>
                Text Search
              </Button>
              <Button variant="light" onClick={() => actions?.onSetPinned?.()}>Pinned</Button>
              <Button variant="light" onClick={() => actions?.onSetUntagged?.()}>Untagged</Button>
              <Button variant={actions?.importOpen ? "filled" : "light"} leftSection={<IconUpload size={16} />} onClick={() => actions?.onImport?.()}>
                Import
              </Button>
            </Group>
          </Group>

          <UserMenu
            user={currentUser}
            themeColor={themeColor}
            onThemeChange={onThemeChange}
            onLogout={onLogout}
          />
        </Group>
      </Box>

      <Container fluid my="md" style={{ flex: 1, width: "100%", maxWidth: "100%" }}>
        <Routes>
          <Route path="/notes" element={<NotesPanel registerActions={handleRegister} />} />
          <Route path="*" element={<Navigate to="/notes" replace />} />
        </Routes>
      </Container>
    </Stack>
  )
}
