import assert from "node:assert/strict";
import { test } from "node:test";
import { lock, secret, unlock } from "../src/link.mjs";

test("the dish is read as the page reads it", () => {
  assert.equal(secret(" Pastél de NATA "), "pastel de nata");
  assert.equal(secret("La Bomba"), "bomba");
  assert.equal(secret("Çorba, ñoquis!"), "corba noquis");
});

test("a link opens with its dish only", async () => {
  const tryOut = { provider: "OPENAI", model: "google/gemma-4-31b-it", url: "https://proxy.test/v1", key: "fr-ana", until: 1, google: "fr-ana", placesUrl: "https://proxy.test" };
  const code = await lock(tryOut, "Tumbet");

  assert.deepEqual(await unlock(code, "tumbet"), tryOut);
  assert.equal(await unlock(code, "bifana"), null);
});
