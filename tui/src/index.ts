#!/usr/bin/env bun
import { createInterface } from "node:readline";
import { QocClient, EventStream } from "./client";

const baseUrl = process.env.QOC_URL ?? "http://localhost:8080";
const token = process.env.QOC_TOKEN;
const client = new QocClient({ baseUrl, token });

const args = process.argv.slice(2);
const cmd = args[0];

let sawText = false;

function handleEvent(ev: any): void {
  switch (ev?._type) {
    case "TextDelta": {
      const text = ev.text ?? "";
      if (text) {
        process.stdout.write(text);
        sawText = true;
      }
      break;
    }
    case "TextEnded":
      process.stdout.write("\n");
      break;
    case "ToolCallRequested":
      console.error(`[tool] ${ev.name ?? ""}`);
      break;
    case "ToolResultProvided": {
      const text = String(ev?.outcome?.text ?? "").trim();
      if (text) {
        console.error(`[tool result] ${text.length > 300 ? text.slice(0, 300) + "…" : text.replace(/\n/g, " ")}`);
      }
      break;
    }
    case "ToolFailed":
    case "ProviderFailed":
      console.error(`[error] ${ev.message ?? ""}`);
      break;
    case "RetryScheduled":
      console.error(`[retry] attempt ${ev.attempt ?? ""} in ${ev.delay ?? ""}`);
      break;
    case "SubagentStarted":
      console.error(`[subagent] started ${ev.agent ?? ""}`);
      break;
    case "SubagentFinished":
      console.error(`[subagent] ${ev.state ?? "finished"}`);
      break;
    case "SubagentCancelled":
      console.error("[subagent] cancelled");
      break;
    default:
      break;
  }
}

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

async function main() {
  if (cmd === "health" || !cmd) {
    console.log(JSON.stringify(await client.health(), null, 2));
    return;
  }
  if (cmd === "agents") {
    console.log(JSON.stringify(await client.listAgents(), null, 2));
    return;
  }
  if (cmd === "sessions") {
    console.log(JSON.stringify(await client.listSessions(), null, 2));
    return;
  }
  if (cmd === "run") {
    const text = args.slice(1).join(" ");
    if (!text) { console.error("usage: run <prompt>"); process.exit(1); }
    const s = await client.createSession("build");
    let stream: EventStream | null = null;
    try {
      stream = await client.openEvents(s.id, handleEvent);
    } catch (e) {
      console.error("[sse]", (e as Error).message);
    }
    try {
      sawText = false;
      const r = await client.runPrompt(s.id, text);
      if (stream) await delay(150);
      if (!sawText) console.log(r.text);
      console.error(`[finish=${r.finish} in=${r.inputTokens} out=${r.outputTokens} session=${s.id}]`);
    } finally {
      stream?.close();
    }
    return;
  }
  if (cmd === "chat") {
    const s = await client.createSession("build");
    console.log("session:", s.id);
    let stream: EventStream | null = null;
    try {
      stream = await client.openEvents(s.id, handleEvent);
    } catch (e) {
      console.error("[sse]", (e as Error).message);
    }
    const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: "qoc> " });
    rl.prompt();
    rl.on("line", async (line) => {
      const t = line.trim();
      if (!t || t === "exit" || t === "quit") { rl.close(); return; }
      try {
        sawText = false;
        const r = await client.runPrompt(s.id, t);
        if (stream) await delay(150);
        if (!sawText) console.log(r.text);
        console.error(`[finish=${r.finish} in=${r.inputTokens} out=${r.outputTokens}]`);
      } catch (e) {
        console.error("error:", (e as Error).message);
      }
      rl.prompt();
    });
    rl.on("close", () => {
      stream?.close();
      process.exit(0);
    });
    return;
  }
  console.error("unknown command:", cmd);
  console.error("commands: health, agents, sessions, run <prompt>, chat");
  process.exit(1);
}

main().catch((e) => { console.error(e); process.exit(1); });
