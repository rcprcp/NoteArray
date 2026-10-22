import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  Alert,
  Badge,
  Button,
  Card,
  Collapse,
  Group,
  PasswordInput,
  Progress,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Title,
  useMantineColorScheme,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconArrowDown, IconArrowUp, IconCheck, IconKey, IconLogout, IconMoon, IconSun, IconSunMoon, IconTrash } from '@tabler/icons-react'
import { THEME_COLORS } from './ThemeButton'
import { notesApi, usersApi } from './api'

function formatDate(value) {
  if (!value) return '—'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString()
}

function formatBytes(bytes) {
  const n = Number(bytes) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(2)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(2)} MB`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

const PLANS = [
  { key: 'free', tier: 0, name: 'Free', storage: '100 MB of storage', limitLabel: '100 MB', limitBytes: 100 * 1024 * 1024, price: 'Free' },
  { key: 'pro', tier: 1, name: 'Pro', storage: '1 GB of note storage', limitLabel: '1 GB', limitBytes: 1 * 1024 * 1024 * 1024, price: '$2/mo' },
  { key: 'premium', tier: 2, name: 'Premium', storage: '5 GB of note storage', limitLabel: '5 GB', limitBytes: 5 * 1024 * 1024 * 1024, price: '$4/mo' },
]

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
  onPlanChange,
  onPlanSync,
  onLogout,
}) {
  const { colorScheme, setColorScheme } = useMantineColorScheme()
  const location = useLocation()
  const navigate = useNavigate()
  const [planSyncing, setPlanSyncing] = useState(false)
  const [passwordOpen, setPasswordOpen] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState({})
  const [passwordError, setPasswordError] = useState(null)
  const [passwordSuccess, setPasswordSuccess] = useState(null)
  const [savingPassword, setSavingPassword] = useState(false)
  const [deleteError, setDeleteError] = useState(null)
  const [deletePassword, setDeletePassword] = useState('')
  const [deleteFieldError, setDeleteFieldError] = useState(null)
  const [deletingAccount, setDeletingAccount] = useState(false)
  const [storageUsage, setStorageUsage] = useState({ usedBytes: 0, noteCount: 0 })

  useEffect(() => {
    let active = true
    notesApi
      .storage()
      .then((res) => {
        if (active && res) {
          setStorageUsage({
            usedBytes: Number(res.usedBytes) || 0,
            noteCount: Number(res.noteCount) || 0,
          })
        }
      })
      .catch(() => {})
    return () => {
      active = false
    }
  }, [user.id])

  const isFreePlan = (user.plan || 'free').toLowerCase() === 'free'
  const hasPendingUpgrade = user.planStatus === 'pending_checkout' || !!user.pendingPlan
  const canDeleteAccount = !hasPendingUpgrade

  const handleDeleteAccount = async () => {
    if (!canDeleteAccount) return
    setDeleteFieldError(null)
    if (!deletePassword) {
      setDeleteFieldError('Password is required to delete your account')
      return
    }
    const confirmMsg = isFreePlan
      ? 'Are you sure you want to permanently delete your account and all of your notes?'
      : 'Deleting your account will cancel your recurring Stripe subscription, await confirmation from Stripe, and permanently delete your account and all of your notes. Continue?'
    if (!window.confirm(confirmMsg)) {
      return
    }
    setDeleteError(null)
    setDeletingAccount(true)
    try {
      // The server re-verifies the current password before deleting; the
      // session cookie alone can no longer trigger destructive deletion.
      await usersApi.remove(user.id, deletePassword)
      await onLogout()
    } catch (e) {
      const msg = e.message || 'Failed to delete account'
      if (msg.toLowerCase().includes('password')) {
        setDeleteFieldError(msg)
      } else {
        setDeleteError(msg)
      }
    } finally {
      setDeletingAccount(false)
    }
  }

  // Handle return redirects from Stripe Checkout (?stripe=success&session_id={CHECKOUT_SESSION_ID}&...&stripe=cancel)
  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const stripeResult = params.get('stripe')
    if (!stripeResult || !onPlanSync) return
    // The client only ever passes the opaque checkout session id back to the
    // server; plan + Stripe identifiers are verified server-side (finding #26).
    if (stripeResult === 'success') {
      const sessionId = params.get('session_id')
      if (sessionId) {
        onPlanSync({
          action: 'confirm',
          checkoutSessionId: sessionId,
        })
      } else {
        // A success redirect without a session id cannot be verified server-side;
        // clear the pending checkout instead of trusting client-declared state.
        notifications.show({
          color: 'yellow',
          title: 'Payment not verified',
          message: 'The Stripe redirect was missing its checkout session. If you were charged, click the plan again so it can be verified.',
        })
        onPlanSync({ action: 'cancel' })
      }
    } else if (stripeResult === 'cancel') {
      onPlanSync({ action: 'cancel' })
    }
    navigate('/settings', { replace: true })
  }, [location.search, navigate, onPlanSync])

  const handlePlanSelect = async (planKey) => {
    setPlanSyncing(true)
    try {
      await onPlanChange?.(planKey)
    } finally {
      setPlanSyncing(false)
    }
  }

  const handleStripeSync = async (payload) => {
    setPlanSyncing(true)
    try {
      await onPlanSync?.(payload)
    } finally {
      setPlanSyncing(false)
    }
  }

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
            <ProfileRow
              label="Plan"
              value={
                <Group gap="xs">
                  <Badge color={themeColor} style={{ textTransform: 'capitalize' }}>
                    {user.plan || 'free'}
                  </Badge>
                  {user.planStatus && user.planStatus !== 'active' && (
                    <Badge color={user.planStatus === 'pending_checkout' ? 'yellow' : 'red'} variant="outline">
                      {user.planStatus === 'pending_checkout'
                        ? `Stripe checkout pending (${user.pendingPlan})`
                        : user.planStatus.replace('_', ' ')}
                    </Badge>
                  )}
                </Group>
              }
            />
            <ProfileRow label="Theme" value={<Badge color={themeColor}>{user.themeColor || themeColor}</Badge>} />
            <ProfileRow label="Last login" value={formatDate(user.lastLogin)} />
            <ProfileRow label="Created" value={formatDate(user.createdAt)} />
            <ProfileRow label="Updated" value={formatDate(user.updatedAt)} />
            <ProfileRow label="ID" value={<Text size="xs" ff="monospace">{user.id}</Text>} />
          </Table.Tbody>
        </Table>
      </Card>

      <Card withBorder shadow="sm" p="lg" radius="md">
        {(() => {
          const currentPlanKey = (user.plan || 'free').toLowerCase()
          const currentPlan = PLANS.find((p) => p.key === currentPlanKey) || PLANS[0]
          const rawPct = (storageUsage.usedBytes / currentPlan.limitBytes) * 100
          const displayPct = storageUsage.usedBytes > 0 ? Math.max(2, Math.min(100, rawPct)) : 0
          return (
            <Stack gap="sm">
              <Group justify="space-between" align="center">
                <Title order={3} size="h5">Storage Used</Title>
                <Badge variant="light" color={themeColor}>
                  {formatBytes(storageUsage.usedBytes)} / {currentPlan.limitLabel} ({currentPlan.name})
                </Badge>
              </Group>

              <div>
                <Group justify="space-between" mb={4}>
                  <Text size="xs" fw={600}>
                    {currentPlan.name} Plan Limit ({currentPlan.limitLabel})
                  </Text>
                  <Text size="xs" c="dimmed">
                    {formatBytes(storageUsage.usedBytes)} of {currentPlan.limitLabel} ({rawPct < 0.01 && storageUsage.usedBytes > 0 ? '<0.01' : rawPct.toFixed(2)}%) · {storageUsage.noteCount} note{storageUsage.noteCount === 1 ? '' : 's'}
                  </Text>
                </Group>
                <Progress
                  value={displayPct}
                  color={rawPct >= 90 ? 'red' : themeColor}
                  size="lg"
                  radius="xl"
                />
              </div>
            </Stack>
          )
        })()}
      </Card>

      <Card withBorder shadow="sm" p="lg" radius="md">
        <Title order={3} size="h5" mb="sm">Available Plans</Title>

        {user.planStatus === 'pending_checkout' && user.pendingPlan && (
          <Alert color="yellow" mb="md" title={`Pending Stripe Checkout for ${user.pendingPlan.toUpperCase()}`}>
            <Stack gap="xs">
              <Text size="sm">
                Finish your Stripe checkout in the payment window — after payment you are redirected
                back here, and the <b>{user.pendingPlan.toUpperCase()}</b> plan activates automatically
                once the payment is verified with Stripe. Or cancel to stay on your current plan.
              </Text>
              <Group gap="xs">
                <Button
                  size="xs"
                  variant="default"
                  loading={planSyncing}
                  onClick={() => handleStripeSync({ action: 'cancel' })}
                >
                  Cancel Checkout
                </Button>
              </Group>
            </Stack>
          </Alert>
        )}

        {user.planStatus === 'past_due' && (
          <Alert color="red" mb="md" title="Stripe Payment Past Due">
            Your subscription payment is past due in Stripe. Please update your payment method or confirm payment sync.
          </Alert>
        )}

        <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
          {(() => {
            const currentPlanKey = (user.plan || 'free').toLowerCase()
            const currentTier = PLANS.find((p) => p.key === currentPlanKey)?.tier ?? 0
            return PLANS.map((plan) => {
              const isCurrent = plan.key === currentPlanKey
              const isPendingTarget = user.planStatus === 'pending_checkout' && user.pendingPlan === plan.key
              const isUpgrade = plan.tier > currentTier
              const badgeLabel = isCurrent
                ? 'Current Plan'
                : isPendingTarget
                  ? 'Pending Stripe'
                  : isUpgrade
                    ? 'Upgrade'
                    : 'Downgrade'
              const badgeColor = isCurrent
                ? themeColor
                : isPendingTarget
                  ? 'yellow'
                  : isUpgrade
                    ? 'green'
                    : 'gray'
              const buttonLabel = isCurrent
                ? 'Current Plan'
                : isPendingTarget
                  ? `Awaiting Stripe (${plan.name})`
                  : isUpgrade
                    ? `Upgrade to ${plan.name}`
                    : `Downgrade to ${plan.name}`
              const ButtonIcon = isCurrent ? IconCheck : isUpgrade ? IconArrowUp : IconArrowDown

              return (
                <Card key={plan.key} withBorder padding="md" radius="md">
                  <Stack gap="xs" justify="space-between" style={{ height: '100%' }}>
                    <Stack gap="xs">
                      <Group justify="space-between" align="center">
                        <Text fw={600} size="md">{plan.name}</Text>
                        <Badge color={badgeColor} variant={isCurrent ? 'filled' : 'light'}>
                          {badgeLabel}
                        </Badge>
                      </Group>
                      <Text size="sm" c="dimmed">{plan.storage}</Text>
                      <Text fw={700} size="lg">{plan.price}</Text>
                    </Stack>
                    <Button
                      variant={isCurrent ? 'filled' : 'light'}
                      color={isUpgrade ? 'green' : themeColor}
                      leftSection={<ButtonIcon size={16} />}
                      fullWidth
                      mt="xs"
                      disabled={isCurrent}
                      loading={planSyncing && isPendingTarget}
                      onClick={() => handlePlanSelect(plan.key)}
                    >
                      {buttonLabel}
                    </Button>
                  </Stack>
                </Card>
              )
            })
          })()}
        </SimpleGrid>
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

      <Card withBorder shadow="sm" p="lg" radius="md">
        <Stack gap="sm">
          <Title order={3} size="h5" c="red">Delete Account</Title>
          {deleteError && (
            <Alert color="red" withCloseButton onClose={() => setDeleteError(null)}>
              {deleteError}
            </Alert>
          )}
          {hasPendingUpgrade && (
            <Alert color="yellow">
              Please complete or cancel your pending Stripe checkout above before deleting your account.
            </Alert>
          )}
          {!isFreePlan && !hasPendingUpgrade && (
            <Alert color="yellow">
              You are currently on the <b style={{ textTransform: 'capitalize' }}>{user.plan}</b> plan. Clicking <b>Delete my account</b> will cancel your recurring Stripe subscription, await confirmation from Stripe, and then permanently delete your account and all notes.
            </Alert>
          )}
          {deletingAccount && !isFreePlan && (
            <Text size="xs" c="dimmed">
              Awaiting Stripe subscription cancellation confirmation and deleting account...
            </Text>
          )}
          <Text size="xs" c="dimmed">
            Deleting is permanent. To confirm, enter your current password — the account cannot be deleted with the session alone.
          </Text>
          <PasswordInput
            label="Current password"
            required
            value={deletePassword}
            error={deleteFieldError}
            disabled={!canDeleteAccount || deletingAccount}
            onChange={(e) => setDeletePassword(e.currentTarget.value)}
            style={{ maxWidth: 320 }}
          />
          <Group justify="flex-start">
            <Button
              color="red"
              variant="filled"
              leftSection={<IconTrash size={16} />}
              disabled={!canDeleteAccount || !deletePassword}
              loading={deletingAccount}
              onClick={handleDeleteAccount}
            >
              Delete my account
            </Button>
          </Group>
        </Stack>
      </Card>
    </Stack>
  )
}
