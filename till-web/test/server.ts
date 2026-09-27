import { setupServer } from 'msw/node'

/** One interceptor for the whole run; each test installs the handlers it needs. */
export const server = setupServer()
