# Frontend Handoff: Product Autocomplete

The Spring Boot backend now owns product-fragment detection, matching, ranking, and replacement boundaries for command autocomplete. Expo should send the current draft and cursor position, display the returned suggestions, and replace only the range returned by the backend.

## Endpoint

```http
GET /api/products/suggestions
token: <existing Aigul session token>

?text=<URL-encoded draft>
&cursor=<UTF-16 offset>
&context=COMMAND
&limit=5
```

- `text` is required and may contain at most 500 UTF-16 code units.
- `cursor` is required and must be between `0` and `text.length`, inclusive.
- JavaScript and React Native string indices are UTF-16, so use the editor selection offset directly. Do not convert it to Unicode code points.
- `context=COMMAND` is required. `SEARCH` is reserved for a later search-field integration and currently returns HTTP 400.
- `limit` is optional, defaults to `5`, and must be between `1` and `10`.
- Authentication uses the existing `token` request header, not an `Authorization: Bearer` header.

Example request:

```text
GET /api/products/suggestions?text=add%202%20atlas%20p%20to%20Bishkek&cursor=13&context=COMMAND&limit=5
token: 123e4567-session-token
```

## Response

```json
{
  "replacementStart": 6,
  "replacementEnd": 13,
  "commonCompletion": "Atlass Plisse",
  "suggestions": [
    {
      "productId": 2,
      "name": "Atlass Plisse Barbie Blue"
    },
    {
      "productId": 3,
      "name": "Atlass Plisse Barbie Black"
    }
  ]
}
```

- `replacementStart` and `replacementEnd` are UTF-16 offsets into the exact `text` sent in the request.
- The replacement range contains only the selected product fragment. It excludes quantities, operations, locations, separators, and other products.
- `name` is the canonical product name stored in the authenticated user's catalog.
- `commonCompletion` contains complete leading words shared by every returned suggestion. It is `null` when there is no useful shared completion.
- Suggestions are already deterministically ranked. Expo should not reorder or fuzzy-filter them.

An empty result looks like this:

```json
{
  "replacementStart": 13,
  "replacementEnd": 13,
  "commonCompletion": null,
  "suggestions": []
}
```

The backend returns an empty result when the fragment is blank, has fewer than two normalized letters, is already an exact product name, or has no match. Clear or hide the autocomplete UI when `suggestions` is empty.

## Applying a Suggestion

Always replace the returned range; do not append the product name to the entire command.

```ts
type ProductSuggestion = {
  productId: number;
  name: string;
};

type ProductSuggestionResponse = {
  replacementStart: number;
  replacementEnd: number;
  commonCompletion: string | null;
  suggestions: ProductSuggestion[];
};

function applyProductCompletion(
  draft: string,
  response: ProductSuggestionResponse,
  completion: string,
) {
  const nextDraft =
    draft.slice(0, response.replacementStart) +
    completion +
    draft.slice(response.replacementEnd);

  const nextCursor = response.replacementStart + completion.length;
  return { nextDraft, nextCursor };
}
```

Use the same replacement function when accepting `commonCompletion` or a selected suggestion name. After replacement, restore the input selection to `{ start: nextCursor, end: nextCursor }`.

Before applying a response, confirm that it still belongs to the current draft/cursor request. If the user typed again while the request was in flight, discard the stale response.

## Recommended Expo Flow

1. Observe the command input text and collapsed cursor selection.
2. Debounce requests and cancel or ignore stale requests when text/cursor changes.
3. Send the complete draft, current `selection.start`, `context=COMMAND`, and the existing session token.
4. Hide suggestions for 400 responses and use the existing sign-out/session-expired flow for 401 responses.
5. Render the backend order unchanged. Optionally show `commonCompletion` as a shared completion action or ghost text.
6. On selection, replace exactly `[replacementStart, replacementEnd)` and move the cursor after the inserted canonical name.

The frontend does not need to identify command verbs, quantities, locations, product separators, completed products, typos, or multi-product boundaries. That logic is backend-authoritative. Repeating the same product in a later command fragment is allowed.

## Backend Behavior Already Covered

- Case-insensitive and diacritic-insensitive matching, including Cyrillic names.
- Exact phrase, full-phrase prefix, sequential word-prefix, and bounded typo-tolerant ranking.
- Multi-product commands separated by commas, conjunctions, and newlines.
- Cursor placement inside a product fragment in the middle of a command.
- Exact product names treated as completed.
- Authenticated user isolation and a maximum of 200 lightweight ID/name candidates per request.

Expo integration itself has not been implemented in this backend phase.
