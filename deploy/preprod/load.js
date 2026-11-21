import http from 'k6/http'
import exec from 'k6/execution'
import { Counter, Rate } from 'k6/metrics'
import { check } from 'k6'

const fixture = JSON.parse(open(__ENV.CARE_LOAD_FIXTURE || '/load.json'))
const origin = __ENV.CARE_LOAD_ORIGIN || 'https://web:8443'
if (origin !== 'https://web:8443' && !/^https:\/\/127\.0\.0\.1:\d+$/.test(origin)) {
  throw new Error('Load tests require the isolated Compose gateway')
}
const failures = new Rate('care_unexpected_errors')
const conflicts = new Counter('care_conflicts')
const successes = new Counter('care_successes')
const clients = fixture.sessions.length
const appointments = new Map()

export const options = {
  scenarios: {
    mixed: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.CARE_LOAD_RPS || 100),
      timeUnit: '1s',
      duration: __ENV.CARE_LOAD_DURATION || '30m',
      preAllocatedVUs: Math.min(clients, 100),
      maxVUs: clients,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<=500', 'p(99)<=1000'],
    care_unexpected_errors: ['rate<0.005'],
    dropped_iterations: ['count==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
}

export default function () {
  const iteration = exec.scenario.iterationInTest
  const index = iteration % clients
  const session = fixture.sessions[index]
  const cycle = Math.floor(iteration / clients)
  const choice = (index + cycle) % 20
  const headers = { Cookie: session.cookie, Accept: 'application/json' }
  let method = 'GET'
  let name
  let path
  let data
  if (choice < 7) {
    name = 'schedule'
    path = '/schedule-slots?departmentId=' + fixture.department
  } else if (choice < 10) {
    name = 'appointments.list'
    path = '/appointments'
  } else if (choice < 13) {
    name = 'followups.list'
    path = '/followups'
  } else if (choice < 15) {
    name = 'inbox'
    path = '/inbox'
  } else {
    method = 'POST'
    headers[session.header] = session.csrf
    headers['Content-Type'] = 'application/json'
    headers['Idempotency-Key'] = fixture.run + '-' + iteration
    const active = appointments.get(index)
    const next = fixture.slots[(index * 7 + cycle) % fixture.slots.length]
    if (active && choice < 17) {
      name = 'appointments.cancel'
      path = '/appointments/' + active.id + '/cancel'
    } else if (active && next !== active.slotId) {
      name = 'appointments.reschedule'
      path = '/appointments/' + active.id + '/reschedule'
      data = { slotId: next }
    } else if (!active) {
      name = 'appointments.book'
      path = '/appointments'
      data = { slotId: next }
    } else {
      name = 'appointments.cancel'
      path = '/appointments/' + active.id + '/cancel'
    }
  }
  if (choice === 14 && cycle % 8 === 0) {
    method = 'POST'
    name = 'followups.feedback'
    path = '/followups/' + fixture.tasks[index] + '/actions'
    headers[session.header] = session.csrf
    headers['Content-Type'] = 'application/json'
    data = { action: 'FEEDBACK', version: 1, note: 'Synthetic performance check' }
  }
  const response = http.request(method, origin + '/api/v1' + path, data && JSON.stringify(data), {
    headers,
    tags: { name },
    redirects: 0,
    timeout: '5s',
  })
  const ok = response.status >= 200 && response.status < 300
  const expected = method === 'POST' && response.status === 409
  failures.add(!ok && !expected, { operation: name })
  check(response, { 'expected status': () => ok || expected })
  if (expected) conflicts.add(1, { operation: name })
  if (ok) {
    successes.add(1, { operation: name })
    if (name === 'appointments.cancel') appointments.delete(index)
    if (name === 'appointments.book' || name === 'appointments.reschedule') {
      appointments.set(index, { id: response.json('id'), slotId: data.slotId })
    }
  }
}

export function handleSummary(data) {
  const result = {}
  for (const name of ['http_reqs', 'http_req_duration', 'care_unexpected_errors', 'care_conflicts',
    'care_successes', 'dropped_iterations', 'iterations', 'vus_max']) {
    const metric = data.metrics[name]
    if (metric) result[name] = { ...metric.values, thresholds: metric.thresholds }
  }
  return {
    stdout: JSON.stringify(result, null, 2) + '\n',
    [__ENV.CARE_LOAD_REPORT || '/evidence/load-report.json']: JSON.stringify(result, null, 2) + '\n',
  }
}
