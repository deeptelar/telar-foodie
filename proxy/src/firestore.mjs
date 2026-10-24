// The friends of the proxy, in Firestore: one document a friend, named by the hash of their token.

import { FieldValue, Firestore } from "@google-cloud/firestore";

const COLLECTION = "friends";

/** The store of the proxy and of `friends.mjs`, in the project of the credentials. */
export function firestore() {
  const friends = new Firestore().collection(COLLECTION);
  return {
    async find(hash) {
      const doc = await friends.doc(hash).get();
      return doc.exists ? doc.data() : null;
    },
    async addUsage(hash, { tokens, searches }) {
      await friends.doc(hash).update({ tokensUsed: FieldValue.increment(tokens), searchesUsed: FieldValue.increment(searches) });
    },
    async add(hash, friend) {
      await friends.doc(hash).create(friend);
    },
    async all() {
      return (await friends.orderBy("created").get()).docs.map((doc) => ({ hash: doc.id, ...doc.data() }));
    },
    async remove(hash) {
      await friends.doc(hash).delete();
    },
  };
}
