import { setupServer } from 'msw/node'

/** MSW server for component and API tests; tests add handlers with `server.use(...)`. */
export const server = setupServer()
