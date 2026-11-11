import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { createServer, Server } from "node:http";
import { QocClient, EventStream } from "./client";

let server: Server;
let baseUrl: string;

beforeAll(async () => {
  server = createServer((req, res) => {
    const url = req.url ?? "/";
    if (url.startsWith("/api/health")) {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ status: "ok" }));
      return;
    }
    if (url.startsWith("/api/session/s1/prompt")) {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ text: "hi", finish: "NORMAL", inputTokens: 1, outputTokens: 2 }));
      return;
    }
    if (url.startsWith("/api/session") && req.method === "POST") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ id: "s1" }));
      return;
    }
    if (url.startsWith("/api/session")) {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ data: [] }));
      return;
    }
    if (url.startsWith("/api/event")) {
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.write('data: {"_type":"TextDelta","text":"hi"}\n\n');
      res.end();
      return;
    }
    res.writeHead(404);
    res.end("not found");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  if (!addr || typeof addr === "string") throw new Error("no port");
  baseUrl = `http://127.0.0.1:${addr.port}`;
});

afterAll(async () => {
  await new Promise<void>((resolve) => server.close(() => resolve()));
});

describe("QocClient", () => {
  test("health", async () => {
    const c = new QocClient({ baseUrl });
    expect(await c.health()).toEqual({ status: "ok" });
  });

  test("session lifecycle", async () => {
    const c = new QocClient({ baseUrl });
    expect(await c.createSession()).toEqual({ id: "s1" });
    const r = await c.runPrompt("s1", "hello");
    expect(r.text).toBe("hi");
    expect(r.finish).toBe("NORMAL");
  });

  test("SSE stream receives events", async () => {
    const c = new QocClient({ baseUrl });
    const events: any[] = [];
    let stream: EventStream | null = null;
    try {
      stream = await c.openEvents("s1", (ev) => events.push(ev));
      const deadline = Date.now() + 5000;
      while (events.length === 0 && Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 20));
      }
      expect(events.length).toBeGreaterThan(0);
      expect(events[0]._type).toBe("TextDelta");
      expect(events[0].text).toBe("hi");
    } finally {
      stream?.close();
    }
  });

  test("HTTP errors are reported", async () => {
    const c = new QocClient({ baseUrl: baseUrl + "/missing" });
    await expect(c.health()).rejects.toThrow();
  });
});
