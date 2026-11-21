import { execFileSync } from 'node:child_process'

interface Container {
  Config: { Labels: Record<string, string> }
  State: { Running: boolean }
  NetworkSettings: { Ports: Record<string, { HostIp: string; HostPort: string }[] | null> }
}

const projectLabel = 'com.docker.compose.project'
const serviceLabel = 'com.docker.compose.service'
const testLabel = 'app.carecontinuo.e2e'

function docker(...args: string[]) {
  return execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}

export default function verifyEnvironment() {
  const url = new URL(process.env.CARE_WEB_URL!)
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.href !== url.origin + '/') {
    throw new Error('CARE_WEB_URL must be a loopback HTTP origin with an explicit port.')
  }

  const [db] = JSON.parse(docker('inspect', '--type=container', '--', process.env.CARE_E2E_DB_CONTAINER!)) as Container[]
  const labels = db.Config.Labels
  const project = labels?.[projectLabel]
  if (!db.State.Running || !project || labels[serviceLabel] !== 'postgres' || labels[testLabel] !== 'true') {
    throw new Error('CARE_E2E_DB_CONTAINER must name a running PostgreSQL container from compose.verify.yaml.')
  }

  const webId = docker('ps', '--quiet', '--filter', 'label=' + testLabel + '=true',
    '--filter', 'label=' + projectLabel + '=' + project, '--filter', 'label=' + serviceLabel + '=web')
  if (!webId) throw new Error('The test project has no running Web container.')
  const [web] = JSON.parse(docker('inspect', '--type=container', '--', webId)) as Container[]
  if (!web.NetworkSettings.Ports['80/tcp']?.some((port) => port.HostIp === url.hostname && port.HostPort === url.port)) {
    throw new Error('CARE_WEB_URL must match the Web port of the same test project as CARE_E2E_DB_CONTAINER.')
  }
}
