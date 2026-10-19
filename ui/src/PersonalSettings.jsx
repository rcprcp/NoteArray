import { useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  Collapse,
  Group,
  PasswordInput,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Title,
  useMantineColorScheme,
} from '@mantine/core'
import { IconKey, IconLogout, IconMoon, IconSun, IconSunMoon } from '@tabler/icons-react'
import { THEME_COLORS } from './ThemeButton'
import { usersApi } from './api'

function formatDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString()
}

function ProfileRow({ label, value }) {
  return (
    <Table.Tr>
      <Table.Td w={140} fw={500}>{label}</Table.Td>
      <Table.Td>{value}</Table.Td>
    </Table.Tr>
  )
}

export default function PersonalSettings({
  user,
  themeColor,
  onThemeChange,
  onLogout,
}) {
  const { colorScheme, setColorScheme } = useMantineColorScheme()
  const [passwordOpen, setPasswordOpen] = useState(false)
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [passwordError, setPasswordError] = useState(null)
  const [passwordSuccess, setPasswordSuccess] = useState(null)
  const [savingPassword, setSavingPassword] = useState(false)

  const handlePasswordSubmit = async () => {
    setPasswordError(null)
    setPasswordSuccess(null)
    if (!newPassword) {
      setPasswordError('Password is required')
      return
    }
    if (newPassword !== confirmPassword) {
      setPasswordError('Passwords do not match')
      return
    }
    if (
      newPassword.length < 8 ||
      !/[A-Z]/.test(newPassword) ||
      !/[a-z]/.test(newPassword) ||
      !/[0-9]/.test(newPassword)
    ) {
      setPasswordError(
        'Password must be at least 8 characters and include uppercase, lowercase, and a number'
      )
      return
    }

    setSavingPassword(true)
    try {
      await usersApi.update(user.id, { password: newPassword })
      setNewPassword('')
      setConfirmPassword('')
      setPasswordSuccess('Password updated successfully.')
      setPasswordOpen(false)
    } catch (e) {
      setPasswordError(e.message || 'Failed to update password')
    } finally {
      setSavingPassword(false)
    }
  }

  return (
    <Stack gap="md" style={{ width: '100%' }}>
      {/* Upper right corner of the main body, directly below and aligned with the header avatar */}
      <Group justify="space-between" align="center">
        <Title order={2} size="h4">Personal Settings</Title>
        <Button
          color="red"
          variant="light"
          leftSection={<IconLogout size={16} />}
          onClick={onLogout}
        >
          Logout
        </Button>
      </Group>

      <Card withBorder shadow="sm" p="lg" radius="md">
        <Title order={3} size="h5" mb="sm">My profile</Title>
        <Table verticalSpacing="xs" withTableBorder>
          <Table.Tbody>
            <ProfileRow label="Name" value={user.name} />
            <ProfileRow label="Email" value={user.email} />
            <ProfileRow label="Theme" value={<Badge color={themeColor}>{user.themeColor || themeColor}</Badge>} />
            <ProfileRow label="Last login" value={formatDate(user.lastLogin)} />
            <ProfileRow label="Created" value={formatDate(user.createdAt)} />
            <ProfileRow label="Updated" value={formatDate(user.updatedAt)} />
            <ProfileRow label="ID" value={<Text size="xs" ff="monospace">{user.id}</Text>} />
          </Table.Tbody>
        </Table>
      </Card>

      <Card withBorder shadow="sm" p="lg" radius="md">
        <Title order={3} size="h5" mb="sm">Theme</Title>
        <Stack gap="md">
          <div>
            <Text size="sm" fw={500} mb="xs">Appearance</Text>
            <Group gap="sm">
              <Button
                variant={colorScheme === 'light' ? 'filled' : 'light'}
                leftSection={<IconSun size={16} />}
                onClick={() => setColorScheme('light')}
              >
                Light
              </Button>
              <Button
                variant={colorScheme === 'dark' ? 'filled' : 'light'}
                leftSection={<IconMoon size={16} />}
                onClick={() => setColorScheme('dark')}
              >
                Dark
              </Button>
              <Button
                variant={colorScheme === 'auto' ? 'filled' : 'light'}
                leftSection={<IconSunMoon size={16} />}
                onClick={() => setColorScheme('auto')}
              >
                Auto
              </Button>
            </Group>
          </div>

          <div>
            <Text size="sm" fw={500} mb="xs">Color</Text>
            <SimpleGrid cols={{ base: 2, xs: 3, sm: 7 }} spacing="sm">
              {THEME_COLORS.map((color) => (
                <Button
                  key={color}
                  color={color}
                  variant={themeColor === color ? 'filled' : 'light'}
                  onClick={() => onThemeChange(color)}
                  styles={{ root: { textTransform: 'capitalize' } }}
                >
                  {color}
                </Button>
              ))}
            </SimpleGrid>
          </div>
        </Stack>
      </Card>

      <Card withBorder shadow="sm" p="lg" radius="md">
        <Group justify="space-between" align="center">
          <Title order={3} size="h5">Security</Title>
          <Button
            variant="light"
            leftSection={<IconKey size={16} />}
            onClick={() => {
              setPasswordOpen((v) => !v)
              setPasswordError(null)
              setPasswordSuccess(null)
            }}
          >
            Change Password
          </Button>
        </Group>

        {passwordSuccess && (
          <Alert color="green" mt="sm" withCloseButton onClose={() => setPasswordSuccess(null)}>
            {passwordSuccess}
          </Alert>
        )}

        <Collapse expanded={passwordOpen}>
          <Stack gap="sm" mt="md">
            {passwordError && (
              <Alert color="red" withCloseButton onClose={() => setPasswordError(null)}>
                {passwordError}
              </Alert>
            )}
            <PasswordInput
              label="New Password"
              required
              value={newPassword}
              onChange={(e) => setNewPassword(e.currentTarget.value)}
            />
            <PasswordInput
              label="Confirm New Password"
              required
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.currentTarget.value)}
            />
            <Text size="xs" c="dimmed">
              Password must be at least 8 characters and include at least one uppercase letter, one lowercase letter, and one number.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setPasswordOpen(false)}>
                Cancel
              </Button>
              <Button onClick={handlePasswordSubmit} loading={savingPassword}>
                Update Password
              </Button>
            </Group>
          </Stack>
        </Collapse>
      </Card>
    </Stack>
  )
}
