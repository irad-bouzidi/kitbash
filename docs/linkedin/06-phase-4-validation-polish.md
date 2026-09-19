# Part 7 — Phase 4, verification for everyone

**Publish when:** a user can trigger a verification of their own combination from the UI and read the build log.
**Length:** ~2,800 characters
**Hook:** resolving part 6's closing worry — I was wrong about not affording it, and the reason
is the most transferable idea in the series.
**Opens by:** the "I was fairly sure I couldn't afford to" line from part 6.
**Ends by:** setting up part 8 — the optional milestone, and the feature that might not get built.

---

Last post I admitted I wanted to let every user run their own build verification, and was fairly sure I couldn't afford it.

I was wrong, and the reason is the most useful thing I've learned building Kitbash.

🟢 The feature is a button labelled "Verify this build." Press it and the server generates your exact selection, unzips it into a disposable container with no network beyond a dependency proxy, runs the real build and test commands, and shows you the log. Green or red, with evidence.

Handing that to every authenticated user sounds reckless. It isn't — because the work is deduplicated rather than cheap.

🔁 Every run is keyed by the hash of your selection plus the digest of the catalog that would render it. The second person to verify the house stack gets the first person's result instantly. And the nightly matrix from last post has already pre-populated every enumerated combination, so most requests for common stacks are green before anyone asks.

What's left is the genuinely novel combination — which is exactly the case worth spending a container on.

⏱️ So the controls are concurrency, not quota: one running job per user, four globally, a fifteen-minute hard timeout, and a queue-depth cap that returns an honest error with the current depth rather than silently queueing you for an hour. Verification never runs on the API host.

This is also the only asynchronous job in the entire system. Generation is synchronous and streamed, because rendering takes tens of milliseconds and a queue would be complexity with no payoff. Build validation genuinely takes minutes. One exception, earned.

Two more things landed that I'd put in any tool of this shape.

🏷️ Verification results became badges in the wizard, placed at the specific option pairing that's red — not a banner at the top of the page. The information belongs where the decision gets made.

🧭 And errors became a surface instead of a stack trace. Every failure names the stage, the recipe and the file, and carries a hint naming the next action: "this feature requires an HTTP server; select a backend." Typed values in the domain layer, not strings assembled at the controller, and the interface renders them verbatim.

One more, quieter: the nightly matrix now also tests the most-used real selections pulled from actual generation history, not only the combinations someone thought to enumerate. Test what people build, not what you imagined they'd build.

That's the product finished. The last milestone was always the optional one — four extensions and one feature I'm not sure I should build at all. Next post is how that argument ended.

🧱 Building Kitbash — part 7 of 8. Earlier posts are linked in the comments.

#SoftwareEngineering #DeveloperExperience #CI #Observability #DevTools

---

## Notes

- The dedupe explanation is the transferable idea in this post — it generalizes well beyond
  Kitbash, which is what makes it worth reading for people who'll never use it.
- "I was wrong" in line two is doing real work. Don't soften it into "it turned out to be
  affordable."
- If the CLI binary shipped in the same milestone, add one line rather than a paragraph; it's
  the least interesting item in the set.
