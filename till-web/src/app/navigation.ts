/**
 * Leaving the SPA for another site — the identity provider's logout page.
 *
 * A module of its own so a test can replace it: a full-page navigation is the one browser behaviour
 * a test environment cannot perform, and the assertion worth making is where the page was sent.
 *
 * @param url where to go
 */
export function leaveFor(url: string): void {
  window.location.assign(url)
}
