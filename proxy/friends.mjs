// Adds, lists and removes the friends of the proxy. Adding one prints their try-out link.
//
//   node friends.mjs add Ana --dish tumbet [--days 3] [--tokens 500000] [--searches 40] [--model google/gemma-4-31b-it] [--no-google]
//   node friends.mjs list
//   node friends.mjs remove Ana
//
// FOODIE_PROXY is the address of the proxy on Cloud Run, and GOOGLE_CLOUD_PROJECT its project.

import { randomBytes } from "node:crypto";
import { parseArgs } from "node:util";
import { firestore } from "./src/firestore.mjs";
import { lock, secret } from "./src/link.mjs";
import { hashOf } from "./src/proxy.mjs";

const PAGE = process.env.FOODIE_PAGE ?? "https://deeptelar.github.io/telar-foodie/";
const DAY = 24 * 60 * 60 * 1000;

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    dish: { type: "string" },
    days: { type: "string", default: "3" },
    tokens: { type: "string", default: "500000" },
    searches: { type: "string", default: "40" },
    model: { type: "string", default: process.env.FOODIE_MODEL ?? "google/gemma-4-31b-it" },
    "no-google": { type: "boolean", default: false },
  },
});
const [command, name] = positionals;
const store = firestore();

if (command === "add" && name) {
  const proxy = (process.env.FOODIE_PROXY ?? "").replace(/\/+$/, "");
  if (!proxy.startsWith("https://")) throw new Error("Set FOODIE_PROXY to the address of the proxy, such as https://foodie-proxy-abc123-ew.a.run.app");
  if (!values.dish || !secret(values.dish)) throw new Error("Give a secret dish with --dish, to tell the friend apart from the link.");
  const token = "fr-" + randomBytes(24).toString("base64url");
  const until = Date.now() + Number(values.days) * DAY;
  const google = !values["no-google"];
  await store.add(hashOf(token), {
    name,
    created: Date.now(),
    until,
    tokenLimit: Number(values.tokens),
    tokensUsed: 0,
    searchLimit: google ? Number(values.searches) : 0,
    searchesUsed: 0,
  });
  // The same fields as TryOut.kt: the page plans with the proxy as its server, and the token as every key.
  const tryOut = { provider: "OPENAI", model: values.model, url: `${proxy}/v1`, key: token, until, google: google ? token : "", placesUrl: google ? proxy : "" };
  console.log(`${name} can plan until ${new Date(until).toLocaleString()}, with ${values.tokens} tokens${google ? ` and ${values.searches} Google searches` : ""}.`);
  console.log(`\nThe link (the dish is "${values.dish}"; tell it apart):\n${PAGE}#try=${await lock(tryOut, values.dish)}`);
} else if (command === "list") {
  const friends = await store.all();
  if (friends.length === 0) console.log("No friends yet.");
  for (const f of friends) {
    const state = Date.now() >= f.until ? "ended" : `until ${new Date(f.until).toLocaleString()}`;
    console.log(`${f.name}: ${state} · ${f.tokensUsed} of ${f.tokenLimit} tokens · ${f.searchesUsed} of ${f.searchLimit} searches`);
  }
} else if (command === "remove" && name) {
  const friends = (await store.all()).filter((f) => f.name === name);
  if (friends.length === 0) console.log(`No friend called ${name}.`);
  for (const f of friends) await store.remove(f.hash);
  if (friends.length > 0) console.log(`Removed ${name}: their link stops working now.`);
} else {
  console.log("Use: node friends.mjs add <name> --dish <dish> [--days 3] [--tokens 500000] [--searches 40] [--model <id>] [--no-google]\n     node friends.mjs list\n     node friends.mjs remove <name>");
}
