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
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState({})
  const [passwordError, setPasswordError] = useState(null)
  const [passwordSuccess, setPasswordSuccess] = useState(null)
  const [savingPassword, setSavingPassword] = useState(false)

  const resetPasswordForm = () => {
    setCurrentPassword('')
    setNewPassword('')
    setConfirmPassword('')
    setFieldErrors({})
    setPasswordError(null)
  }

  const handlePasswordSubmit = async () => {
    setPasswordError(null)
    setPasswordSuccess(null)

    const errs = {}
    if (!currentPassword) errs.currentPassword = 'Current password is required'
    if (!newPassword) errs.newPassword = 'New password is required'
    if (!confirmPassword) errs.confirmPassword = 'Password verification is required'
    if (Object.keys(errs).length > 0) {
      setFieldErrors(errs)
      return
    }

    setFieldErrors({})
    setSavingPassword(true)
    try {
      // Server validates currentPassword first, then validates newPassword policy and confirmPassword match.
      await usersApi.update(user.id, {
        currentPassword,
        password: newPassword,
        confirmPassword,
      })
      resetPasswordForm()
      setPasswordOpen(false)
      await onLogout()
    } catch (e) {
      const msg = e.message || 'Failed to update password'
      const lower = msg.toLowerCase()
      if (lower.includes('current password')) {
        setFieldErrors({ currentPassword: msg })
      } else if (lower.includes('do not match')) {
        setFieldErrors({ confirmPassword: msg })
      } else if (lower.includes('password must')) {
        setFieldErrors({ newPassword: msg })
      } else {
        setPasswordError(msg)
      }
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
              resetPasswordForm()
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
              label="Current Password"
              required
              value={currentPassword}
              error={fieldErrors.currentPassword}
              onChange={(e) => setCurrentPassword(e.currentTarget.value)}
            />
            <PasswordInput
              label="New Password"
              required
              value={newPassword}
              error={fieldErrors.newPassword}
              onChange={(e) => setNewPassword(e.currentTarget.value)}
            />
            <PasswordInput
              label="Verify New Password"
              required
              value={confirmPassword}
              error={fieldErrors.confirmPassword}
              onChange={(e) => setConfirmPassword(e.currentTarget.value)}
            />
            <Text size="xs" c="dimmed">
              Password must be at least 8 characters and include at least one uppercase letter, one lowercase letter, and one number. Special characters (!@#$%^&*) are optional.
            </Text>
            <Group justify="flex-end">
              <Button
                variant="default"
                onClick={() => {
                  resetPasswordForm()
                  setPasswordOpen(false)
                }}
              >
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
