# Part 5 — Phase 2, presets, locks and history

**Publish when:** generate → save preset → cold reload → one-click regenerate returns an identical zip from cache.
**Length:** ~2,900 characters
**Hook:** answering the question part 4 closed on, by refusing to pick a side.
**Opens by:** part 4's question about whether a saved template should track upgrades.
**Ends by:** setting up part 6 — the bet that breadth is cheap once the engine is right.

---

Last post I left you with a question: should a saved project template follow framework upgrades, or stay frozen exactly as you left it?

The answers in the comments split almost evenly, which is what finally told me I'd asked it wrong. Both answers are right — about different objects.

📌 A preset is a team's house standard. "Give me our standard service again." A preset frozen on Spring Boot 3.2 is a trap, so presets track the latest catalog by default. Pinning stays available per preset, for compliance cases where it genuinely matters.

🧾 A generation is a receipt. Every single render records a lock: the exact resolved version of every recipe, plus a digest identifying the catalog that produced it. From history you can regenerate exactly, replaying that lock, or regenerate current and re-resolve against today's catalog.

That split gives reproducibility without staleness, which neither answer achieves alone. It also makes "this used to work" debuggable — diff two locks and the reason is usually right there.

🗂️ So Kitbash now has three nouns, and the codebase never confuses them. A Recipe is maintainer-owned and lives in git. A Preset is user-owned and lives in the database. A Generation is system-owned and immutable.

🗄️ One decision that surprised people when I explained it in person: the catalog deliberately stays out of the database. Recipes live in the repository, validated at startup, held in memory — reviewable, diffable, versioned alongside the code that renders them. Putting them in tables buys an admin CRUD screen and a migration for every recipe change. It's only genuinely needed for user-submitted recipes, which is a separate product with real security weight. That one comes up again in the final post.

Also shipped: single sign-on, presets with private/team/public visibility, share links where the URL itself is the default form and a short token is the fallback, a preview that shows you the file tree and any file's real rendered contents before you download anything, and a content-addressed zip cache.

📦 That cache is only safe because of the decision from part 3: two identical selections produce byte-identical zips, so a cache hit can be served verbatim.

🗓️ And one retention number across everything — thirty days for cached zips, generation records and logs alike, with a per-item Keep exemption. Three separate policies would be three things nobody can recall.

The engine's been carrying two technologies this whole time. The bet from the beginning was that breadth is cheap once the engine is right — a weekend, not a quarter. Next post is where I find out whether that was true, because I'm adding a second backend language, a second build tool, two more architectures and a typed API client.

🧱 Building Kitbash — part 5 of 8. Earlier posts are linked in the comments.

#SoftwareEngineering #SoftwareArchitecture #API #Postgres #DeveloperExperience

---

## Notes

- **The "split almost evenly" line assumes part 4 actually got comments.** If it didn't, use:
  "I asked a few people and got a clean split, which is what finally told me I'd asked it
  wrong." Don't invent engagement you didn't get.
- The catalog-in-git paragraph is the one people push back on, and it now explicitly forward-
  references part 8. That's deliberate: it makes the last post feel planned rather than tacked on.
- Only one closer — the forward tease. If you'd rather end on a question, cut the tease and
  move it to part 6's opener instead.
