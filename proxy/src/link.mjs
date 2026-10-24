// A try-out link, made as the page makes it (TryOut.kt), so that the page opens it with the dish.

const SALT_BYTES = 16;
const IV_BYTES = 12;
const ROUNDS = 600_000;

const ACCENTS = new Map([..."àáâãäå"].map((c) => [c, "a"]).concat([..."èéêë"].map((c) => [c, "e"]), [..."ìíîï"].map((c) => [c, "i"]), [..."òóôõö"].map((c) => [c, "o"]), [..."ùúûü"].map((c) => [c, "u"]), [["ç", "c"], ["ñ", "n"]]));
const KIND_WORDS = new Set(["restaurante", "restaurant", "ristorante", "trattoria", "osteria", "taberna", "tasca", "cafe", "the", "o", "a", "el", "la", "le"]);

/** The dish as the page reads it: `String.simplified()` of Candidates.kt. */
export function secret(dish) {
  const plain = [...dish.toLowerCase()].map((c) => ACCENTS.get(c) ?? c).map((c) => (/[a-z0-9]/.test(c) ? c : " ")).join("");
  const words = plain.split(" ").filter((w) => w.trim() !== "");
  let start = 0;
  while (start < words.length && KIND_WORDS.has(words[start]) && words.length > 1) start++;
  return words.slice(start).join(" ");
}

/** Locks [tryOut] with [dish]: the salt, the IV and the encrypted JSON, in Base64 for an address. */
export async function lock(tryOut, dish) {
  const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES));
  const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES));
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await key(dish, salt), new TextEncoder().encode(JSON.stringify(tryOut))));
  return Buffer.from([...salt, ...iv, ...sealed]).toString("base64url");
}

/** The try-out that [code] locks, or `null` with another dish. */
export async function unlock(code, dish) {
  try {
    const all = Buffer.from(code, "base64url");
    const opened = await crypto.subtle.decrypt({ name: "AES-GCM", iv: all.subarray(SALT_BYTES, SALT_BYTES + IV_BYTES) }, await key(dish, all.subarray(0, SALT_BYTES)), all.subarray(SALT_BYTES + IV_BYTES));
    return JSON.parse(new TextDecoder().decode(opened));
  } catch {
    return null;
  }
}

async function key(dish, salt) {
  const material = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret(dish)), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey({ name: "PBKDF2", hash: "SHA-256", salt, iterations: ROUNDS }, material, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}
