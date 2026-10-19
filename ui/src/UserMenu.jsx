import { Avatar, Badge, Group, Menu, Modal, Table, Text, UnstyledButton } from '@mantine/core'
import { IconChevronDown, IconLogout, IconPalette, IconUser } from '@tabler/icons-react'
import { ThemePicker } from './ThemeButton'

function initials(name) {
  const parts = (name || '').trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  return (parts[0][0] + (parts[1]?.[0] || '')).toUpperCase()
}

function formatDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString()
}

// The avatar button plus its dropdown. Rendered inline while the header has
// room; when the header collapses, the same items appear in the overflow menu
// (see UserMenuItems) and this button is simply not rendered.
export default function UserMenu({ user, themeColor, onProfile, onTheme, onLogout }) {
  return (
    <Menu shadow="md" width={240} position="bottom-end">
      <Menu.Target>
        <UnstyledButton aria-label="Personal settings">
          <Group gap="xs" wrap="nowrap">
            <Avatar radius="xl" color={themeColor} size="sm">{initials(user.name)}</Avatar>
            <Text fw={500} visibleFrom="sm">{user.name}</Text>
            <IconChevronDown size={14} />
          </Group>
        </UnstyledButton>
      </Menu.Target>

      <Menu.Dropdown>
        <UserMenuItems user={user} onProfile={onProfile} onTheme={onTheme} onLogout={onLogout} />
      </Menu.Dropdown>
    </Menu>
  )
}

// Menu body shared by the avatar dropdown and the header overflow dropdown.
export function UserMenuItems({ user, onProfile, onTheme, onLogout }) {
  return (
    <>
      <Menu.Label>Signed in as</Menu.Label>
      <Menu.Item leftSection={<IconUser size={16} />} disabled>
        {user.email}
      </Menu.Item>
      <Menu.Divider />
      <Menu.Item leftSection={<IconUser size={16} />} onClick={onProfile}>
        My profile
      </Menu.Item>
      <Menu.Item leftSection={<IconPalette size={16} />} onClick={onTheme}>
        Theme
      </Menu.Item>
      <Menu.Divider />
      <Menu.Item color="red" leftSection={<IconLogout size={16} />} onClick={onLogout}>
        Logout
      </Menu.Item>
    </>
  )
}

// Profile and theme dialogs. Kept separate from the avatar so they stay mounted
// (and can be opened) even when the avatar is collapsed into the overflow menu.
export function UserMenuDialogs({ user, themeColor, onThemeChange, profileOpened, onProfileClose, themeOpened, onThemeClose }) {
  return (
    <>
      <Modal opened={profileOpened} onClose={onProfileClose} title="My profile" size="md">
        <Table verticalSpacing="xs" withTableBorder>
          <Table.Tbody>
            <ProfileRow label="Name" value={user.name} />
            <ProfileRow label="Email" value={user.email} />
            <ProfileRow label="Theme" value={<Badge color={themeColor}>{user.themeColor}</Badge>} />
            <ProfileRow label="Last login" value={formatDate(user.lastLogin)} />
            <ProfileRow label="Created" value={formatDate(user.createdAt)} />
            <ProfileRow label="Updated" value={formatDate(user.updatedAt)} />
            <ProfileRow label="ID" value={<Text size="xs" ff="monospace">{user.id}</Text>} />
          </Table.Tbody>
        </Table>
      </Modal>

      <ThemePicker
        opened={themeOpened}
        onClose={onThemeClose}
        value={themeColor}
        onThemeChange={onThemeChange}
      />
    </>
  )
}

function ProfileRow({ label, value }) {
  return (
    <Table.Tr>
      <Table.Td w={120} fw={500}>{label}</Table.Td>
      <Table.Td>{value}</Table.Td>
    </Table.Tr>
  )
}
