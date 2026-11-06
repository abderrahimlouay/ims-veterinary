/// <reference types="node" />
import { readFileSync, readdirSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * ADR-069 and the Phase 13 completion criterion: components use design tokens only, so both themes work everywhere.
 * Fails on a hex colour, an rgb()/hsl() literal (`rgb(var(--token))` is allowed) or a Tailwind palette class in the
 * source. Only `tokens.css` defines colours.
 */
const SRC = resolve(process.cwd(), 'src')
const PALETTE =
  'slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose|black|white'
const UTILITIES = 'bg|text|border|ring|outline|fill|stroke|from|to|via|divide|placeholder|accent|caret|decoration|shadow'
const RULES: [string, RegExp][] = [
  ['hex colour', /#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?(?:[0-9a-fA-F]{2})?\b/],
  ['colour function literal', /\b(?:rgba?|hsla?)\((?!\s*var\()/],
  ['Tailwind palette class', new RegExp(`\\b(?:${UTILITIES})-(?:${PALETTE})(?:-\\d{2,3})?\\b`)],
]

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      return sources(path)
    }
    return /\.(tsx?|css)$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) && entry.name !== 'tokens.css'
      ? [path]
      : []
  })
}

describe('no hard-coded colours (ADR-069)', () => {
  it('finds none in the components, pages and styles', () => {
    const found: string[] = []
    for (const file of sources(SRC)) {
      readFileSync(file, 'utf8')
        .split('\n')
        .forEach((line, index) => {
          for (const [rule, pattern] of RULES) {
            const match = pattern.exec(line)
            if (match) {
              found.push(`${relative(SRC, file)}:${index + 1} ${rule} ${match[0]}`)
            }
          }
        })
    }
    expect(found).toEqual([])
  })

  it('recognises each kind of hard-coded colour', () => {
    const hits = (line: string) => RULES.filter(([, p]) => p.test(line)).map(([rule]) => rule)
    expect(hits('className="text-red-600"')).toEqual(['Tailwind palette class'])
    expect(hits("style={{ color: '#c02626' }}")).toEqual(['hex colour'])
    expect(hits('fill: rgb(192 38 38);')).toEqual(['colour function literal'])
    expect(hits('background: rgb(var(--bg));')).toEqual([])
    expect(hits('className="bg-surface text-ink border-control"')).toEqual([])
  })
})
