#!/usr/bin/env node
/**
 * Kopplungs- und Hub-Analyse für das OpenCode-Monorepo.
 *
 * Erzeugt die Messwerte, die in `docs/03-bounded-contexts.md` und
 * `docs/04-change-impact.md` zitiert werden.
 *
 * Aufruf:  node docs/tools/coupling-analysis.mjs [repoRoot]
 * Default: /home/torsten/dev/my-projects/oc
 *
 * Messmethode:
 *  - Imports werden per Regex `from "<relativer Pfad>"` erfasst.
 *  - TypeScript-Source in diesem Repo nutzt NodeNext-Imports mit `.js`-Suffix;
 *    der Auflöser strippt `.js` und prüft `.ts` bzw. `index.ts`.
 *  - Fan-In/Fan-Out werden auf Datei-Ebene gezählt, die "(root)"-Ebene fasst die
 *    62 Top-Level-Domänendateien von `packages/core/src` zusammen.
 *  - Paketgrenzen werden zusätzlich über echte `@opencode/*`-Importe geprüft
 *    (package.json-Deps allein beweisen keine Einhaltung).
 */

import fs from "node:fs"
import path from "node:path"

const repo = process.argv[2] ?? "/home/torsten/dev/my-projects/oc"
const coreSrc = path.join(repo, "packages/core/src")

function walk(dir, acc = [], skip = new Set(["node_modules", ".git", "dist", "build"])) {
  if (!fs.existsSync(dir)) return acc
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skip.has(e.name)) continue
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, acc, skip)
    else if (/\.(ts|tsx|mts)$/.test(e.name)) acc.push(p)
  }
  return acc
}

function resolveLocal(from, spec) {
  const raw = path.normalize(path.join(path.dirname(from), spec))
  const stem = raw.replace(/\.js$/, "")
  if (fs.existsSync(stem + ".ts")) return stem + ".ts"
  if (fs.existsSync(path.join(stem, "index.ts"))) return path.join(stem, "index.ts")
  return null
}

const sub = (f) => {
  const rel = path.relative(coreSrc, f)
  const parts = rel.split(path.sep)
  return parts.length > 1 ? parts[0] : "(root)"
}

// ── 1) Paket-Import-Kanten über alle packages/ ────────────────────────────────
const pkgOf = (f) => {
  const parts = path.relative(repo, f).split(path.sep)
  if (parts[0] !== "packages") return null
  return ["plugins", "console", "stats"].includes(parts[1]) ? `${parts[1]}/${parts[2]}` : parts[1]
}

const pkgEdges = new Map()
const pkgHits = []
for (const f of walk(path.join(repo, "packages"))) {
  const from = pkgOf(f)
  if (!from) continue
  const src = fs.readFileSync(f, "utf8")
  const re = /from\s+["'](@opencode\/[^"']+)["']/g
  let m
  while ((m = re.exec(src))) {
    const target = m[1].split("/")[1]
    if (["plugins", "console", "stats"].includes(target)) continue
    const to = target
    if (from === to) continue
    pkgEdges.set(`${from} -> ${to}`, (pkgEdges.get(`${from} -> ${to}`) ?? 0) + 1)
    pkgHits.push({ file: path.relative(repo, f), from, to, spec: m[1], typeOnly: /import\s+type/.test(src.slice(Math.max(0, m.index - 60), m.index)) })
  }
}

console.log("═".repeat(78))
console.log("1) PAKET-IMPORT-KANTEN (echte @opencode/*-Importe)")
console.log("═".repeat(78))
for (const [k, v] of [...pkgEdges.entries()].sort((a, b) => b[1] - a[1]).slice(0, 30)) {
  console.log(String(v).padStart(4), k)
}

console.log("")
console.log("GRENZPRÜFUNG (jeweils 0 Treffer = Regel eingehalten)")
const forbidden = [
  ["client", "core"],
  ["client", "server"],
  ["schema", "core"],
  ["schema", "protocol"],
  ["protocol", "core"],
  ["protocol", "server"],
  ["core", "server"],
  ["core", "client"],
  ["core", "protocol"],
  ["app", "core"],
  ["tui", "server"],
  ["session-ui", "core"],
  ["ui", "core"],
]
for (const [from, to] of forbidden) {
  const hits = pkgHits.filter((h) => h.from === from && h.to === to)
  const prod = hits.filter((h) => !h.file.includes("/test/"))
  console.log(
    `  ${from} -> ${to}`.padEnd(28),
    `${hits.length} Treffer gesamt, ${prod.length} in Produktivcode`,
    prod.length ? prod.map((h) => h.file).join(", ") : "",
  )
}

// ── 2) core/src Unterordner-Kopplung ─────────────────────────────────────────
const files = walk(coreSrc)
const importedBy = new Map()
const outDeg = new Map()
const inDeg = new Map()
const fileCount = new Map()
const lineCount = new Map()

for (const f of files) {
  const s = sub(f)
  fileCount.set(s, (fileCount.get(s) ?? 0) + 1)
  lineCount.set(s, (lineCount.get(s) ?? 0) + fs.readFileSync(f, "utf8").split("\n").length)
  const src = fs.readFileSync(f, "utf8")
  const re = /from\s+["'](\.[^"']+)["']/g
  let m
  const targets = new Set()
  while ((m = re.exec(src))) {
    const t = resolveLocal(f, m[1])
    if (t) targets.add(t)
  }
  for (const t of targets) {
    importedBy.set(t, (importedBy.get(t) ?? 0) + 1)
    outDeg.set(s, (outDeg.get(s) ?? 0) + 1)
    inDeg.set(sub(t), (inDeg.get(sub(t)) ?? 0) + 1)
  }
}

console.log("")
console.log("═".repeat(78))
console.log("2) core/src – UNTERORDNER (Dateien | Zeilen | Fan-Out | Fan-In)")
console.log("═".repeat(78))
for (const s of [...fileCount.keys()].sort((a, b) => (lineCount.get(b) ?? 0) - (lineCount.get(a) ?? 0))) {
  console.log(
    `  ${s.padEnd(18)} ${String(fileCount.get(s)).padStart(4)} ${String(lineCount.get(s)).padStart(7)} ${String(outDeg.get(s) ?? 0).padStart(6)} ${String(inDeg.get(s) ?? 0).padStart(6)}`,
  )
}
console.log(`  TOTAL ${files.length} Dateien, ${[...lineCount.values()].reduce((a, b) => a + b, 0)} Zeilen`)

// ── 3) Hub-Module (Fan-In) ──────────────────────────────────────────────────
console.log("")
console.log("═".repeat(78))
console.log("3) HUB-MODULE in core/src (Fan-In = Anzahl importierender Dateien)")
console.log("═".repeat(78))
for (const [f, n] of [...importedBy.entries()].sort((a, b) => b[1] - a[1]).slice(0, 25)) {
  console.log(String(n).padStart(4), path.relative(coreSrc, f))
}

// ── 4) Größte Dateien im Repo (packages/) ───────────────────────────────────
console.log("")
console.log("═".repeat(78))
console.log("4) GRÖSSTE DATEIEN in packages/ (Zeilen)")
console.log("═".repeat(78))
const big = walk(path.join(repo, "packages"))
  .map((f) => [path.relative(repo, f), fs.readFileSync(f, "utf8").split("\n").length])
  .sort((a, b) => b[1] - a[1])
for (const [f, n] of big.slice(0, 20)) console.log(String(n).padStart(6), f)
