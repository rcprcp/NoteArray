// Compact, locale-aware formatting for timestamps in the notes list.
//
// "Adaptive" keeps the list narrow without hiding information:
//   - today            -> time only ("14:23" / "2:23 PM")
//   - earlier this year -> month and day ("Nov 13" / "13 Nov")
//   - older            -> month, day and year ("Nov 13, 2025" / "13 Nov 2025")
//
// The hour cycle can follow the browser locale ('auto') or be forced to a
// 12-hour ('h12') or 24-hour ('h23') clock. The full timestamp stays available
// in the element's title attribute.

export const HOUR_CYCLE_AUTO = 'auto'
export const HOUR_CYCLES = [HOUR_CYCLE_AUTO, 'h12', 'h23']

function toDate(value) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

function clockOptions(hourCycle) {
  return hourCycle === 'h12' || hourCycle === 'h23' ? { hourCycle } : {}
}

const timeFormatters = new Map()

function timeFormatter(locale, hourCycle) {
  const key = `${locale || ''}|${hourCycle || HOUR_CYCLE_AUTO}`
  let formatter = timeFormatters.get(key)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(locale, {
      hour: 'numeric',
      minute: '2-digit',
      ...clockOptions(hourCycle),
    })
    timeFormatters.set(key, formatter)
  }
  return formatter
}

/**
 * Format a note timestamp for the compact list view.
 *
 * @param {string|number|Date} value ISO timestamp (or anything `new Date()` accepts)
 * @param {{ hourCycle?: string, locale?: string, now?: Date }} [options]
 *   `now` is injectable so the day/year cut-offs are testable.
 */
export function formatNoteDate(value, { hourCycle = HOUR_CYCLE_AUTO, locale, now = new Date() } = {}) {
  const date = toDate(value)
  if (!date) return '—'

  const sameDay = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate()
  if (sameDay) {
    return timeFormatter(locale, hourCycle).format(date)
  }

  const sameYear = date.getFullYear() === now.getFullYear()
  return new Intl.DateTimeFormat(locale, {
    month: 'short',
    day: 'numeric',
    ...(sameYear ? {} : { year: 'numeric' }),
  }).format(date)
}

/** Full timestamp for tooltips and non-compact screens. */
export function formatFullTimestamp(value) {
  const date = toDate(value)
  return date ? date.toLocaleString() : '—'
}
