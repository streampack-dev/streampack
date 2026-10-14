# operation-dictionary

`operation-dictionary` provides `define <word>`: a definition from the first dictionary that answers, with AI as a last resort, each credited as its source asks.

## Operations

| Operation | Command / payload | Purpose |
|-----------|-------------------|---------|
| `DictionaryOperation` | `define <word>` or `DictionaryRequest` | Looks up a definition and returns a short formatted response. |

## Behavior

The operation runs at priority `95`, after normal factoid lookup. This lets known definitions be served from factoids first. On a successful lookup, it seeds a factoid through `EventGateway` so future lookups can hit the cache.

### Where definitions come from (#202)

`DictionaryLookupService` asks each `DictionaryProvider` in order. The first that finds the word answers, and the first that says there's no such word ends the lookup without asking the rest. A provider that fails (it doesn't answer within the timeout, or answers with an error, a rate limit or something it can't read) is skipped for a while, and the next one is asked.

| Order | Provider | Data | Credit |
|-------|----------|------|--------|
| 1 | [FreeDictionaryAPI.com](https://freedictionaryapi.com) | Wiktionary, CC BY-SA 4.0; 1,000 requests an hour per address | `(Wiktionary via FreeDictionaryAPI.com, CC BY-SA 4.0: <Wiktionary page>)`: its terms ask for a visible credit and the page's link |
| 2 | [dictionaryapi.dev](https://dictionaryapi.dev) | Wiktionary, CC BY-SA; down since 2026-10-12, kept for when it returns | `(Wiktionary via dictionaryapi.dev, <license>: <Wiktionary page>)` |
| 3 | AI, on `AI_MODERATION_MODEL` (Haiku by default) | Only when AI is on, and only once both dictionaries have failed | `(AI-generated)` |

The AI provider must refuse a word that isn't an established English word or phrase. At most 60 lookups an hour go to it, so an outage can't run up the bill.

Every reply, and every factoid it seeds, ends with its credit. A definition too long to fit in a factoid with its credit (512 characters) is answered but not cached.

### Outages

Each provider has a short timeout (`DICTIONARY_TIMEOUT`, 3 seconds) and a circuit breaker: after a failure it is skipped for `DICTIONARY_BREAK_FOR` (15 minutes), then asked again. Both a provider tripping and its recovery are logged at WARN, as `Dictionary <name> failed (<reason>); skipping it for PT15M` and `Dictionary <name> is answering again`, so an outage shows up in the log the first time it happens. When no provider can answer, `No dictionary could answer for '<word>'` is logged and the command goes unanswered, as for an unknown word.

Considered and left out: Datamuse, which needs an API key from 2027-02-01; Merriam-Webster, whose terms forbid copying its content and commercial use, while `define` keeps its answers as factoids; and a bundled WordNet, which would have to be a service of its own.

The operation is addressed and uses operation group `dictionary`.

## Example Flows

- Look up a word:
  `define ubiquitous`
- Repeat the same lookup later:
  the first result seeds a factoid, so later requests can be served from cached data
