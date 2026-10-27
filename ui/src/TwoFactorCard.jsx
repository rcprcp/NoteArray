import { useEffect, useState } from 'react'
import {
  Alert, Badge, Button, Card, Code, CopyButton, Group, Modal, PasswordInput,
  Stack, Text, TextInput, Title,
} from '@mantine/core'
import { IconDownload, IconRefresh, IconShieldLock } from '@tabler/icons-react'
import { QRCodeSVG } from 'qrcode.react'
import { usersApi } from './api'

function splitCode(value) {
  const trimmed = (value || '').trim()
  if (/^\d{6}$/.test(trimmed)) return { totpCode: trimmed, recoveryCode: null }
  return { totpCode: null, recoveryCode: trimmed || null }
}

function downloadRecoveryCodes(codes) {
  const blob = new Blob([
    'NoteArray — two-factor recovery codes\n',
    'Each code can be used once. Store them somewhere safe.\n\n',
    ...codes.map((c) => `${c}\n`),
  ], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'notearray-recovery-codes.txt'
  link.click()
  URL.revokeObjectURL(url)
}

export default function TwoFactorCard({ onStatusChange }) {
  const [status, setStatus] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [modal, setModal] = useState(null) // password | setup | codes | disable | regenerate
  const [password, setPassword] = useState('')
  const [setup, setSetup] = useState(null)
  const [code, setCode] = useState('')
  const [recoveryCodes, setRecoveryCodes] = useState(null)

  const loadStatus = async () => {
    try {
      const next = await usersApi.totpStatus()
      setStatus(next)
      onStatusChange?.(!!next?.enabled)
    } catch (e) {
      setError(e.message || 'Could not load two-factor status')
    }
  }

  useEffect(() => {
    loadStatus()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const closeModal = () => {
    setModal(null)
    setPassword('')
    setCode('')
    setSetup(null)
    setError(null)
  }

  const beginSetup = async () => {
    setBusy(true)
    setError(null)
    try {
      const result = await usersApi.totpSetup(password)
      setSetup(result)
      setPassword('')
      setModal('setup')
    } catch (e) {
      setError(e.message || 'Could not start two-factor setup')
    } finally {
      setBusy(false)
    }
  }

  const confirmEnable = async () => {
    setBusy(true)
    setError(null)
    try {
      const result = await usersApi.totpEnable(code.trim())
      setRecoveryCodes(result.recoveryCodes)
      setCode('')
      setModal('codes')
      await loadStatus()
    } catch (e) {
      setError(e.message || 'Could not enable two-factor authentication')
    } finally {
      setBusy(false)
    }
  }

  const confirmDisable = async () => {
    setBusy(true)
    setError(null)
    const { totpCode, recoveryCode } = splitCode(code)
    try {
      await usersApi.totpDisable(password, totpCode, recoveryCode)
      setPassword('')
      setCode('')
      setModal(null)
      await loadStatus()
    } catch (e) {
      setError(e.message || 'Could not disable two-factor authentication')
    } finally {
      setBusy(false)
    }
  }

  const regenerate = async () => {
    setBusy(true)
    setError(null)
    const { totpCode, recoveryCode } = splitCode(code)
    try {
      const result = await usersApi.regenerateRecoveryCodes(totpCode, recoveryCode)
      setRecoveryCodes(result.recoveryCodes)
      setCode('')
      setModal('codes')
    } catch (e) {
      setError(e.message || 'Could not regenerate recovery codes')
    } finally {
      setBusy(false)
    }
  }

  const enabled = !!status?.enabled

  return (
    <Card withBorder shadow="sm" p="lg" radius="md">
      <Group justify="space-between" align="center">
        <Group gap="xs">
          <IconShieldLock size={18} />
          <Title order={3} size="h5">Two-factor authentication</Title>
          {status && <Badge color={enabled ? 'teal' : 'gray'}>{enabled ? 'Enabled' : 'Not enabled'}</Badge>}
        </Group>
        {enabled ? (
          <Group gap="xs">
            <Button
              variant="light"
              leftSection={<IconRefresh size={16} />}
              onClick={() => { setCode(''); setError(null); setModal('regenerate') }}
            >
              New recovery codes
            </Button>
            <Button
              color="red"
              variant="light"
              onClick={() => { setPassword(''); setCode(''); setError(null); setModal('disable') }}
            >
              Disable
            </Button>
          </Group>
        ) : (
          <Button
            variant="light"
            leftSection={<IconShieldLock size={16} />}
            onClick={() => { setPassword(''); setError(null); setModal('password') }}
          >
            Enable
          </Button>
        )}
      </Group>

      <Text size="sm" c="dimmed" mt="xs">
        Use Google Authenticator (or any TOTP app) as a second step at login.
      </Text>

      {enabled && (
        <Text size="sm" mt="sm">
          Recovery codes remaining: <b>{status?.recoveryCodesRemaining ?? 0}</b>
        </Text>
      )}

      {error && !modal && (
        <Alert color="red" mt="sm" withCloseButton onClose={() => setError(null)}>{error}</Alert>
      )}

      <Modal opened={modal === 'password'} onClose={closeModal} title="Enable two-factor authentication">
        <Stack>
          {error && <Alert color="red" withCloseButton onClose={() => setError(null)}>{error}</Alert>}
          <Text size="sm">Confirm your current password to start setup.</Text>
          <PasswordInput
            label="Current password"
            required
            value={password}
            onChange={(e) => setPassword(e.currentTarget.value)}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={closeModal}>Cancel</Button>
            <Button onClick={beginSetup} loading={busy} disabled={!password}>Continue</Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={modal === 'setup'} onClose={closeModal} title="Scan with Google Authenticator" size="lg">
        <Stack align="center">
          {error && <Alert color="red" withCloseButton onClose={() => setError(null)}>{error}</Alert>}
          {setup && (
            <>
              <div style={{ background: 'white', padding: 12, borderRadius: 8 }}>
                <QRCodeSVG value={setup.otpauthUrl} size={190} />
              </div>
              <Text size="sm" c="dimmed" ta="center">
                Can&apos;t scan? Enter this key manually ({setup.issuer}, {setup.account}):
              </Text>
              <Group gap="xs" justify="center">
                <Code>{setup.secret}</Code>
                <CopyButton value={setup.secret}>
                  {({ copied, copy }) => (
                    <Button size="xs" variant="light" onClick={copy}>{copied ? 'Copied' : 'Copy'}</Button>
                  )}
                </CopyButton>
              </Group>
              <TextInput
                label="6-digit code"
                placeholder="123456"
                value={code}
                onChange={(e) => setCode(e.currentTarget.value.replace(/\D/g, '').slice(0, 6))}
                inputMode="numeric"
                autoComplete="one-time-code"
                w="100%"
              />
              <Group justify="flex-end" w="100%">
                <Button variant="default" onClick={closeModal}>Cancel</Button>
                <Button onClick={confirmEnable} loading={busy} disabled={code.length !== 6}>
                  Enable
                </Button>
              </Group>
            </>
          )}
        </Stack>
      </Modal>

      <Modal opened={modal === 'codes'} onClose={closeModal} title="Save your recovery codes" size="lg">
        <Stack>
          <Alert color="yellow">
            These codes are shown only once. Each can be used once if you lose access
            to your authenticator app. Store them somewhere safe.
          </Alert>
          <Code block style={{ whiteSpace: 'pre-wrap', lineHeight: 1.9 }}>
            {recoveryCodes?.join('\n')}
          </Code>
          <Group justify="space-between">
            <Button
              variant="light"
              leftSection={<IconDownload size={16} />}
              onClick={() => downloadRecoveryCodes(recoveryCodes || [])}
            >
              Download
            </Button>
            <Button onClick={closeModal}>I saved them</Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={modal === 'disable'} onClose={closeModal} title="Disable two-factor authentication">
        <Stack>
          {error && <Alert color="red" withCloseButton onClose={() => setError(null)}>{error}</Alert>}
          <PasswordInput
            label="Current password"
            required
            value={password}
            onChange={(e) => setPassword(e.currentTarget.value)}
          />
          <TextInput
            label="Authenticator code or recovery code"
            placeholder="123456 or XXXX-XXXX-XXXX"
            required
            value={code}
            onChange={(e) => setCode(e.currentTarget.value)}
            autoComplete="one-time-code"
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={closeModal}>Cancel</Button>
            <Button color="red" onClick={confirmDisable} loading={busy} disabled={!password || !code}>
              Disable
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal opened={modal === 'regenerate'} onClose={closeModal} title="New recovery codes">
        <Stack>
          {error && <Alert color="red" withCloseButton onClose={() => setError(null)}>{error}</Alert>}
          <Text size="sm">Old recovery codes stop working immediately. Confirm with your authenticator or a recovery code.</Text>
          <TextInput
            label="Authenticator code or recovery code"
            placeholder="123456 or XXXX-XXXX-XXXX"
            required
            value={code}
            onChange={(e) => setCode(e.currentTarget.value)}
            autoComplete="one-time-code"
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={closeModal}>Cancel</Button>
            <Button onClick={regenerate} loading={busy} disabled={!code}>Generate</Button>
          </Group>
        </Stack>
      </Modal>
    </Card>
  )
}
