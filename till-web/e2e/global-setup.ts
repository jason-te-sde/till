import type { FullConfig } from '@playwright/test'

/**
 * Stops the run at once, with the instruction, when the stack is not up — rather than letting every
 * test time out one by one with an error about a locator.
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const base = config.projects[0]?.use.baseURL ?? 'http://localhost:8080'
  try {
    const session = await fetch(`${base}/api/me`)
    if (!session.ok) {
      throw new Error(`${base}/api/me answered ${String(session.status)}`)
    }
  } catch (error) {
    throw new Error(
      `the stack is not answering at ${base} (${error instanceof Error ? error.message : String(error)}).\n` +
        'Start it from the repository root first:  docker compose up -d --wait',
      { cause: error },
    )
  }
}
