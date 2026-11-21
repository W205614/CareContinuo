import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

export const project = process.env.CARE_PREPROD_PROJECT ?? 'carecontinuo-preprod'
export const directory = resolve(process.env.CARE_PREPROD_FIXTURES ?? '../.local/preprod')
export const origin = process.env.CARE_PREPROD_ORIGIN!
export const composeArgs = ['compose', '-f', 'compose.preprod.yaml', '--env-file', resolve(directory, 'compose.env'), '-p', project]

export default function verify() {
  const envFile = resolve(directory, 'compose.env')
  const expected = readFileSync(envFile, 'utf8').split(/\r?\n/)
    .find((line) => line.startsWith('CARE_PREPROD_ORIGIN='))?.split('=')[1]
  if (!expected || origin !== expected) throw new Error('CARE_PREPROD_ORIGIN must match the verified loopback Compose project')
  execFileSync(process.env.CARE_TEST_PYTHON ?? 'python', [
    'deploy/preprod/control.py', '--env-file', envFile, '--project', project, 'verify',
  ], { cwd: '..', stdio: 'pipe' })
}
