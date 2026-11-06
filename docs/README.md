# Developer notes

The notes kept while the editor was built: what the game does with a save,
measured on real saves or read out of the decompiled game, and what went wrong
whenever that was guessed at instead. Start with
[CONTRIBUTING.md](../CONTRIBUTING.md) for the short version;
[ROADMAP.md](../ROADMAP.md) has what is planned and what was decided against.

| Document | What is in it |
|---|---|
| [save-format.md](save-format.md) | How a save is put together, and where each thing the editor changes is kept |
| [invariants.md](invariants.md) | The rules an edit has to keep, each with the fault that taught it |
| [glossary.md](glossary.md) | The words the code uses for what is in a save (object, component, variable, tree, mirror, the globals) and for what the editor does to one |
| [features.md](features.md) | One entry for each feature: where its data lives, what the game does with it on load, what the editor writes, and how that was checked in the game |
| [testing.md](testing.md) | Working test-first, and loading an edited save in the game itself |
| [release.md](release.md) | How the Windows release is built and what it has to carry |
| [lessons.md](lessons.md) | What each pass over the whole editor found, in the order they were made |

`screenshots/` holds the pictures the README shows
(`tools/ui-tests/screenshots.py` draws them).
