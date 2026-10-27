import { useState } from 'react'
import { Container, Stack, Text, Button, PasswordInput, TextInput, Group, Card, Title, Alert } from '@mantine/core'
import { usersApi } from './api'

const LOCAL_PART_RE = /^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+$/
const DOMAIN_LABEL_RE = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/

export function normalizeEmail(raw) {
  return (raw || '').trim().toLowerCase()
}

export function normalizeName(raw) {
  return (raw || '').trim()
}

export function validateEmail(raw) {
  const value = normalizeEmail(raw)
  if (!value) return 'Email is required'
  if (value.length > 320) return 'Email must be at most 320 characters'
  if (/[\u0000-\u001F\u007F\s]/.test(value)) return 'Invalid email format'

  const at = value.indexOf('@')
  if (at <= 0 || at !== value.lastIndexOf('@') || at === value.length - 1) {
    return 'Invalid email format'
  }

  const local = value.slice(0, at)
  const domain = value.slice(at + 1)

  if (
    local.length > 64 ||
    local.startsWith('.') ||
    local.endsWith('.') ||
    local.includes('..') ||
    !LOCAL_PART_RE.test(local)
  ) {
    return 'Invalid email format'
  }

  if (
    domain.length > 255 ||
    !domain.includes('.') ||
    domain.startsWith('.') ||
    domain.endsWith('.') ||
    domain.includes('..')
  ) {
    return 'Invalid email format'
  }

  const labels = domain.split('.')
  if (labels.length < 2) return 'Invalid email format'
  for (const label of labels) {
    if (!label || label.length > 63 || !DOMAIN_LABEL_RE.test(label)) {
      return 'Invalid email format'
    }
  }
  const tld = labels[labels.length - 1]
  if (!/[a-z]/.test(tld)) return 'Invalid email format'

  return null
}

export function validateName(raw) {
  const value = normalizeName(raw)
  if (!value) return 'Name is required'
  if (value.length > 255) return 'Name must be at most 255 characters'
  if (/[\u0000-\u001F\u007F]/.test(value)) return 'Invalid name'
  return null
}

export default function LoginPage({ onLogin }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [mode, setMode] = useState('login') // 'login' or 'signup'
  const [name, setName] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [fieldErrors, setFieldErrors] = useState({})
  const [totpChallenge, setTotpChallenge] = useState(null)
  const [totpCode, setTotpCode] = useState('')
  const [recoveryCode, setRecoveryCode] = useState('')
  const [useRecoveryCode, setUseRecoveryCode] = useState(false)

  const handleLogin = async () => {
    const normalizedEmail = normalizeEmail(email)
    setEmail(normalizedEmail)
    const emailErr = validateEmail(normalizedEmail)
    const errs = {}
    if (emailErr) errs.email = emailErr
    if (!password) errs.password = 'Password is required'
    if (Object.keys(errs).length > 0) {
      setFieldErrors(errs)
      return
    }
    setFieldErrors({})
    setLoading(true)
    try {
      const result = await usersApi.login(normalizedEmail, password)
      setError(null)
      if (result?.totpRequired) {
        setTotpChallenge(result.challenge)
        setPassword('')
        setTotpCode('')
        setRecoveryCode('')
        setUseRecoveryCode(false)
        return
      }
      await onLogin()
    } catch (e) {
      setError(e.message || 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  const handleTotp = async () => {
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
      await onLogin()
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

  const handleSignup = async () => {
    const normalizedName = normalizeName(name)
    const normalizedEmail = normalizeEmail(email)
    setName(normalizedName)
    setEmail(normalizedEmail)

    const errs = {}
    const nameErr = validateName(normalizedName)
    const emailErr = validateEmail(normalizedEmail)
    if (nameErr) errs.name = nameErr
    if (emailErr) errs.email = emailErr
    if (!password) errs.password = 'Password is required'
    if (password !== passwordConfirm) errs.passwordConfirm = 'Passwords do not match'
    if (Object.keys(errs).length > 0) {
      setFieldErrors(errs)
      return
    }
    // Basic client-side check mirrors server policy; server remains authoritative.
    if (password.length < 8 || !/[A-Z]/.test(password) || !/[a-z]/.test(password) || !/[0-9]/.test(password)) {
      setFieldErrors({ password: 'Password does not meet requirements' })
      return
    }
    setFieldErrors({})
    setLoading(true)
    try {
      await usersApi.create({ name: normalizedName, email: normalizedEmail, password })
      // Auto-login after signup
      await usersApi.login(normalizedEmail, password)
      setError(null)
      await onLogin()
    } catch (e) {
      if (e.status === 409) {
        const msg = e.message.toLowerCase()
        if (msg.includes('name')) {
          setError('Name already in use')
        } else if (msg.includes('email')) {
          setError('Email already in use')
        } else {
          setError(e.message || 'Signup failed')
        }
      } else {
        setError(e.message || 'Signup failed')
      }
    } finally {
      setLoading(false)
    }
  }

  const toggleMode = () => {
    setMode(mode === 'login' ? 'signup' : 'login')
    setError(null)
    setFieldErrors({})
  }

  return (
    <Container size="xs" my={40}>
      <Card withBorder shadow="sm" p="lg" radius="md">
        <Title order={2} mb="md" c="red">
          {totpChallenge ? 'Two-factor verification' : mode === 'login' ? 'Login' : 'Create Account'}
        </Title>

        {error && (
          <Alert color="red" mb="md" withCloseButton onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <form
          noValidate
          onSubmit={(e) => {
            e.preventDefault()
            if (totpChallenge) {
              handleTotp()
            } else if (mode === 'login') {
              handleLogin()
            } else {
              handleSignup()
            }
          }}
        >
          <Stack>
            {totpChallenge ? (
              <>
                <Text size="sm" c="dimmed">
                  Signed in as <b>{email}</b>. Enter the 6-digit code from your authenticator app.
                </Text>
                {useRecoveryCode ? (
                  <TextInput
                    label="Recovery code"
                    placeholder="XXXX-XXXX-XXXX"
                    required
                    value={recoveryCode}
                    onChange={(e) => setRecoveryCode(e.currentTarget.value)}
                    autoComplete="one-time-code"
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
                  />
                )}
                <Button type="submit" loading={loading} fullWidth color="red">
                  Verify
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
              </>
            ) : (
              <>
                {mode === 'signup' && (
                  <TextInput
                    label="Name"
                    required
                    value={name}
                    error={fieldErrors.name}
                    onChange={(e) => setName(e.currentTarget.value)}
                  />
                )}

                <TextInput
                  label="Email"
                  type="email"
                  required
                  value={email}
                  error={fieldErrors.email}
                  onChange={(e) => setEmail(e.currentTarget.value)}
                  onBlur={() => setEmail((v) => normalizeEmail(v))}
                />

                <PasswordInput
                  label="Password"
                  required
                  value={password}
                  error={fieldErrors.password}
                  onChange={(e) => setPassword(e.currentTarget.value)}
                />

                {mode === 'signup' && (
                  <>
                    <PasswordInput
                      label="Confirm Password"
                      required
                      value={passwordConfirm}
                      error={fieldErrors.passwordConfirm}
                      onChange={(e) => setPasswordConfirm(e.currentTarget.value)}
                    />

                    <Text size="xs" c="dimmed" mt={-4}>
                      Password must be at least 8 characters and include at least one uppercase letter, one lowercase letter, and one number. Special characters (!@#$%^&*) are optional.
                    </Text>
                  </>
                )}

                <Button type="submit" loading={loading} fullWidth color="red">
                  {mode === 'login' ? 'Login' : 'Create Account'}
                </Button>

                <Group justify="center">
                  <Text size="sm">
                    {mode === 'login' ? "Don't have an account? " : 'Already have an account? '}
                    <Button type="button" variant="subtle" size="xs" onClick={toggleMode}>
                      {mode === 'login' ? 'Sign up' : 'Login'}
                    </Button>
                  </Text>
                </Group>
              </>
            )}
          </Stack>
        </form>
      </Card>
    </Container>
  )
}
