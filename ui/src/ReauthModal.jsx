import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Group, Modal, PasswordInput, Stack, Text, TextInput } from '@mantine/core'
import { usersApi } from './api'

/**
 * In-place re-authentication prompt. The SPA stays mounted, so the editor's
 * content, cursor and scroll position survive; the failed API request is
 * retried by the caller once this resolves.
 *
 * The email is fixed to the account that owns the open draft. Signing in as a
 * different user would make the pending save ambiguous (and would let one user
 * see another user's draft), so switching accounts is an explicit sign-out.
 */
export default function ReauthModal({ opened, user, onSuccess, onCancel }) {
  const [password, setPassword] = useState('')
  const [totpChallenge, setTotpChallenge] = useState(null)
  const [totpCode, setTotpCode] = useState('')
  const [recoveryCode, setRecoveryCode] = useState('')
  const [useRecoveryCode, setUseRecoveryCode] = useState(false)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const passwordRef = useRef(null)
  const totpRef = useRef(null)
  const recoveryRef = useRef(null)

  // Fresh form each time the prompt opens.
  useEffect(() => {
    if (!opened) return undefined
    setPassword('')
    setTotpChallenge(null)
    setTotpCode('')
    setRecoveryCode('')
    setUseRecoveryCode(false)
    setError(null)
    setLoading(false)
    const timer = setTimeout(() => passwordRef.current?.focus(), 50)
    return () => clearTimeout(timer)
  }, [opened])

  // Move focus into the active second-factor field, like the login page does.
  useEffect(() => {
    if (!opened || !totpChallenge) return
    const target = useRecoveryCode ? recoveryRef.current : totpRef.current
    target?.focus()
  }, [opened, totpChallenge, useRecoveryCode])

  const submitPassword = async (event) => {
    event?.preventDefault()
    if (!user?.email) return
    if (!password) {
      setError('Password is required')
      return
    }
    setLoading(true)
    setError(null)
    try {
      const result = await usersApi.login(user.email, password)
      if (result?.totpRequired) {
        setTotpChallenge(result.challenge)
        setPassword('')
        setTotpCode('')
        setRecoveryCode('')
        setUseRecoveryCode(false)
      } else {
        await onSuccess()
      }
    } catch (e) {
      setError(e.message || 'Sign in failed')
    } finally {
      setLoading(false)
    }
  }

  const submitTotp = async (event) => {
    event?.preventDefault()
    const code = useRecoveryCode ? '' : totpCode.trim()
    const recovery = useRecoveryCode ? recoveryCode.trim() : ''
    if (!useRecoveryCode && !/^\d{6}$/.test(code)) {
      setError('Enter the 6-digit code from your authenticator app.')
      return
    }
    if (useRecoveryCode && !recovery) {
      setError('Enter one of your recovery codes.')
      return
    }
    setLoading(true)
    setError(null)
    try {
      await usersApi.loginTotp(totpChallenge, code, recovery)
      setTotpChallenge(null)
      await onSuccess()
    } catch (e) {
      setError(e.message || 'Verification failed')
      setTotpCode('')
    } finally {
      setLoading(false)
    }
  }

  const backToPassword = () => {
    setTotpChallenge(null)
    setTotpCode('')
    setRecoveryCode('')
    setUseRecoveryCode(false)
    setError(null)
  }

  return (
    <Modal
      opened={opened}
      onClose={onCancel}
      title="Session expired"
      centered
      withCloseButton={false}
      closeOnClickOutside={false}
      closeOnEscape={false}
    >
      <Stack>
        <Text size="sm">
          Your changes are still here. Sign in again to save them to your account.
        </Text>
        {user?.email && (
          <Text size="sm" c="dimmed">
            Signing in as <b>{user.email}</b>
          </Text>
        )}
        {error && <Alert color="red" role="alert">{error}</Alert>}

        {totpChallenge ? (
          <form onSubmit={submitTotp}>
            <Stack>
              <Text size="sm" c="dimmed">
                Enter the 6-digit code from your authenticator app.
              </Text>
              {useRecoveryCode ? (
                <TextInput
                  label="Recovery code"
                  placeholder="XXXX-XXXX-XXXX"
                  required
                  value={recoveryCode}
                  onChange={(e) => setRecoveryCode(e.currentTarget.value)}
                  autoComplete="one-time-code"
                  ref={recoveryRef}
                />
              ) : (
                <TextInput
                  label="Authentication code"
                  placeholder="123456"
                  required
                  value={totpCode}
                  onChange={(e) => setTotpCode(e.currentTarget.value.replace(/\D/g, '').slice(0, 6))}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  ref={totpRef}
                />
              )}
              <Button type="submit" loading={loading} fullWidth color="red">
                Verify &amp; save
              </Button>
              <Group justify="space-between">
                <Button
                  type="button"
                  variant="subtle"
                  size="xs"
                  onClick={() => {
                    setUseRecoveryCode((v) => !v)
                    setError(null)
                  }}
                >
                  {useRecoveryCode ? 'Use authenticator code' : 'Use a recovery code'}
                </Button>
                <Button type="button" variant="subtle" size="xs" onClick={backToPassword}>
                  Back
                </Button>
              </Group>
            </Stack>
          </form>
        ) : (
          <form onSubmit={submitPassword}>
            <Stack>
              <PasswordInput
                label="Password"
                required
                value={password}
                onChange={(e) => setPassword(e.currentTarget.value)}
                autoComplete="current-password"
                ref={passwordRef}
              />
              <Group justify="space-between">
                <Button type="button" variant="subtle" onClick={onCancel} disabled={loading}>
                  Not now
                </Button>
                <Button type="submit" loading={loading} color="red">
                  Sign in &amp; save
                </Button>
              </Group>
            </Stack>
          </form>
        )}
      </Stack>
    </Modal>
  )
}
