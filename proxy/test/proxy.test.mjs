import assert from "node:assert/strict";
import { createServer } from "node:http";
import { after, before, test } from "node:test";
import { hashOf, PLACE_FIELDS, proxy } from "../src/proxy.mjs";

const PAGE = "https://deeptelar.github.io";
const friends = new Map();
const sent = [];
let now = 1_000;
let server;
let address;

/** OpenRouter and Google, as the proxy sees them: a stream with its usage last, and a search. */
async function fakeFetch(url, init) {
  sent.push({ url, headers: init.headers, body: init.body });
  if (url.endsWith("/chat/completions")) {
    const events = ['data: {"choices":[{"delta":{"content":"Hola"}}]}\n\n', 'data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":30}}\n\n', "data: [DONE]\n\n"];
    const body = new ReadableStream({ start(c) { events.forEach((e) => c.enqueue(new TextEncoder().encode(e))); c.close(); } });
    return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
  }
  return new Response('{"places":[]}', { status: 200, headers: { "content-type": "application/json" } });
}

const store = {
  find: async (hash) => friends.get(hash) ?? null,
  addUsage: async (hash, { tokens, searches }) => {
    const f = friends.get(hash);
    f.tokensUsed += tokens;
    f.searchesUsed += searches;
  },
};

function friend(token, changes = {}) {
  friends.set(hashOf(token), { name: token, until: 2_000, tokenLimit: 1_000, tokensUsed: 0, searchLimit: 2, searchesUsed: 0, ...changes });
}

const chat = (token, model = "google/gemma-4-31b-it", origin = PAGE) =>
  fetch(`${address}/v1/chat/completions`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json", origin },
    body: JSON.stringify({ model, stream: true, messages: [{ role: "user", content: "hi" }] }),
  });

const search = (token) =>
  fetch(`${address}/v1/places:searchText`, { method: "POST", headers: { "x-goog-api-key": token, "x-goog-fieldmask": "*", origin: PAGE }, body: '{"textQuery":"tumbet"}' });

before(async () => {
  server = createServer(
    proxy(store, {
      openRouterKey: "sk-or-real",
      openRouterUrl: "https://openrouter.test/api/v1",
      googleKey: "AIza-real",
      placesUrl: "https://places.test",
      origins: [PAGE],
      models: ["google/gemma-4-31b-it"],
      now: () => now,
      fetch: fakeFetch,
    }),
  );
  await new Promise((resolve) => server.listen(0, resolve));
  address = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

test("a friend's chat goes to OpenRouter with the real key, streams back, and counts its tokens", async () => {
  friend("fr-ana");
  sent.length = 0;
  const response = await chat("fr-ana");

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("access-control-allow-origin"), PAGE);
  assert.match(await response.text(), /Hola[\s\S]*\[DONE\]/);
  assert.equal(sent[0].url, "https://openrouter.test/api/v1/chat/completions");
  assert.equal(sent[0].headers.authorization, "Bearer sk-or-real");
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(friends.get(hashOf("fr-ana")).tokensUsed, 150);
});

test("a search goes to Google with the real key and the page's fields, and counts one", async () => {
  friend("fr-ben");
  sent.length = 0;
  const response = await search("fr-ben");

  assert.equal(response.status, 200);
  assert.equal(sent[0].url, "https://places.test/v1/places:searchText");
  assert.equal(sent[0].headers["x-goog-api-key"], "AIza-real");
  // The friend cannot ask for fields that Google bills more for.
  assert.equal(sent[0].headers["x-goog-fieldmask"], PLACE_FIELDS);
  assert.equal(friends.get(hashOf("fr-ben")).searchesUsed, 1);
});

test("an unknown token, an ended try-out and a spent one are turned away, and nothing is sent", async () => {
  friend("fr-ended", { until: 500 });
  friend("fr-spent", { tokensUsed: 1_000, searchesUsed: 2 });
  sent.length = 0;

  for (const [response, words] of [
    [await chat("fr-nobody"), /not known/],
    [await chat("fr-ended"), /has ended/],
    [await chat("fr-spent"), /all its tokens/],
    [await search("fr-spent"), /all its Google searches/],
  ]) {
    assert.equal(response.status, 401);
    assert.match((await response.json()).error.message, words);
  }
  assert.equal(sent.length, 0);
});

test("only the models of the try-out, and only the page's site, are let through", async () => {
  friend("fr-cai");
  sent.length = 0;

  const model = await chat("fr-cai", "anthropic/claude-fable-5.1");
  assert.equal(model.status, 403);
  assert.match((await model.json()).error.message, /not part of this try-out/);
  assert.equal((await chat("fr-cai", "google/gemma-4-31b-it", "https://evil.example")).status, 403);
  assert.equal(sent.length, 0);
});

test("the browser's preflight is answered for the page's site only", async () => {
  const ok = await fetch(`${address}/v1/chat/completions`, { method: "OPTIONS", headers: { origin: PAGE, "access-control-request-headers": "authorization,content-type" } });
  assert.equal(ok.status, 204);
  assert.equal(ok.headers.get("access-control-allow-headers"), "authorization,content-type");
  const other = await fetch(`${address}/v1/chat/completions`, { method: "OPTIONS", headers: { origin: "https://evil.example" } });
  assert.equal(other.status, 403);
});
