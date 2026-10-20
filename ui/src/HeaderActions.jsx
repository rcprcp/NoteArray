import { Fragment, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ActionIcon, Box, Button, Group, Menu } from '@mantine/core'
import { IconCheck, IconChevronDown, IconDots } from '@tabler/icons-react'

// Gap between header items, kept in one place so measurement matches layout.
const ITEM_GAP = 8

// Width reserved for the overflow (…) button once anything is pushed off.
const OVERFLOW_WIDTH = 40

// The header collapses whatever does not fit into a right-hand overflow menu.
// Items are pushed off right-to-left: the rightmost item goes first, then the
// next one to its left, and so on. The split is measured at runtime, so it
// adapts to the viewport, zoom level and label lengths rather than a fixed
// breakpoint.
export default function HeaderActions({ items, userMenu, availableWidth = 0, onOverflowChange }) {
  const rowRef = useRef(null)
  const measureRef = useRef(null)
  const [visibleCount, setVisibleCount] = useState(items.length + 1)
  const [menuOpened, setMenuOpened] = useState(false)

  const measure = useCallback(() => {
    const row = rowRef.current
    const measurer = measureRef.current
    if (!row || !measurer) return

    const available = availableWidth > 0 ? availableWidth : row.clientWidth
    const children = Array.from(measurer.children)
    // Candidates: every action, then the user menu last (rightmost).
    const widths = children
      .slice(0, items.length + 1)
      .map((el) => el.getBoundingClientRect().width)
    if (widths.length === 0) return

    const totalWidth = (count) => {
      let sum = 0
      for (let i = 0; i < count; i += 1) sum += widths[i] + (i > 0 ? ITEM_GAP : 0)
      return sum
    }

    // Short-circuit: everything (including the user menu) fits, show it all.
    if (totalWidth(widths.length) <= available) {
      setVisibleCount(widths.length)
      return
    }

    // Reserve space for the overflow button and find how many items fit.
    let used = 0
    let cut = 0
    for (let i = 0; i < widths.length; i += 1) {
      const next = used + widths[i] + (i > 0 ? ITEM_GAP : 0)
      if (next + ITEM_GAP + OVERFLOW_WIDTH > available) break
      used = next
      cut = i + 1
    }
    setVisibleCount(cut)
  }, [items.length, availableWidth])

  useLayoutEffect(() => {
    measure()
    const row = rowRef.current
    if (!row || typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(() => measure())
    ro.observe(row)
    return () => ro.disconnect()
  }, [measure])

  useEffect(() => {
    const onResize = () => measure()
    window.addEventListener('resize', onResize)
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(() => measure()).catch(() => {})
    }
    return () => window.removeEventListener('resize', onResize)
  }, [measure])

  const showUserMenu = visibleCount > items.length
  const visibleActions = items.slice(0, Math.min(visibleCount, items.length))
  const overflowActions = items.slice(visibleActions.length)
  const overflowUser = !showUserMenu
  const hasOverflow = overflowActions.length > 0 || overflowUser

  useEffect(() => {
    onOverflowChange?.(hasOverflow)
  }, [hasOverflow, onOverflowChange])

  // Close the overflow menu if it disappears (e.g. the window was widened).
  useEffect(() => {
    if (!hasOverflow) setMenuOpened(false)
  }, [hasOverflow])

  return (
    <>
      <Group
        ref={rowRef}
        gap={ITEM_GAP}
        wrap="nowrap"
        justify="space-between"
        style={{ minWidth: 0, flex: 1 }}
      >
        {/* Left-justified action buttons */}
        <Group gap={ITEM_GAP} wrap="nowrap" style={{ minWidth: 0 }}>
          {visibleActions.map((item) =>
            item.menu ? (
              <Menu key={item.key} shadow="md" width={200} position="bottom-start">
                <Menu.Target>
                  <Button
                    variant={item.active && !item.disabled ? 'filled' : 'light'}
                    leftSection={item.icon ? <item.icon size={16} /> : null}
                    rightSection={<IconChevronDown size={14} />}
                    disabled={item.disabled}
                    style={{ flex: '0 0 auto' }}
                  >
                    {item.label}
                  </Button>
                </Menu.Target>
                <Menu.Dropdown>
                  {item.menu.map((sub) => (
                    <Menu.Item
                      key={sub.key}
                      rightSection={sub.active ? <IconCheck size={16} /> : null}
                      onClick={sub.onClick}
                    >
                      {sub.label}
                    </Menu.Item>
                  ))}
                </Menu.Dropdown>
              </Menu>
            ) : (
              <Button
                key={item.key}
                variant={item.active && !item.disabled ? 'filled' : 'light'}
                leftSection={item.icon ? <item.icon size={16} /> : null}
                disabled={item.disabled}
                onClick={item.onClick}
                aria-expanded={item.toggle ? item.active : undefined}
                style={{ flex: '0 0 auto' }}
              >
                {item.label}
              </Button>
            ),
          )}
        </Group>

        {/* Right-justified avatar and overflow (…) menu */}
        <Group gap={ITEM_GAP} wrap="nowrap" justify="flex-end" style={{ flex: '0 0 auto', marginLeft: 'auto' }}>
          {showUserMenu && userMenu?.element}

          {hasOverflow && (
            <Box style={{ flex: '0 0 auto', width: OVERFLOW_WIDTH, display: 'flex', justifyContent: 'flex-end' }}>
              <Menu shadow="md" width={240} position="bottom-end" opened={menuOpened} onChange={setMenuOpened}>
                <Menu.Target>
                  <ActionIcon variant="light" size="lg" aria-label="More actions">
                    <IconDots size={18} />
                  </ActionIcon>
                </Menu.Target>
                <Menu.Dropdown>
                  {overflowActions.map((item) =>
                    item.menu ? (
                      <Fragment key={item.key}>
                        <Menu.Divider />
                        <Menu.Label>{item.label}</Menu.Label>
                        {item.menu.map((sub) => (
                          <Menu.Item
                            key={sub.key}
                            rightSection={sub.active ? <IconCheck size={16} /> : null}
                            onClick={() => {
                              sub.onClick?.()
                              setMenuOpened(false)
                            }}
                          >
                            {sub.label}
                          </Menu.Item>
                        ))}
                      </Fragment>
                    ) : (
                      <Menu.Item
                        key={item.key}
                        disabled={item.disabled}
                        leftSection={item.icon ? <item.icon size={16} /> : null}
                        rightSection={item.active && !item.disabled ? <IconCheck size={16} /> : null}
                        onClick={() => {
                          if (item.disabled) return
                          item.onClick?.()
                          setMenuOpened(false)
                        }}
                      >
                        {item.label}
                      </Menu.Item>
                    ),
                  )}
                  {overflowUser && userMenu?.menuItems?.()}
                </Menu.Dropdown>
              </Menu>
            </Box>
          )}
        </Group>
      </Group>

      {/* Hidden row used purely for measuring natural item widths. */}
      <Group
        ref={measureRef}
        gap={ITEM_GAP}
        wrap="nowrap"
        aria-hidden="true"
        style={{
          position: 'absolute',
          top: 0,
          left: 0,
          height: 0,
          overflow: 'hidden',
          visibility: 'hidden',
          pointerEvents: 'none',
        }}
      >
        {items.map((item) => (
          <Button
            key={item.key}
            variant={item.active ? 'filled' : 'light'}
            leftSection={item.icon ? <item.icon size={16} /> : null}
            rightSection={item.menu ? <IconChevronDown size={14} /> : null}
            style={{ flex: '0 0 auto' }}
            tabIndex={-1}
            aria-hidden="true"
          >
            {item.label}
          </Button>
        ))}
        {userMenu?.measureElement}
      </Group>
    </>
  )
}
