/**
 * The CSRF token, as the store hands it to this page.
 *
 * The store keeps the session in a cookie script cannot read, and so has to stop other sites from
 * riding on it: every write must also carry an `X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie.
 * That cookie *is* readable, on purpose — only a script running on this origin can read it and copy
 * it into a header, which is exactly what a page on another origin cannot do.
 *
 * @param cookies the cookie string to read; the document's by default
 * @returns the token, or undefined before the store has issued one
 */
export function csrfToken(cookies: string = document.cookie): string | undefined {
  for (const cookie of cookies.split(';')) {
    const [name, ...value] = cookie.trim().split('=')
    if (name === 'XSRF-TOKEN') {
      return decodeURIComponent(value.join('='))
    }
  }
  return undefined
}
