# Part 2 — The design

**Publish when:** a few days after [part 1](00-announcement.md), while it still has engagement.
**Length:** ~2,700 characters
**Hook:** quoting the answers to part 1's question, then closing the "obvious way doesn't work"
loop with arithmetic.
**Opens by:** the replies to part 1.
**Ends by:** setting up part 3 — the first milestone is deliberately unambitious.

---

I asked how your team sets up a new service. The most common answer, by a distance: "we copy the last repo and delete the parts we don't need."

Second most common: "we have a template, but nobody's touched it since 2024."

Both of those are the problem Kitbash is trying to delete. So let me close the loop I left open last week — why the obvious way to build it doesn't work.

The obvious way is a template per stack combination. Spring Boot with React with Postgres and GitLab CI, checked into a folder. Then the next combination, in another folder.

🧮 Do the arithmetic: 2 backends × 3 architectures × 2 build tools × 2 frontend states × 2 auth options × 2 CI providers is around 96 trees to maintain. Add one backend and it multiplies again. Every Spring Boot release touches all of them.

That's not a maintenance burden, it's a death sentence with a delay on it. It's why most scaffolding projects are abandoned about a year in.

🧩 So Kitbash composes instead. Every selectable thing is a recipe: a folder of templates plus a manifest declaring what it provides and what it requires. The React recipe says "I require a REST API." The Spring Boot recipe says "I provide one." A resolver checks the selected set structurally and assembles a single file plan out of the parts.

⚙️ The hard bit is that several recipes need to modify the same file. Adding auth touches the backend, the frontend router, the compose file and the CI pipeline at once. So shared files get modified by typed, format-aware operations — insert a dependency into the right block with deduplication, deep-merge YAML and fail loudly on a collision, add an environment variable to .env.example and compose.yaml in one operation. Not string appending. String appending produces broken syntax within a week.

Three constraints I committed to before writing any code:

1️⃣ Recipe content is derived from real projects that compile, not written as template soup. A test asserts the rendered output matches the checked-in reference project byte for byte.

2️⃣ Two identical selections produce byte-identical zips. That's what makes caching safe and old builds reproducible.

3️⃣ Every combination gets generated, unzipped and actually built in a container, nightly. Not a snapshot test of rendered text — the real build command.

✅ The bar for done: unzip, run one command, it works.

Six milestones planned. The first one is deliberately unambitious — one hardcoded stack, no engine at all — and I'll explain why that was the right call next time.

🧱 Building Kitbash — part 2 of 8. Part 1 is linked in the comments.

#SoftwareEngineering #SoftwareArchitecture #Java #SpringBoot #PlatformEngineering

---

## Notes

- **Replace the two opening quotes with real answers from part 1's comments** if you get good
  ones. Quoting your own network by paraphrase is far stronger than inventing the line, and it
  visibly rewards the people who replied.
- If nobody comments on part 1, the opener still works as written — "the most common answer"
  is true of the industry regardless.
- The 96-combination arithmetic is the load-bearing paragraph of the entire series. Every later
  post assumes the reader has it.
- Ends on a small open loop ("why unambitious was the right call") that part 3 pays off in its
  first line.
