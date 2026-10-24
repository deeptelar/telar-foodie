// The proxy between the page and the paid services: it keeps the real keys, and lets in the
// friends it knows, each with a token, an end and limits of their own.

import { createHash } from "node:crypto";

/** The fields of a place that a search returns, and Google bills: the same as the page asks for. */
export const PLACE_FIELDS =
  "places.id,places.displayName,places.formattedAddress,places.location,places.rating,places.userRatingCount," +
  "places.priceLevel,places.websiteUri,places.primaryTypeDisplayName,places.internationalPhoneNumber";

/** The largest request the proxy reads: a long plan sends the whole conversation each round. */
const MAX_BODY = 5 * 1024 * 1024;

/** How a friend's token is kept: only its hash, so that a copy of the store lets no one in. */
export const hashOf = (token) => createHash("sha256").update(token).digest("hex");

/**
 * The request handler of the proxy.
 *
 * @param store where the friends are: `find(hash)` and `addUsage(hash, { tokens, searches })`.
 * @param config the keys, the addresses of the services, the sites and models allowed, and `now`.
 */
export function proxy(store, config) {
  const now = config.now ?? (() => Date.now());
  const fetch = config.fetch ?? globalThis.fetch;
  const origins = new Set(config.origins);

  return async (req, res) => {
    const origin = req.headers.origin;
    const cors = origin && origins.has(origin)
      ? {
          "access-control-allow-origin": origin,
          "access-control-allow-methods": "GET, POST, OPTIONS",
          "access-control-allow-headers": req.headers["access-control-request-headers"] ?? "authorization, content-type",
          "access-control-max-age": "86400",
          vary: "Origin",
        }
      : {};
    const fail = (status, message, type = "try_out") => {
      res.writeHead(status, { ...cors, "content-type": "application/json" });
      res.end(JSON.stringify({ error: { type, message } }));
    };

    try {
      if (req.method === "OPTIONS") {
        res.writeHead(origin && origins.has(origin) ? 204 : 403, cors);
        return res.end();
      }
      const path = new URL(req.url, "http://proxy").pathname;
      if (req.method === "GET" && path === "/") {
        res.writeHead(200, { ...cors, "content-type": "text/plain" });
        return res.end("Foodie Route proxy");
      }
      // A page of another site may not spend the friends' allowance.
      if (origin && !origins.has(origin)) return fail(403, "This proxy only answers the Foodie Route page.");

      if (req.method === "POST" && path === "/v1/chat/completions") {
        const token = (req.headers.authorization ?? "").replace(/^Bearer\s+/i, "");
        const friend = await admitted(token, "tokens");
        if (typeof friend === "string") return fail(401, friend);
        const body = JSON.parse(await read(req));
        if (config.models.length > 0 && !config.models.includes(body.model)) {
          return fail(403, `The model ${body.model} is not part of this try-out. It can use: ${config.models.join(", ")}.`);
        }
        return relayChat(req, res, cors, hashOf(token), body);
      }

      if (req.method === "POST" && path === "/v1/places:searchText") {
        const token = req.headers["x-goog-api-key"] ?? "";
        const friend = await admitted(token, "searches");
        if (typeof friend === "string") return fail(401, friend);
        if (!config.googleKey) return fail(404, "This try-out has no Google Places.");
        return relaySearch(res, cors, hashOf(token), await read(req));
      }

      return fail(404, `Nothing at ${req.method} ${path}.`, "not_found");
    } catch (error) {
      if (!res.headersSent) fail(502, `The proxy could not answer: ${error.message}`, "proxy");
      else res.end();
    }
  };

  /** The friend of [token], or why they may not go on: unknown, ended, or out of [what] they may use. */
  async function admitted(token, what) {
    const friend = token ? await store.find(hashOf(token)) : null;
    if (!friend) return "This try-out link is not known, or it was removed. Ask your friend for a new one.";
    if (now() >= friend.until) return "This try-out has ended. Ask your friend for a new link.";
    if (what === "tokens" && friend.tokensUsed >= friend.tokenLimit) return "This try-out used all its tokens. Ask your friend for more.";
    if (what === "searches" && friend.searchesUsed >= friend.searchLimit) return "This try-out used all its Google searches. Plans go on without ratings.";
    return friend;
  }

  /** Sends the chat to OpenRouter with the real key, passes its answer on as it comes, and counts its tokens. */
  async function relayChat(req, res, cors, hash, body) {
    const abort = new AbortController();
    res.on("close", () => abort.abort());
    const upstream = await fetch(`${config.openRouterUrl}/chat/completions`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${config.openRouterKey}`,
        "content-type": "application/json",
        "x-title": "Foodie Route",
      },
      body: JSON.stringify(body),
      signal: abort.signal,
    });
    res.writeHead(upstream.status, { ...cors, "content-type": upstream.headers.get("content-type") ?? "application/json", "cache-control": "no-cache" });
    const usage = new UsageReader((upstream.headers.get("content-type") ?? "").includes("event-stream"));
    try {
      for await (const chunk of upstream.body) {
        usage.read(chunk);
        res.write(chunk);
      }
    } catch (error) {
      // The page stopped listening, as when the traveller pauses: what came so far still counts.
      if (!abort.signal.aborted) throw error;
    } finally {
      res.end();
      const tokens = usage.tokens();
      if (tokens > 0) await store.addUsage(hash, { tokens, searches: 0 });
    }
  }

  /** Sends one search to Google with the real key and the page's fields, and counts it. */
  async function relaySearch(res, cors, hash, body) {
    const upstream = await fetch(`${config.placesUrl}/v1/places:searchText`, {
      method: "POST",
      headers: { "x-goog-api-key": config.googleKey, "x-goog-fieldmask": PLACE_FIELDS, "content-type": "application/json" },
      body,
    });
    const text = await upstream.text();
    res.writeHead(upstream.status, { ...cors, "content-type": "application/json" });
    res.end(text);
    if (upstream.ok) await store.addUsage(hash, { tokens: 0, searches: 1 });
  }
}

/**
 * Finds the usage in an answer of the API of OpenAI: in the events of a [stream], whose last ones
 * have it when the request asks with `stream_options.include_usage`, or else in its whole JSON.
 */
export class UsageReader {
  #stream;
  #text = "";
  #decoder = new TextDecoder();
  #found = 0;

  constructor(stream) {
    this.#stream = stream;
  }

  read(chunk) {
    this.#text += this.#decoder.decode(chunk, { stream: true });
    if (!this.#stream) return;
    const lines = this.#text.split("\n");
    this.#text = lines.pop();
    lines.forEach((line) => this.#data(line.startsWith("data:") ? line.slice(5).trim() : ""));
  }

  tokens() {
    this.#data(this.#stream ? this.#text.replace(/^data:/, "").trim() : this.#text);
    this.#text = "";
    return this.#found;
  }

  #data(json) {
    if (!json.trim().startsWith("{")) return;
    try {
      const usage = JSON.parse(json).usage;
      if (usage) this.#found = (usage.prompt_tokens ?? 0) + (usage.completion_tokens ?? 0);
    } catch {
      // An answer cut short, or an event that is not JSON: no usage in it.
    }
  }
}

async function read(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY) throw new Error("the request is too large");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}
