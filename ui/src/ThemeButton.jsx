import { Button, Modal, SimpleGrid } from '@mantine/core'

// Mantine color palettes selectable in the UI. Must stay in sync with
// THEME_COLORS in src/main/java/com/notearray/resource/UserResource.java (server-side validation).
export const THEME_COLORS = [
  'dark', 'gray', 'red', 'pink', 'grape', 'violet', 'indigo',
  'blue', 'cyan', 'teal', 'green', 'lime', 'yellow', 'orange',
]

// Controlled theme picker modal. `value` is the active color; `onThemeChange`
// persists/applies a new one.
export function ThemePicker({ opened, onClose, value, onThemeChange }) {
  const select = (color) => {
    onThemeChange(color)
    onClose()
  }

  return (
    <Modal opened={opened} onClose={onClose} title="Select color theme">
      <SimpleGrid cols={3} spacing="sm">
        {THEME_COLORS.map((color) => (
          <Button
            key={color}
            color={color}
            variant={value === color ? 'filled' : 'light'}
            onClick={() => select(color)}
            styles={{ root: { textTransform: 'capitalize' } }}
          >
            {color}
          </Button>
        ))}
      </SimpleGrid>
    </Modal>
  )
}