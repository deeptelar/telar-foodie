// The proxy on Cloud Run: the keys come from Secret Manager as environment variables.

import { createServer } from "node:http";
import { firestore } from "./src/firestore.mjs";
import { proxy } from "./src/proxy.mjs";

const list = (value) => (value ?? "").split(",").map((it) => it.trim()).filter(Boolean);

if (!process.env.OPENROUTER_KEY) throw new Error("OPENROUTER_KEY is not set: see proxy/README.md.");

const handler = proxy(firestore(), {
  openRouterKey: process.env.OPENROUTER_KEY,
  openRouterUrl: process.env.OPENROUTER_URL ?? "https://openrouter.ai/api/v1",
  googleKey: process.env.GOOGLE_PLACES_KEY ?? "",
  placesUrl: process.env.PLACES_URL ?? "https://places.googleapis.com",
  origins: list(process.env.ALLOWED_ORIGINS ?? "https://deeptelar.github.io"),
  models: list(process.env.ALLOWED_MODELS),
});

const port = Number(process.env.PORT ?? 8080);
createServer(handler).listen(port, () => console.log(`Foodie Route proxy on port ${port}`));
