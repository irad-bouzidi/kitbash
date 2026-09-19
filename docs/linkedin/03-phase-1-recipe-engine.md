# Part 4 — Phase 1, the recipe engine

**Publish when:** four matrix cells are green nightly and a recipe can be added with no frontend commit.
**Length:** ~2,900 characters
**Hook:** the claim part 3 set up — the frontend changed without a line of frontend code.
**Opens by:** the "milestone that decides whether this survives" framing from part 3.
**Ends by:** setting up part 5 with a real design question — should a saved template track
upgrades or stay frozen?

---

Last post I said the next milestone would decide whether Kitbash survives its first year.

It shipped. Here's the clearest evidence I can give you that it worked: I added a technology yesterday, the wizard changed to include it, and I didn't write a line of frontend code.

The rule the whole engine is built on is the one from part 2 — never store a template per stack combination. Every selectable thing is a recipe: a directory of templates plus a manifest declaring what it provides and what it requires.

🧩 Capabilities are the glue. The Spring Boot recipe provides http-server, rest-api, openapi-spec. The React recipe requires rest-api. A resolver validates the selected set structurally, which means there is no hand-maintained compatibility matrix anywhere in the codebase. That matrix is exactly the artifact that rots.

⚙️ The resolver is a pure function. Selection in, ordered recipe list and diagnostics out. No I/O, no clock, no framework. Its test suite runs in under five seconds, which matters because it's the one component with real logic and the one where a regression stays invisible until someone's generated project fails to compile.

🔧 Composition is where it gets real. I mentioned typed patches last time; they're now eight operations. Insert a dependency into the right block with deduplication. Deep-merge YAML and fail loudly on a collision rather than picking a winner. Union JSON arrays. Add an npm script and refuse a name collision. Insert at a marker the owning recipe placed. Add an environment variable to two files in one operation. Every patch is idempotent, and a patch targeting a file no selected recipe produced fails immediately with a message naming the recipe and the fix — never silently.

🪄 Then the part that produced the opening claim. The server exposes one endpoint carrying the entire catalog: option groups, types, defaults, labels, help text, dependency rules, versions. The web wizard renders itself from that document. There is exactly one switch statement in the whole frontend, and it switches on option type, never on technology name. Adding a technology is a backend-only change.

🔒 Also in: a sandboxed template engine with reflection removed, path traversal defences, resource caps enforced before a single byte is rendered, an offline CLI, and the verification matrix generating each combination and running its real build in a container. Four combinations green nightly. Small number, right machine.

💬 Next milestone is where it stops being an engine and starts being a product — and it opens with a question I couldn't answer cleanly for a week. If you save your team's standard stack as a template, should it follow framework upgrades, or stay frozen exactly as you left it?

🧱 Building Kitbash — part 4 of 8. Earlier posts are linked in the comments.

#SoftwareEngineering #SoftwareArchitecture #Java #DeveloperExperience #DevTools

---

## Notes

- The opener is the whole post. If the throwaway-recipe demo can be recorded, attach a
  20-second screen capture of the wizard changing after a server restart — it lands harder
  than the paragraph.
- Update "four combinations" if the matrix is bigger by publication. Don't round up.
- The closing question is a genuine open loop and a strong comment prompt. People will answer
  it, and part 5 opens by agreeing with both sides.
