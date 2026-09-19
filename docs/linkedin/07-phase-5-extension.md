# Part 8 — Phase 5, extensions and the one I didn't build

**Publish when:** whichever phase 5 items actually shipped are live. This milestone is conditional
by design — trim the post to what's true.
**Length:** ~2,600 characters
**Hook:** paying off part 7's cliffhanger with the unusual answer: the argument ended in a no.
**Opens by:** the "one feature I'm not sure I should build" from part 7.
**Ends by:** closing the series — the retrospective, and the one lesson that transfers.

---

Last post I said the final Kitbash milestone included one feature I wasn't sure I should build at all.

I didn't build it. The write-up explaining why is reviewed and merged in the repository, and I'd call it one of the better outcomes of the project.

Four things shipped. One got argued out.

✅ Shipped: a recipe authoring kit. A published manifest schema with editor completion, a local harness that renders one recipe and checks its patches without booting the server, and a command that turns an existing project into a recipe skeleton. The test I set was that someone who'd never touched the codebase could add a working recipe using only the documentation. They did — and everywhere they got stuck went straight back into the docs.

✅ Shipped: a terminal client. It fetches the same catalog document the web wizard renders itself from, and prompts you through it. Adding a recipe changes the prompts with no release of the client — the same property the web app has, from the same endpoint, which is the payoff of a decision made back in part 4.

✅ Shipped: React Native, with honest verification. A mobile cell builds a bundle and runs component tests. It does not claim the app runs on a device, because the matrix can't back that claim, and a green badge that overstates what was checked is worse than no badge.

✅ Shipped: pushing straight into a Git group instead of downloading a zip. The pushed initial commit is byte-identical to the zip's — which is only a checkable statement because of the determinism decision from part 3.

❌ Not shipped: user-submitted recipes.

I flagged it as conditional from the start, and mentioned in part 5 that it was the one case that would justify a database-backed catalog. When I wrote the threat model honestly, the justification wasn't there. Accepting other people's recipes means executing other people's templates: a separate process, no network, read-only filesystem, hard resource caps, a review workflow, provenance, revocation. That's its own product, not a checkbox on this one.

🧱 Eight posts, six milestones, one bet. The bet was that the engine matters more than the catalog — that you get composition right first and breadth becomes a weekend.

That held. The milestone that decided everything was the fourth post, not the sixth, and the work that made breadth cheap was the work that produced nothing a user could see.

🙏 Thanks for following along. The questions in the comments genuinely changed the design twice.

🧱 Building Kitbash — part 8 of 8.

#SoftwareEngineering #SoftwareArchitecture #DeveloperExperience #DevTools #PlatformEngineering

---

## Notes

- **Trim to reality.** If user-submitted recipes do get built, the framing inverts — lead with
  the sandbox and the escape-attempt test suite, and the series ends on a different note.
- If only two of the four items ship, drop the others silently rather than listing them as
  planned. A retrospective that lists unfinished work reads as an apology.
- "The questions in the comments changed the design twice" should be true. If it is, name the
  two changes in a reply rather than the post — it rewards the people who engaged and gives the
  post a second life in the feed.
- The callbacks to parts 3, 4 and 5 are what make this land as a series finale. If you cut any
  of those earlier posts, cut the matching callback here too.
