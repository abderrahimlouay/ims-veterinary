import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'

/**
 * testing.md §8: the first full-app render in a fresh worker takes ~0.5 s and over 1 s when the CPU is shared
 * (a Maven build alongside), beyond Testing Library's 1 s default for findBy/waitFor.
 */
configure({ asyncUtilTimeout: 3000 })

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
})
afterAll(() => server.close())
