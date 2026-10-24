# Foodie Route proxy

A small server on Google Cloud Run that keeps your OpenRouter and Google Places keys, so that
friends can try the planner without ever holding them. Each friend gets a token of their own, with
an end and limits. A forwarded link costs you one `remove`, not a new key.

The page does not need it: a traveller with their own keys talks to the providers straight from the
browser. The proxy is only for try-outs.

```
friend's phone ──token──▶ proxy on Cloud Run ──real key──▶ OpenRouter
                          (checks the token,   ──real key──▶ Google Places
                           counts the usage)
```

- **`POST /v1/chat/completions`**: the API of OpenAI, passed on to OpenRouter as it streams. The
  page uses the proxy as its "address", and the friend's token as its "key". The proxy counts the
  tokens of each answer from its usage, and lets through only the models in `ALLOWED_MODELS`.
- **`POST /v1/places:searchText`**: a search of Google Places, with the fields the page asks for and
  no more, since Google bills by the fields. Each search counts one.
- **Friends** are in Firestore, one document each, named by the SHA-256 of their token: a copy of the
  database lets no one in. A limit is checked before each request, so a friend can go over it by one
  request.

## Deploy it

You need the [`gcloud` CLI](https://cloud.google.com/sdk/docs/install) (`gcloud init` once after
installing it) and Node 22 or newer. Use the Google Cloud project of your Places key, which already
has billing.

```bash
PROJECT=your-project-id
REGION=europe-west1
gcloud config set project $PROJECT
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com firestore.googleapis.com

# The friends' database, once. Firestore's free tier is for the (default) database.
gcloud firestore databases create --location=$REGION
```

**The Google key of the proxy** is a new one: in the console, under **APIs & Services →
Credentials**, create an API key, and restrict it to **Places API (New)** under API restrictions,
with **no** application restriction. A website restriction would stop it, because the proxy calls
Google from Cloud Run, not from a page; and it never reaches a browser.

**The keys go into Secret Manager.** `read -rs` keeps them out of your shell's history:

```bash
read -rs KEY && printf '%s' "$KEY" | gcloud secrets create openrouter-key --data-file=-     # paste the OpenRouter key, Enter
read -rs KEY && printf '%s' "$KEY" | gcloud secrets create google-places-key --data-file=-  # paste the proxy's Google key, Enter
unset KEY
```

**The proxy runs as its own service account**, which may read those two secrets and the database,
and nothing else:

```bash
gcloud iam service-accounts create foodie-proxy
SA=foodie-proxy@$PROJECT.iam.gserviceaccount.com
gcloud projects add-iam-policy-binding $PROJECT --member=serviceAccount:$SA --role=roles/datastore.user
for secret in openrouter-key google-places-key; do
  gcloud secrets add-iam-policy-binding $secret --member=serviceAccount:$SA --role=roles/secretmanager.secretAccessor
done
```

**Deploy**, from the root of the repository. `^;^` makes `;` the separator of the variables, so that
a list of models can have commas. At most 2 instances keep a mistake cheap, and a 10-minute timeout
lets a long answer stream to the end.

```bash
gcloud run deploy foodie-proxy --source proxy --region $REGION --service-account $SA \
  --allow-unauthenticated --max-instances 2 --timeout 600 \
  --set-secrets OPENROUTER_KEY=openrouter-key:latest,GOOGLE_PLACES_KEY=google-places-key:latest \
  --set-env-vars '^;^ALLOWED_ORIGINS=https://deeptelar.github.io;ALLOWED_MODELS=google/gemma-4-31b-it'
```

It prints the proxy's address, such as `https://foodie-proxy-abc123-ew.a.run.app`. `--allow-unauthenticated`
lets the page reach it; the proxy itself turns away anyone without a friend's token.

## Add a friend

`friends.mjs` writes to the same database with your own credentials, and prints the friend's
try-out link: the page opens it with the secret dish, and plans through the proxy.

```bash
cd proxy && npm ci
gcloud auth application-default login
export GOOGLE_CLOUD_PROJECT=$PROJECT FOODIE_PROXY=https://foodie-proxy-abc123-ew.a.run.app

node friends.mjs add Ana --dish tumbet --days 3 --tokens 500000 --searches 40
node friends.mjs list
node friends.mjs remove Ana        # Ana's link stops working at once
```

Send the link, and tell the dish apart from it. The defaults are 3 days, 500,000 tokens and 40
searches. A plan with Gemma 4 31B took about 80,000 tokens in our comparison, so 500,000 is around
six plans, for less than $0.20 at OpenRouter's prices. `--model` picks another model of
`ALLOWED_MODELS`, and `--no-google` leaves out the ratings.

## What it costs

Cloud Run, Firestore and Secret Manager each have a monthly free tier that a few friends stay well
inside; see Google's pricing pages for the current numbers. What you pay for is the tokens at
OpenRouter and the Google searches beyond the free ones of each month. To cap those as well, give
the OpenRouter key a credit limit, and lower the daily quota of Places API (New) in the console.

## Check it

`npm test` runs the proxy against a stand-in for OpenRouter and Google, with no key and no network.
`test/link.test.mjs` checks that a link opens with its dish, and the page's
[`TryOutTest`](../src/jsTest/kotlin/dev/deeptelar/foodie/TryOutTest.kt) opens a link this folder made.
