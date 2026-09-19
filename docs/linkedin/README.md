# LinkedIn posts — an eight-part series

One story told across eight posts. Each one opens by picking up where the previous stopped and
closes by setting up the next, so a reader who follows all eight gets a narrative rather than
eight status updates — and a reader who only sees part 6 still understands it.

| # | File | Publish when | Picks up | Hands off |
| --- | --- | --- | --- | --- |
| 1 | [00-announcement.md](00-announcement.md) | First | — sets the premise | "the obvious way doesn't work" |
| 2 | [01-kickoff.md](01-kickoff.md) | A few days later, while part 1 has engagement | Answers to part 1's question | "the first milestone is deliberately unambitious" |
| 3 | [02-phase-0-walking-skeleton.md](02-phase-0-walking-skeleton.md) | Zip unzips and the build passes in CI | Why unambitious was right | "next is the one that decides whether this survives" |
| 4 | [03-phase-1-recipe-engine.md](03-phase-1-recipe-engine.md) | Four cells green nightly; a recipe needs no frontend commit | That survival claim | "should a saved template track upgrades or stay frozen?" |
| 5 | [04-phase-2-persistence.md](04-phase-2-persistence.md) | Generate → preset → cold reload → identical zip from cache | That question | "the bet was breadth is a weekend" |
| 6 | [05-phase-3-catalog-breadth.md](05-phase-3-catalog-breadth.md) | ~96 cells green nightly under 20 min | Settles the bet with a diff | "every badge here was produced by me, on my schedule" |
| 7 | [06-phase-4-validation-polish.md](06-phase-4-validation-polish.md) | A user can verify their own combination and read the log | "I was fairly sure I couldn't afford to" | "one feature I'm not sure I should build" |
| 8 | [07-phase-5-extension.md](07-phase-5-extension.md) | Only for the parts actually shipped | How that argument ended | Closes the series |

Each file's header names its own **Opens by** and **Ends by**, so if you rewrite one post you
can see immediately which two neighbours need adjusting.

## How the series holds together

- **Every post ends with `Building Kitbash — part N of 8`** and, from part 2 onward, "Earlier
  posts are linked in the comments." Drop the links in a first comment right after publishing;
  LinkedIn suppresses reach on posts with outbound links in the body.
- **Open loops carry the series.** Parts 1, 4, 6 and 7 each end on an unanswered question or an
  admitted doubt that the next post resolves in its first two lines. Those hand-offs are the
  reason someone comes back, so if you cut a post, re-point the loop rather than leaving it dangling.
- **Each post re-establishes just enough context to stand alone.** Part 6 restates the
  96-combination problem in one clause; part 8 restates what user-submitted recipes would cost.
  The feed is not sequential and most readers will land mid-series.
- **Callbacks name the part number** ("the determinism decision from part 3"). It signals a
  series to a new reader without a recap paragraph.
- **The count is a commitment.** Part 1 says "part 1 of 8." If you decide to skip the phase 5
  post, change it to 7 *before publishing part 1* — the series is conditional at the end, and
  the numbering has to reflect whichever choice you make.

## Before posting

- **Emoji are anchors, not decoration.** Each one leads a paragraph that functions as a list
  item, so the post skims well without turning into an ad. Five to eight per post; never two in
  one paragraph, never one mid-sentence. The key:

  | | Means | | Means |
  | --- | --- | --- | --- |
  | 🧱 | Kitbash itself, and every series footer | ✅ | Shipped, green, passing |
  | 🧩 | Composition, recipes, capabilities | ❌ | Deliberately not built |
  | ⚙️ | Engine internals | 📦 | Packaging, zips, caching |
  | 🔒 | Security and sandboxing | ⏱️ | Limits and timing |
  | 🧾 | Receipts, locks, evidence | 💬 | A question for the reader |

  Keep 🧱 in the footer of every post even if you strip the others — it is what makes the
  series recognizable when it scrolls past.
- **LinkedIn does not render Markdown.** The bodies are plain text on purpose — no `**bold**`,
  no `#` headings, no `-` bullets that would appear as literal characters. Copy the block
  between the two `---` rules verbatim.
- **The first two lines are the hook.** Everything past roughly 210 characters sits behind
  "see more", so every post front-loads its claim and its callback.
- **The 3,000-character limit is real** and parts 4 and 5 sit at ~2,900. LinkedIn's counter
  charges some emoji two characters, so the real headroom on those two is thinner than it
  looks — if you add a paragraph, cut one.
- **Publish a phase post only when its exit test actually passes.** Every claim in these drafts
  is backed by a criterion in `docs/tasks/`; a post that runs ahead of the work is the one
  people remember.
- **Numbers are the point.** Every figure comes from the plan or a phase exit test. If reality
  differs when you publish, change the number rather than dropping it.
- Parts 2 and 5 quote reactions to the previous post. If a post doesn't get comments, each file
  carries a fallback opener in its notes — don't invent engagement you didn't get.
- Swap the hashtag block for whatever your network actually follows; five is plenty.
