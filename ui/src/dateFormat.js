// Formatting for timestamps in the notes list.
//
// The default "adaptive" preset keeps the list narrow without hiding
// information:
//   - last 3 days       -> relative ("now", "5m ago", "3h ago", "2d ago")
//   - earlier this year -> month and day ("Nov 13" / "13 Nov")
//   - older             -> month, day and year ("Nov 13, 2025" / "13 Nov 2025")
//
// Other presets pin a fixed shape, including date-first variants for people
// whose browser locale would otherwise show the month first. The hour cycle can
// follow the browser locale ('auto') or be forced to 12-hour ('h12') or 24-hour
// ('h23'). The full timestamp stays available in the element's title attribute.

export const HOUR_CYCLE_AUTO = 'auto'
export const HOUR_CYCLES = [HOUR_CYCLE_AUTO, 'h12', 'h23']

export const DEFAULT_DATE_FORMAT = 'adaptive'
export const DATE_FORMATS = [
  'adaptive',
  'day_month',
  'month_day',
  'mdy_slash',
  'dmy_slash',
  'iso',
  'relative',
  'full',
]

function toDate(value) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

function clockOptions(hourCycle) {
  return hourCycle === 'h12' || hourCycle === 'h23' ? { hourCycle } : {}
}

const MINUTE_MS = 60 * 1000
const HOUR_MS = 60 * MINUTE_MS
const DAY_MS = 24 * HOUR_MS
// Smart dates stay relative for the first three days, then switch to the
// compact calendar form. The explicit Relative preset keeps a week of context.
const ADAPTIVE_RELATIVE_LIMIT_MS = 3 * DAY_MS
const RELATIVE_PRESET_LIMIT_MS = 7 * DAY_MS

/** Relative label while the timestamp is inside the window, otherwise null. */
function elapsedText(date, now, limitMs) {
  const elapsedMs = now.getTime() - date.getTime()
  if (elapsedMs < 0 || elapsedMs >= limitMs) return null
  if (elapsedMs < MINUTE_MS) return 'now'
  if (elapsedMs < HOUR_MS) return `${Math.floor(elapsedMs / MINUTE_MS)}m ago`
  if (elapsedMs < DAY_MS) return `${Math.floor(elapsedMs / HOUR_MS)}h ago`
  return `${Math.floor(elapsedMs / DAY_MS)}d ago`
}

// Intl formatter construction is comparatively expensive and the notes list
// renders hundreds of timestamps per pass, so cache by preset/locale.
const formatters = new Map()

function formatter(locale, options, key) {
  const cacheKey = `${key}|${locale || ''}`
  let instance = formatters.get(cacheKey)
  if (!instance) {
    instance = new Intl.DateTimeFormat(locale, options)
    formatters.set(cacheKey, instance)
  }
  return instance
}

function timeText(date, locale, hourCycle) {
  return formatter(
    locale,
    { hour: 'numeric', minute: '2-digit', ...clockOptions(hourCycle) },
    `time|${hourCycle}`,
  ).format(date)
}

function dateParts(date, locale, options, key) {
  const parts = {}
  for (const part of formatter(locale, options, key).formatToParts(date)) {
    if (part.type !== 'literal') parts[part.type] = part.value
  }
  return parts
}

function sameYear(date, now) {
  return date.getFullYear() === now.getFullYear()
}

function adaptiveText(date, locale, now) {
  const recent = elapsedText(date, now, ADAPTIVE_RELATIVE_LIMIT_MS)
  if (recent) return recent
  const showYear = !sameYear(date, now)
  return formatter(
    locale,
    { month: 'short', day: 'numeric', ...(showYear ? { year: 'numeric' } : {}) },
    `adaptive|${showYear}`,
  ).format(date)
}

function dayMonthText(date, locale, now) {
  const showYear = !sameYear(date, now)
  const parts = dateParts(
    date,
    locale,
    { day: 'numeric', month: 'short', ...(showYear ? { year: 'numeric' } : {}) },
    `dayMonth|${showYear}`,
  )
  return `${parts.day} ${parts.month}${showYear ? ` ${parts.year}` : ''}`
}

function monthDayText(date, locale, now) {
  const showYear = !sameYear(date, now)
  const parts = dateParts(
    date,
    locale,
    { month: 'short', day: 'numeric', ...(showYear ? { year: 'numeric' } : {}) },
    `monthDay|${showYear}`,
  )
  return `${parts.month} ${parts.day}${showYear ? ` ${parts.year}` : ''}`
}

function shortMonth(date, locale) {
  const name = dateParts(date, locale, { month: 'short' }, 'shortMonth').month || ''
  // Slash formats read better without a locale-specific trailing period
  // (e.g. German "Jan." -> "Jan").
  return name.replace(/\.$/, '')
}

function slashText(date, locale, hourCycle, dayFirst) {
  const month = shortMonth(date, locale)
  const year = String(date.getFullYear()).slice(-2)
  const day = date.getDate()
  const datePart = dayFirst ? `${day}/${month}/${year}` : `${month}/${day}/${year}`
  return `${datePart} ${timeText(date, locale, hourCycle)}`
}

function isoText(date, locale, hourCycle) {
  const pad = (n) => String(n).padStart(2, '0')
  const datePart = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  return `${datePart} ${timeText(date, locale, hourCycle)}`
}

function relativeText(date, locale, hourCycle, now) {
  // Future timestamps (clock skew) and notes older than the window fall back
  // to the adaptive date so the entry still carries an absolute reference.
  return elapsedText(date, now, RELATIVE_PRESET_LIMIT_MS)
    ?? adaptiveText(date, locale, now)
}

function fullText(date, locale, hourCycle) {
  return formatter(
    locale,
    { dateStyle: 'medium', timeStyle: 'medium', ...clockOptions(hourCycle) },
    `full|${hourCycle}`,
  ).format(date)
}

/**
 * Format a note timestamp for the compact list view.
 *
 * @param {string|number|Date} value ISO timestamp (or anything `new Date()` accepts)
 * @param {{ preset?: string, hourCycle?: string, locale?: string, now?: Date }} [options]
 *   `now` is injectable so the day/year/relative cut-offs are testable.
 */
export function formatNoteDate(
  value,
  {
    preset = DEFAULT_DATE_FORMAT,
    hourCycle = HOUR_CYCLE_AUTO,
    locale,
    now = new Date(),
  } = {},
) {
  const date = toDate(value)
  if (!date) return '—'

  switch (preset) {
    case 'day_month':
      return elapsedText(date, now, ADAPTIVE_RELATIVE_LIMIT_MS) ?? dayMonthText(date, locale, now)
    case 'month_day':
      return elapsedText(date, now, ADAPTIVE_RELATIVE_LIMIT_MS) ?? monthDayText(date, locale, now)
    case 'mdy_slash':
      return slashText(date, locale, hourCycle, false)
    case 'dmy_slash':
      return slashText(date, locale, hourCycle, true)
    case 'iso':
      return isoText(date, locale, hourCycle)
    case 'relative':
      return relativeText(date, locale, hourCycle, now)
    case 'full':
      return fullText(date, locale, hourCycle)
    case 'adaptive':
    default:
      return adaptiveText(date, locale, now)
  }
}

/** Full timestamp for tooltips and non-compact screens, honoring the clock preference. */
export function formatFullTimestamp(value, hourCycle = HOUR_CYCLE_AUTO) {
  const date = toDate(value)
  if (!date) return '—'
  return formatter(
    undefined,
    { dateStyle: 'medium', timeStyle: 'medium', ...clockOptions(hourCycle) },
    `fullTooltip|${hourCycle}`,
  ).format(date)
}
