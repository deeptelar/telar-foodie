# Contributing to Foodie Route

Thank you for considering a contribution. Foodie Route is a complete app on
[Telar](https://github.com/deeptelar/telar), so a change here is also a way to learn how an agent
app on Telar is put together. This guide covers the setup, the checks a change must pass, and how
the project writes its code.

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md), the same as
Telar's.

## Where to start

- **A first contribution:** the issues labeled
  [good first issue](https://github.com/deeptelar/telar-foodie/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)
  are small, and each says which files to read first and how to know the work is done. In October
  they also count for Hacktoberfest.
- **A bug or an idea:** open an issue, with the steps that show it. A saved plan (**Save the plan as
  a file**) helps, but look through it first: it holds the trip and the traveller's answers.
- **A change to Telar itself,** such as the graph, the agent or a model provider, belongs in
  [deeptelar/telar](https://github.com/deeptelar/telar).

Comment on an issue before you start, so that two people do not write the same thing.

## Getting started

Requirements: JDK 17 or newer, and Chrome or Chromium for the tests in the browser (set
`CHROME_BIN` to its path if it is not found). The Gradle wrapper downloads everything else.

```bash
git clone git@github.com:deeptelar/telar-foodie.git
cd telar-foodie
./gradlew check
```

| Command | What it does |
|---|---|
| `./gradlew check` | Runs every test on the JVM and in headless Chrome. Run it before you open a pull request |
| `./gradlew jvmTest` | Fast feedback: the tests on the JVM only |
| `./gradlew jvmTest --tests '*TextsTest*'` | A single test class |
| `./gradlew jsBrowserDevelopmentRun --continuous` | The page at `http://localhost:8080`, rebuilt when a file changes |
| `./gradlew jsBrowserDistribution` | The page as files, in `build/dist/js/productionExecutable` |

You need no key to work on the app. **Try the sample** on the page plans two days in Lisbon with a
script in place of the model and recorded answers in place of the sources, and the tests run the
whole planner the same way. To try a change with a real model, add your own key under
**Settings**; the README's [Run it](README.md#run-it) says which providers work.

## Writing a change

- **Match the code around it:** its naming, its idiom, and comments that say why rather than what.
- **Texts the traveller reads** go in `Texts.kt` and in each of its three languages
  (`TextsEnglish.kt`, `TextsSpanish.kt`, `TextsCatalan.kt`). The build fails when a language is
  missing one, and `TextsTest` checks that its questions and sample are complete. If you do not
  speak a language, say so in the pull request and someone will help.
- **The planner** is tested with `ScriptedChatModel` from `telar-test` and canned answers of the
  sources, in `src/commonTest`. A change to the planner, the tools or the prompts comes with a test
  there.
- **The free services** (Nominatim, Overpass, Wikivoyage) are run by volunteers. Do not add tests
  that call them on every run: the tests that ask them only run when asked for, such as with
  `FOODIE_LIVE=1`.
- **A change to the page** comes with a screenshot at phone width in the pull request.
- **Never commit a key.** The page keeps keys in the browser, and the tests need none.

## Commits and pull requests

Commit messages start with the area and a sentence, and the body says why:

```
Sources: Ask the next server when Overpass says in a remark that it failed
```

Keep one change to a pull request, and link the issue it closes.

## License

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE), like the rest of the project.
