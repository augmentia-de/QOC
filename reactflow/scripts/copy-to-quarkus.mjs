import { cp, mkdir, rm, readdir } from 'node:fs/promises'
import path from 'node:path'

const root = process.cwd()
const resources = path.resolve(root, '../quarkus/src/main/resources/META-INF/resources')

async function copyDirectory(source, destination) {
  await mkdir(destination, { recursive: true })
  for (const entry of await readdir(source, { withFileTypes: true })) {
    const from = path.join(source, entry.name)
    const to = path.join(destination, entry.name)
    if (entry.isDirectory()) await copyDirectory(from, to)
    else await cp(from, to)
  }
}

await rm(path.join(resources, 'assets'), { recursive: true, force: true })
await rm(path.join(resources, 'index.html'), { force: true })
await rm(path.join(resources, 'core'), { recursive: true, force: true })
await copyDirectory(path.join(root, 'dist/app'), resources)
await copyDirectory(path.join(root, 'dist/core'), path.join(resources, 'core'))

console.log('Copied React app to / and Core UI to /core')