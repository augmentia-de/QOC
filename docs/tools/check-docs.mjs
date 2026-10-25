#!/usr/bin/env node
/**
 * Lightweight documentation checks for qoc/docs.
 *
 * No external dependencies. Exit code 1 on errors, 0 on success.
 * Warnings are reported but do not fail the run.
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const docsRoot = path.resolve(scriptDir, "..");
const skipDirs = new Set([".git", ".idea", "node_modules", "target", "build", "dist"]);
const corruptionMarkers = ["<|end|>", "<|start|>", "<|channel|>", "<|message|>"];

const errors = [];
const warnings = [];

function walk(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith(".") || skipDirs.has(entry.name)) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(full));
    else if (entry.isFile() && entry.name.endsWith(".md")) out.push(full);
  }
  return out;
}

function relative(file) {
  return path.relative(docsRoot, file).replaceAll(path.sep, "/");
}

function slugifyHeading(text) {
  return text
    .trim()
    .toLowerCase()
    .replace(/<[^>]*>/g, "")
    .replace(/[^\p{L}\p{N}\s_-]/gu, "")
    .replace(/\s+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function collectAnchors(text) {
  const anchors = new Set();
  const counts = new Map();
  let fence = false;
  for (const raw of text.split(/\r?\n/)) {
    if (/^\s*```/.test(raw)) {
      fence = !fence;
      continue;
    }
    if (fence) continue;
    const match = /^(#{1,6})\s+(.+?)\s*$/.exec(raw);
    if (!match) continue;
    const base = slugifyHeading(match[2]);
    if (!base) continue;
    const count = counts.get(base) ?? 0;
    anchors.add(count === 0 ? base : `${base}-${count}`);
    counts.set(base, count + 1);
  }
  return anchors;
}

function checkTables(file, text) {
  const lines = text.split(/\r?\n/);
  let fence = false;
  let block = [];
  const flush = () => {
    if (block.length < 2) { block = []; return; }
    const separator = block[1];
    if (!/^\s*\|?[\s:-]+\|[\s:|-]+\|?\s*$/.test(separator)) {
      block = [];
      return;
    }
    const expected = (block[0].match(/(?<!\\)\|/g) ?? []).length;
    for (let i = 2; i < block.length; i++) {
      const actual = (block[i].match(/(?<!\\)\|/g) ?? []).length;
      if (actual !== expected) {
        errors.push(`${relative(file)}: table row has ${actual} pipes, expected ${expected}: ${block[i].slice(0, 120)}`);
      }
    }
    block = [];
  };
  for (const line of lines) {
    if (/^\s*```/.test(line)) {
      flush();
      fence = !fence;
      continue;
    }
    if (fence) continue;
    if (/^\s*\|/.test(line)) block.push(line);
    else flush();
  }
  flush();
}

function checkLinks(file, text, anchorsByFile) {
  const linkPattern = /!?\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;
  let match;
  while ((match = linkPattern.exec(text)) !== null) {
    const rawTarget = match[1].trim();
    if (!rawTarget || rawTarget.startsWith("#")) continue;
    if (/^(https?:|mailto:|tel:|data:)/i.test(rawTarget)) continue;

    const hashIndex = rawTarget.indexOf("#");
    const targetPart = hashIndex === -1 ? rawTarget : rawTarget.slice(0, hashIndex);
    const fragment = hashIndex === -1 ? "" : rawTarget.slice(hashIndex + 1);
    if (!targetPart) continue;

    let decoded;
    try {
      decoded = decodeURIComponent(targetPart);
    } catch {
      decoded = targetPart;
    }

    const resolved = path.resolve(path.dirname(file), decoded);
    let targetFile = resolved;
    if (fs.existsSync(resolved) && fs.statSync(resolved).isDirectory()) {
      const readme = path.join(resolved, "README.md");
      const index = path.join(resolved, "index.md");
      targetFile = fs.existsSync(readme) ? readme : index;
    }

    if (!fs.existsSync(targetFile)) {
      errors.push(`${relative(file)}: broken link -> ${rawTarget}`);
      continue;
    }

    if (fragment && targetFile.endsWith(".md")) {
      const anchors = anchorsByFile.get(targetFile);
      if (anchors && !anchors.has(fragment)) {
        warnings.push(`${relative(file)}: missing anchor #${fragment} in ${relative(targetFile)}`);
      }
    }
  }
}

function checkCorruption(file, text) {
  for (const marker of corruptionMarkers) {
    if (text.includes(marker)) {
      errors.push(`${relative(file)}: corruption marker ${marker}`);
    }
  }
}

function checkSpecIndexCoverage(files) {
  const byDir = new Map();
  for (const file of files) {
    if (!relative(file).startsWith("spec/")) continue;
    const dir = path.dirname(file);
    if (!byDir.has(dir)) byDir.set(dir, []);
    byDir.get(dir).push(file);
  }

  for (const [dir, dirFiles] of byDir.entries()) {
    const readme = path.join(dir, "README.md");
    if (!fs.existsSync(readme)) continue;
    const readmeText = fs.readFileSync(readme, "utf8");
    for (const file of dirFiles) {
      if (file === readme) continue;
      const base = path.basename(file);
      const rel = path.relative(dir, file).replaceAll(path.sep, "/");
      if (!readmeText.includes(base) && !readmeText.includes(rel)) {
        warnings.push(`${relative(readme)}: does not mention ${rel}`);
      }
    }
  }
}

const files = walk(docsRoot).sort();
const anchorsByFile = new Map();

for (const file of files) {
  const text = fs.readFileSync(file, "utf8");
  anchorsByFile.set(file, collectAnchors(text));
  checkCorruption(file, text);
  checkTables(file, text);
  checkLinks(file, text, anchorsByFile);
}

// Links may point forward to files whose anchors are computed above; this is
// already covered because anchorsByFile is fully populated only after the loop.
// Re-run link checks once to use the complete anchor map.
errors.length = 0;
warnings.length = 0;
for (const file of files) {
  const text = fs.readFileSync(file, "utf8");
  checkCorruption(file, text);
  checkTables(file, text);
  checkLinks(file, text, anchorsByFile);
}
checkSpecIndexCoverage(files);

console.log(`Checked ${files.length} Markdown files under ${relative(docsRoot) || "."}.`);

if (warnings.length) {
  console.log(`\nWarnings (${warnings.length}):`);
  for (const warning of warnings) console.log(`  WARN  ${warning}`);
}

if (errors.length) {
  console.log(`\nErrors (${errors.length}):`);
  for (const error of errors) console.log(`  ERROR ${error}`);
  process.exitCode = 1;
} else {
  console.log("\nNo errors.");
}
