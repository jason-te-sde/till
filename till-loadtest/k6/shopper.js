// The load test's shoppers. docs/load-test.md is the protocol this follows: what a shopper does, what
// counts, and the targets; this file is how.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e SHOPPERS=50 -e RAMP=30s -e HOLD=1m shopper.js
//
// Every virtual user is one customer: its own cookie jar (k6 gives each one its own), its own address
// in CloudFront-Viewer-Address, and — the first time it checks out — its own identity from the
// stand-in provider. Only requests through the edge count; the stand-in provider's own answers are
// tagged `edge:no` and left out of every figure.

import http from 'k6/http'
import { sleep } from 'k6'
import exec from 'k6/execution'
import { Counter, Rate } from 'k6/metrics'

const BASE = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '')
const SHOPPERS = Number(__ENV.SHOPPERS || 50)
const RAMP = __ENV.RAMP || '30s'
const HOLD = __ENV.HOLD || '1m'
const RAMP_DOWN = __ENV.RAMP_DOWN || '30s'
const CHECKOUT_SHARE = Number(__ENV.CHECKOUT_SHARE || 0.1)
const CANCEL_SHARE = Number(__ENV.CANCEL_SHARE || 0.1)
// Two generators running at once must hand out different addresses and identities.
const OFFSET = Number(__ENV.SHOPPER_OFFSET || 0)

const CLASSES = ['page', 'session', 'catalogue', 'search', 'signin', 'order']

const unexpected = new Rate('unexpected')
const ordersPlaced = new Counter('orders_placed')
const ordersPaid = new Counter('orders_paid')
const ordersCancelled = new Counter('orders_cancelled')
const refusals = new Counter('refusals')
const signIns = new Counter('sign_ins')

function millis(duration) {
  const match = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(duration)
  if (!match) throw new Error(`not a duration: ${duration}`)
  return Number(match[1]) * { ms: 1, s: 1000, m: 60000, h: 3600000 }[match[2]]
}

const STEADY_FROM = millis(RAMP)
const STEADY_TO = millis(RAMP) + millis(HOLD)

// A threshold is what makes k6 keep a sub-metric for the summary, so the ones that are only there to
// be reported get a condition that always holds. The targets are the other two.
const reported = {}
for (const cls of CLASSES) reported[`http_req_duration{window:steady,class:${cls}}`] = ['max>=0']
for (const name of ['orders_placed', 'orders_paid', 'orders_cancelled', 'refusals', 'sign_ins']) {
  reported[`${name}{window:steady}`] = ['count>=0']
}

export const options = {
  scenarios: {
    shoppers: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: SHOPPERS },
        { duration: HOLD, target: SHOPPERS },
        { duration: RAMP_DOWN, target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    'http_req_duration{window:steady,edge:yes}': ['p(99)<1000'],
    'unexpected{window:steady}': ['rate<0.001'],
    'http_reqs{window:steady,edge:yes}': ['count>=0'],
    ...reported,
  },
  // A shopper keeps its cookies from one iteration to the next, as a browser does. k6's default is
  // a fresh jar every iteration: a shopper signed in on one would be anonymous on the next.
  noCookiesReset: true,
  // Bodies are read only where a shopper needs one; everything else is thrown away as it arrives, so
  // eight thousand shoppers cost the generator their requests and not their responses.
  discardResponseBodies: true,
  // No `url` tag: with every search a distinct URL, it would make every request its own time series.
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '2m',
}

// --- the catalogue, read once --------------------------------------------------------------------

export function setup() {
  const games = http.get(`${BASE}/api/games?size=48`, { responseType: 'text', tags: { edge: 'setup' } })
  const genres = http.get(`${BASE}/api/genres`, { responseType: 'text', tags: { edge: 'setup' } })
  if (games.status !== 200 || genres.status !== 200) {
    throw new Error(`the store is not answering: games ${games.status}, genres ${genres.status}`)
  }
  const items = games.json('items')
  // Search terms from the catalogue's own words, so most searches find something, as people's do.
  const words = new Set()
  for (const game of items) {
    for (const word of game.title.toLowerCase().split(/\s+/)) if (word.length >= 4) words.add(word)
    for (const tag of game.tags) words.add(tag)
  }
  return {
    // Part of every idempotency key, so a second run against the same database is new orders rather
    // than the first run's keys reused for different ones.
    run: Date.now().toString(36),
    // When the scenario starts, give or take the moment setup takes to return: the steady window is
    // measured from here, and the result says when it was so that other figures can be read for it.
    started: Date.now(),
    skus: items.map((game) => game.sku),
    genres: genres.json().map((genre) => genre.value),
    terms: [...words],
  }
}

// --- one shopper ---------------------------------------------------------------------------------

let shopper = null

function me() {
  if (shopper === null) {
    const n = exec.vu.idInTest + OFFSET
    shopper = {
      n,
      subject: `shopper-${n}`,
      arrived: false,
      signedIn: false,
      headers: {
        // 198.18.0.0/15 is set aside for benchmarking (RFC 2544): 131,072 addresses, one per shopper.
        'CloudFront-Viewer-Address': address(n),
        'User-Agent': 'till-loadtest',
        Accept: 'application/json',
        // As every browser does, so the edge compresses what it sends back as it would for a person.
        'Accept-Encoding': 'gzip, deflate, br',
      },
    }
  }
  return shopper
}

function address(n) {
  const host = (n % 131070) + 1
  return `198.${18 + (host >> 16)}.${(host >> 8) & 255}.${host & 255}:${40000 + (n % 20000)}`
}

export default function (catalogue) {
  const s = me()
  if (!s.arrived) {
    arrive()
    s.arrived = true
    think()
  }
  if (Math.random() < 0.6) {
    const genre = pick(catalogue.genres)
    get(`/api/games?genre=${encodeURIComponent(genre)}&page=0&size=24`, 'catalogue', '/api/games?genre')
  } else {
    const term = pick(catalogue.terms)
    get(`/api/games?q=${encodeURIComponent(term)}&page=0&size=24`, 'search', '/api/games?q')
  }
  think()
  get(`/api/games/${pick(catalogue.skus)}`, 'catalogue', '/api/games/{sku}')
  think()
  if (Math.random() < CHECKOUT_SHARE) checkout(catalogue)
}

function arrive() {
  get('/', 'page', '/', { Accept: 'text/html' })
  get('/api/me', 'session', '/api/me')
  get('/api/home', 'catalogue', '/api/home')
}

function checkout(catalogue) {
  const s = me()
  if (!s.signedIn && !signIn()) return
  // The storefront asks who it is once signed in, and that answer is where the CSRF cookie comes from.
  get('/api/me', 'session', '/api/me')
  const csrf = csrfToken()
  const lines = [{ sku: pick(catalogue.skus), quantity: 1 }]
  if (Math.random() < 0.3) {
    const second = pick(catalogue.skus)
    if (second !== lines[0].sku) lines.push({ sku: second, quantity: 1 })
  }
  const placed = send('POST', `${BASE}/api/orders`, 'order', '/api/orders', {
    body: JSON.stringify({ lines }),
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': csrf,
      'Idempotency-Key': `order-${catalogue.run}-${s.n}-${exec.vu.iterationInScenario}`,
    },
    expect: [201, 409],
    read: true,
  })
  if (placed.status === 409) {
    refusals.add(1, { window: phase() })
    return
  }
  if (placed.status !== 201) return
  ordersPlaced.add(1, { window: phase() })
  const id = placed.json('id')
  think()
  const cancel = Math.random() < CANCEL_SHARE
  const action = cancel ? 'cancel' : 'pay'
  const done = send('POST', `${BASE}/api/orders/${id}/${action}`, 'order', `/api/orders/{id}/${action}`, {
    headers: { 'X-XSRF-TOKEN': csrf, 'Idempotency-Key': `${action}-${id}` },
  })
  if (done.status === 200) (cancel ? ordersCancelled : ordersPaid).add(1, { window: phase() })
}

// The whole OpenID Connect round trip, as a browser does it: the store's redirect to the provider,
// the provider's approval, and the store redeeming the code on the way back.
function signIn() {
  const s = me()
  const start = send('GET', `${BASE}/oauth2/authorization/idp`, 'signin', '/oauth2/authorization/idp', {
    expect: [302],
    redirects: 0,
  })
  const toProvider = header(start, 'Location')
  if (!toProvider) return false
  const approved = http.get(`${toProvider}&login_hint=${s.subject}`, {
    redirects: 0,
    responseType: 'none',
    tags: { edge: 'no', class: 'provider', name: 'stand-in /authorize', window: phase() },
  })
  const callback = header(approved, 'Location')
  if (approved.status !== 302 || !callback || callback.includes('error=')) {
    unexpected.add(true, { window: phase(), class: 'signin' })
    return false
  }
  const back = send('GET', callback, 'signin', '/login/oauth2/code/idp', { expect: [302], redirects: 0 })
  const landed = header(back, 'Location') || ''
  if (back.status !== 302 || landed.includes('signin=failed')) {
    unexpected.add(true, { window: phase(), class: 'signin' })
    return false
  }
  s.signedIn = true
  signIns.add(1, { window: phase() })
  return true
}

// --- requests ------------------------------------------------------------------------------------

function get(path, cls, name, headers = {}) {
  return send('GET', `${BASE}${path}`, cls, name, { headers })
}

// Every request through the edge goes through here, so every one carries the shopper's address and
// is counted, and a response other than the expected one is counted against the error target.
function send(method, url, cls, name, { body = null, headers = {}, expect = [200], redirects, read = false } = {}) {
  const window = phase()
  const params = {
    headers: Object.assign({}, me().headers, headers),
    tags: { edge: 'yes', class: cls, name, window },
    responseType: read ? 'text' : 'none',
  }
  if (redirects !== undefined) params.redirects = redirects
  const response = http.request(method, url, body, params)
  const surprising = !expect.includes(response.status)
  unexpected.add(surprising, { window, class: cls })
  if (surprising) report(method, name, response)
  return response
}

// A few of each shopper's surprises, so a run that misses the error target says why; never more than
// three a shopper, so a run that goes badly wrong does not bury the load generator in its own log.
let surprises = 0

function report(method, name, response) {
  if (surprises++ >= 3) return
  const detail = response.error ? ` ${response.error_code} ${response.error}` : ''
  console.warn(`unexpected ${response.status} for ${method} ${name}${detail}`)
}

function header(response, name) {
  return response.headers[name] || response.headers[name.toLowerCase()] || null
}

function csrfToken() {
  const values = http.cookieJar().cookiesForURL(`${BASE}/`)['XSRF-TOKEN']
  return values && values.length ? decodeURIComponent(values[0]) : ''
}

// Which part of the run a request falls in. Only `steady` counts.
function phase() {
  const elapsed = Date.now() - exec.scenario.startTime
  return elapsed < STEADY_FROM ? 'ramp' : elapsed < STEADY_TO ? 'steady' : 'down'
}

function think() {
  sleep(1 + Math.random() * 3)
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)]
}

// --- the result ----------------------------------------------------------------------------------

export function handleSummary(data) {
  const m = (name) => data.metrics[name]
  const seconds = millis(HOLD) / 1000
  const duration = m('http_req_duration{window:steady,edge:yes}')
  const count = (name) => (m(`${name}{window:steady}`) ? m(`${name}{window:steady}`).values.count : 0)
  const perClass = {}
  for (const cls of CLASSES) {
    const trend = m(`http_req_duration{window:steady,class:${cls}}`)
    if (trend && trend.values.max > 0) perClass[cls] = round(trend.values['p(99)'])
  }
  const requests = m('http_reqs{window:steady,edge:yes}')
  const started = data.setup_data ? data.setup_data.started : null
  const result = {
    window: started
      ? { from: new Date(started + STEADY_FROM).toISOString(), to: new Date(started + STEADY_TO).toISOString() }
      : null,
    shoppers: SHOPPERS,
    concurrent_max: m('vus') ? m('vus').values.max : 0,
    steady_seconds: seconds,
    requests: requests ? requests.values.count : 0,
    requests_per_second: round((requests ? requests.values.count : 0) / seconds),
    latency_ms: duration
      ? {
          p50: round(duration.values.med),
          p95: round(duration.values['p(95)']),
          p99: round(duration.values['p(99)']),
          max: round(duration.values.max),
        }
      : null,
    p99_ms_by_class: perClass,
    unexpected_rate: m('unexpected{window:steady}') ? m('unexpected{window:steady}').values.rate : null,
    per_second: {
      orders_placed: round(count('orders_placed') / seconds),
      orders_paid: round(count('orders_paid') / seconds),
      orders_cancelled: round(count('orders_cancelled') / seconds),
      refusals: round(count('refusals') / seconds),
      sign_ins: round(count('sign_ins') / seconds),
    },
    targets: {
      p99_under_1s: passed(m('http_req_duration{window:steady,edge:yes}')),
      errors_under_0_1_percent: passed(m('unexpected{window:steady}')),
    },
  }
  return { stdout: `${describe(result)}\nRESULT ${JSON.stringify(result)}\n` }
}

function passed(metric) {
  if (!metric || !metric.thresholds) return null
  return Object.values(metric.thresholds).every((threshold) => threshold.ok)
}

function round(value) {
  return Math.round(value * 10) / 10
}

function describe(r) {
  const lines = [
    `shoppers            ${r.concurrent_max} at once (asked for ${r.shoppers})`,
    `steady window       ${r.steady_seconds} s, ${r.requests} requests through the edge`,
    `throughput          ${r.requests_per_second} requests/s`,
    r.latency_ms
      ? `latency             p50 ${r.latency_ms.p50} ms, p95 ${r.latency_ms.p95} ms, p99 ${r.latency_ms.p99} ms, max ${r.latency_ms.max} ms`
      : 'latency             no requests in the steady window',
    `p99 by class        ${Object.entries(r.p99_ms_by_class).map(([cls, p99]) => `${cls} ${p99} ms`).join(', ')}`,
    `unexpected          ${r.unexpected_rate === null ? 'n/a' : (r.unexpected_rate * 100).toFixed(3) + '%'}`,
    `per second          ${Object.entries(r.per_second).map(([what, rate]) => `${what.replace('_', ' ')} ${rate}`).join(', ')}`,
    `targets             p99 < 1 s: ${verdict(r.targets.p99_under_1s)}; errors < 0.1%: ${verdict(r.targets.errors_under_0_1_percent)}`,
  ]
  return lines.join('\n')
}

function verdict(ok) {
  return ok === null ? 'n/a' : ok ? 'met' : 'MISSED'
}
