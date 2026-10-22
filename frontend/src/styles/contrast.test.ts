/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * WCAG 2.1 AA over the token pairs the components use, in both themes (frontend.md §6, ADR-069):
 * 4.5:1 for text, 3:1 for control borders, focus ring and the invalid-field border (WCAG 1.4.11).
 * Alert borders (`ok/40`, `danger/40`) are decorative and not checked: the text and tint carry the meaning.
 */
type Rgb = readonly [number, number, number]
type Theme = Record<string, Rgb>

// Read as text: Vitest does not process CSS files, so a `?raw` import arrives empty.
const css = readFileSync(resolve(process.cwd(), 'src/styles/tokens.css'), 'utf8')

function block(selector: RegExp): Theme {
  const body = selector.exec(css)?.[1]
  if (body === undefined) {
    throw new Error(`token block not found: ${selector}`)
  }
  const theme: Theme = {}
  for (const [, name, r, g, b] of body.matchAll(/--([a-z-]+):\s*(\d+)\s+(\d+)\s+(\d+);/g)) {
    theme[name] = [Number(r), Number(g), Number(b)]
  }
  return theme
}

const LIGHT = block(/^:root\s*\{([^}]*)\}/m)
const DARK_SYSTEM = block(/:root:not\(\[data-theme='light'\]\)\s*\{([^}]*)\}/)
const DARK_FORCED = block(/:root\[data-theme='dark'\]\s*\{([^}]*)\}/)

function luminance([r, g, b]: Rgb): number {
  const channel = (v: number) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

function contrast(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** A token at `alpha` over a background, as `bg-ok/10` paints it. */
function over(colour: Rgb, alpha: number, background: Rgb): Rgb {
  return colour.map((c, i) => Math.round(c * alpha + background[i] * (1 - alpha))) as unknown as Rgb
}

const TINT = 0.1 // `bg-<tone>/10` of Alert and badges
const HOVER = 0.9 // `bg-brand/90` of the primary button

function token(theme: Theme, name: string): Rgb {
  const value = theme[name]
  if (value === undefined) {
    throw new Error(`token --${name} is missing`)
  }
  return value
}

/** [pair, measured ratio (computed when the test runs), required ratio] */
function pairs(raw: Theme): Array<[string, () => number, number]> {
  const theme = (name: string) => token(raw, name)
  const result: Array<[string, () => number, number]> = []
  for (const surface of ['bg', 'surface']) {
    for (const text of ['ink', 'mute', 'brand', 'ok', 'warn', 'danger']) {
      result.push([`${text} on ${surface}`, () => contrast(theme(text), theme(surface)), 4.5])
    }
    for (const tone of ['brand', 'ok', 'warn', 'danger']) {
      result.push([`${tone} on ${tone}/10 over ${surface}`, () => contrast(theme(tone), over(theme(tone), TINT, theme(surface))), 4.5])
    }
    for (const border of ['control', 'brand', 'danger']) {
      result.push([`${border} border vs ${surface}`, () => contrast(theme(border), theme(surface)), 3])
    }
  }
  result.push(['brand-ink on brand', () => contrast(theme('brand-ink'), theme('brand')), 4.5])
  result.push(['brand-ink on brand/90 over bg', () => contrast(theme('brand-ink'), over(theme('brand'), HOVER, theme('bg'))), 4.5])
  return result
}

describe('design-token contrast (WCAG 2.1 AA)', () => {
  it('defines the same tokens in every theme block', () => {
    expect(Object.keys(LIGHT).sort()).toEqual(Object.keys(DARK_SYSTEM).sort())
    expect(DARK_FORCED).toEqual(DARK_SYSTEM)
  })

  for (const [name, theme] of [['light', LIGHT], ['dark', DARK_SYSTEM]] as const) {
    it.each(pairs(theme))(`${name}: %s`, (_pair, ratio, minimum) => {
      expect(ratio()).toBeGreaterThanOrEqual(minimum)
    })
  }
})
