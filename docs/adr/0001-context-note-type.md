# Onomatopoeia and grammar points get a context note type, instead of a vocabulary card

An onomatopoeia — and, since the same day, a grammar point — is learnt in the sentence it was met in, so it gets a card whose front is that sentence (word underlined) and whose back holds the user's own translation and a definition — a separate note type, `Yomitan-Mobile-Context`, rather than extra fields and a second template on `Yomitan-Mobile-v8`. New fields on v8 would change `FIELD_NAMES` and force a v9 for every card (and v8's name is what keeps the collection to one note type, after 1 144 strays had to be merged). The name deliberately does not start with `Yomitan-Mobile-v8`: the refresher treats that name and anything after a hyphen as the vocabulary type, and would restyle this one with the vocabulary templates.

## Consequences

- One word, one card still holds: an onomatopoeia gets this card *instead of* a vocabulary card, and only when the sentence it was looked up in really contains it.
- Moving these notes onto another type later means desktop Anki's Change Note Type; the provider cannot change a note's type without losing review history.
- The card has a short `Word` field so the duplicate scan sees it, and a `Frequency` field so AutoReorder orders it with the vocabulary cards.
- One type for both kinds, told apart by tag (`onomatopoeia`, `grammar`): the layout is the same, and a note type per kind is how a collection ends up with a dozen of them. It was named `Yomitan-Mobile-Onomatopoeia` for a few hours and renamed before any collection held it.
