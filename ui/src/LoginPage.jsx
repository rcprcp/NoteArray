import { useEffect, useState } from 'react'
import { Container, Stack, Text, Button, PasswordInput, TextInput, Group, Card, Title, Alert } from '@mantine/core'
import { usersApi } from './api'

export default function LoginPage({ onLogin }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)
  const [mode, setMode] = useState('login') // 'login' or 'signup'
  const [name, setName] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [fieldErrors, setFieldErrors] = useState({})

  const handleLogin = async () => {
    if (!email.trim() || !password) {
      setFieldErrors({ email: !email.trim() ? 'Email is required' : '', password: !password ? 'Password is required' : '' })
      return
    }
    setFieldErrors({})
    setLoading(true)
    try {
      await usersApi.login(email, password)
      setError(null)
      await onLogin()
    } catch (e) {
      setError(e.message || 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  const handleSignup = async () => {
    const errs = {}
    if (!name.trim()) errs.name = 'Name is required'
    if (!email.trim()) errs.email = 'Email is required'
    if (!password) errs.password = 'Password is required'
    if (password !== passwordConfirm) errs.passwordConfirm = 'Passwords do not match'
    if (Object.keys(errs).length > 0) {
      setFieldErrors(errs)
      return
    }
    setFieldErrors({})
    setLoading(true)
    try {
      await usersApi.create({ name: name.trim(), email: email.trim(), password })
      // Auto-login after signup
      await usersApi.login(email, password)
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
        <Title order={2} mb="md" c="red">{mode === 'login' ? 'Login' : 'Create Account'}</Title>

        {error && (
          <Alert color="red" mb="md" withCloseButton onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <Stack>
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
          />

          <PasswordInput
            label="Password"
            required
            value={password}
            error={fieldErrors.password}
            onChange={(e) => setPassword(e.currentTarget.value)}
          />

          {mode === 'signup' && (
            <PasswordInput
              label="Confirm Password"
              required
              value={passwordConfirm}
              error={fieldErrors.passwordConfirm}
              onChange={(e) => setPasswordConfirm(e.currentTarget.value)}
            />
          )}

          <Button onClick={mode === 'login' ? handleLogin : handleSignup} loading={loading} fullWidth color="red">
            {mode === 'login' ? 'Login' : 'Create Account'}
          </Button>

          <Group justify="center">
            <Text size="sm">
              {mode === 'login' ? "Don't have an account? " : 'Already have an account? '}
              <Button variant="subtle" size="xs" onClick={toggleMode}>
                {mode === 'login' ? 'Sign up' : 'Login'}
              </Button>
            </Text>
          </Group>
        </Stack>
      </Card>
    </Container>
  )
}
