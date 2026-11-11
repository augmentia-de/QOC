export interface QocClientOptions {
  baseUrl: string;
  token?: string;
}

export interface EventStream {
  close(): void;
}

export class QocClient {
  constructor(private opts: QocClientOptions) {}

  get baseUrl() { return this.opts.baseUrl.replace(/\/$/, ""); }

  private headers(): Record<string, string> {
    const h: Record<string, string> = { "Content-Type": "application/json" };
    if (this.opts.token) h["Authorization"] = "Bearer " + this.opts.token;
    return h;
  }

  async health(): Promise<any> {
    const r = await fetch(this.baseUrl + "/api/health");
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
  }

  async listSessions(): Promise<any> {
    const r = await fetch(this.baseUrl + "/api/session", { headers: this.headers() });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
  }

  async createSession(agent = "build"): Promise<any> {
    const r = await fetch(this.baseUrl + "/api/session", {
      method: "POST",
      headers: this.headers(),
      body: JSON.stringify({ agent }),
    });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
  }

  async runPrompt(sessionId: string, text: string): Promise<any> {
    const r = await fetch(this.baseUrl + `/api/session/${sessionId}/prompt`, {
      method: "POST",
      headers: this.headers(),
      body: JSON.stringify({ text }),
    });
    if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`);
    return r.json();
  }

  async listAgents(): Promise<any> {
    const r = await fetch(this.baseUrl + "/api/agent", { headers: this.headers() });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
  }

  /**
   * Opens the live SSE stream. `after=Number.MAX_SAFE_INTEGER` suppresses
   * historical replay; only new events are delivered.
   */
  async openEvents(sessionId: string, onEvent: (ev: any) => void): Promise<EventStream> {
    const controller = new AbortController();
    const url = this.baseUrl + `/api/event?session=${encodeURIComponent(sessionId)}`
      + `&live=true&after=${Number.MAX_SAFE_INTEGER}`;
    const r = await fetch(url, {
      headers: { ...this.headers(), Accept: "text/event-stream" },
      signal: controller.signal,
    });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);

    const reader = r.body?.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let data: string[] = [];

    const dispatch = () => {
      if (data.length === 0) return;
      const raw = data.join("\n");
      data = [];
      try {
        onEvent(JSON.parse(raw));
      } catch {
        // Malformed event must not terminate the stream.
      }
    };

    (async () => {
      try {
        while (reader) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let nl: number;
          while ((nl = buffer.indexOf("\n")) >= 0) {
            let line = buffer.slice(0, nl);
            buffer = buffer.slice(nl + 1);
            if (line.endsWith("\r")) line = line.slice(0, -1);
            if (line === "") {
              dispatch();
            } else if (line.startsWith("data:")) {
              data.push(line.slice(5).replace(/^ /, ""));
            }
          }
        }
        dispatch();
      } catch (e) {
        if (!controller.signal.aborted) {
          console.error("[sse]", (e as Error).message);
        }
      }
    })();

    return { close: () => controller.abort() };
  }
}
