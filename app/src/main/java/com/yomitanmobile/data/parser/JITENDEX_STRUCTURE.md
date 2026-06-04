# Jitendex structured-content reference

Captured by sampling the public `jitendex-yomitan.zip` release on
**2026-05-23** (≈10k entries across multiple term banks). Use this as the
canonical reference whenever the parser needs to interpret a new
`data-content` value — guessing from prior assumptions has burned us
before.

## Top-level layout

The `structured-content.content` field is either a single object or an
array of objects. Two top-level wrapper shapes occur, often side-by-side:

```
structured-content
  content: [
    <sense-group block>          # one or more, see A or B below
    <sense-group block>
    ...
    div[data-content=forms]      # optional: alternate spellings
    div[data-content=attribution]  # optional: source link
  ]
```

The two sense-group wrapper shapes:

### Shape A — `div[data-content=sense-group]` (most common, single-meaning words)

```
div[data-content=sense-group]
  ├── span[data-content=part-of-speech-info, data.code=N]   (≥0)
  ├── span[data-content=misc-info,           data.code=N]   (≥0, e.g. "abbr.")
  ├── span[data-content=dialect-info,        data.code=N]   (≥0)
  ├── span[data-content=field-info,          data.code=N]   (≥0)
  └── div[data-content=sense]                                (≥1)
```

The senses are **direct children of `sense-group`** — there is no `<ol>`
wrapper around them.

### Shape B — `ul[data-content=sense-groups]` (multi-meaning words)

```
ul[data-content=sense-groups]
  └── li[data-content=sense-group, style.listStyleType="\"①\""]
        ├── (same chip spans as in Shape A)
        └── div[data-content=sense]
```

Each `<li sense-group>` can carry a CSS `listStyleType` like `"①"`/`"②"`
that tells the original Yomitan UI which numeral to render.

Both shapes nest the same `sense` structure inside.

## Sense node

```
div|li[data-content=sense]
  ├── ul[data-content=glossary]
  │     └── li (string content — one per gloss)
  └── div[data-content=extra-info]   (optional, may contain ≥1 box)
        └── (nested div wrappers; eventually one or more "extra-box" items)
```

### `extra-info` / `extra-box` variants

Each extra-box is a `div` with `data.class == "extra-box"` and a specific
`data-content` value. The boxes we have seen:

| `data-content`     | Label                | Content                                   |
|--------------------|----------------------|-------------------------------------------|
| `example-sentence` | (none — paired a/b)  | JP + EN example pair (handled separately) |
| `xref`             | "See also" (`reference-label`) | `<a>` link + `xref-glossary` text |
| `antonym`          | "Antonym"            | `<a>` link + `antonym-glossary` text      |
| `sense-note`       | "Note"               | Free-form prose note                      |
| `lang-source`      | "Language of Origin" | Etymology (e.g. `English: "line robbing"`, may carry `lang-source-wasei`) |
| `info-gloss`       | "Explanation"        | Encyclopedic gloss of the term            |

Each non-example extra-box has the same internal layout:

```
div[data-content=<box>, data.class="extra-box"]
  ├── div[data-content=<box>-label,   data.class="extra-label"]   "Note" / "See also" / …
  └── div[data-content=<box>-content, data.class="extra-content"]  free-form text or
                                                                   reference markup
```

For `xref` / `antonym` the "content" is a flat sequence: a
`span[data-content=reference-label]` ("See also" / "Antonym") followed by
one or more `<a href="?query=…">` link spans.

## Top-level siblings (outside sense-groups)

```
div[data-content=forms]                  alternate spellings & readings
  ├── span[data-content=forms-label]     "forms"
  └── ul → li (one per variant string)

div[data-content=attribution]            source link
  └── a[href=...] "JMdict"

span[data-content=attribution-footnote]  "[1]" markers in examples
```

## Complete `data-content` catalogue (≈10k entries sampled)

```
antonym            antonym-content        antonym-glossary
attribution        attribution-footnote
dialect-info       example-keyword
example-sentence   example-sentence-a     example-sentence-b
extra-info         field-info
forms              forms-col-senses-row   forms-header-row
forms-label        forms-row-senses
glossary           graphic                graphic-attribution
info-gloss         info-gloss-content     info-gloss-label
lang-source        lang-source-content    lang-source-label   lang-source-wasei
misc-info          part-of-speech-info
redirect-glossary  reference-label
sense              sense-group            sense-groups
sense-note         sense-note-content     sense-note-label
xref               xref-content           xref-glossary
```

## Implications for the parser

1. The sense-aware walker **must accept Shape A** (top-level
   `div[data-content=sense-group]`, no `sense-groups` wrapper, no `<ol>`
   around senses). Until 2026-05-23 our walker only matched Shape B and
   silently fell through to legacy flat-text extraction — that is why
   notes, cross-refs, forms, and attribution all leaked into the meaning
   column for most entries.

2. Note-worthy content lives in dedicated `extra-info` boxes. We extract
   them by `data-content` value, not by walking and hoping. The mapping
   to the UI Notes card:

   - `sense-note`   → "Note: <content>"
   - `xref`         → "See also: <link text>"
   - `antonym`      → "Antonym: <link text>"
   - `lang-source`  → "Language of Origin: <content>"
   - `info-gloss`   → "Explanation: <content>"

3. `forms`, `attribution`, `attribution-footnote`, and `graphic*` are not
   notes — they are top-level metadata or footer markers. They must not
   land in either the meaning column or the notes card.
