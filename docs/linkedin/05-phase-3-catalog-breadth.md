# Part 6 — Phase 3, catalog breadth

**Publish when:** the nightly matrix runs ~96 cells green under 20 minutes and the DTO typecheck test is wired.
**Length:** ~2,800 characters
**Hook:** settling the bet part 5 ended on, with a diff.
**Opens by:** the "breadth is a weekend" bet from part 5.
**Ends by:** setting up part 7 — everything has been verified by me, on a schedule I control.

---

Last post I said the whole bet was that breadth gets cheap once the engine is right. Here's the receipt.

🧾 I added a second backend language to Kitbash last week. The merge request touched four directories: recipes, reference projects, verification cells, docs.

Zero lines in the generation engine.

That's the payoff of the two milestones before it. Breadth added to a sound engine is a weekend. Breadth added to an unsound one is the reason these projects die — which is the 96-combination arithmetic from part 2, arriving on schedule.

What landed: a Kotlin backend, Maven alongside Gradle, JWT authentication, observability, GitHub Actions alongside GitLab CI, and two more architectures.

🏗️ The architectures are the part I'd argue about publicly. An architecture option that only renames folders is worse than no option at all — it's a lie the generator tells you. So hexagonal emits a framework-annotation-free domain with explicit ports and adapters, modular monolith emits per-feature packages with one public API class each, and every generated project ships an architecture test that fails its own build if you violate the structure.

The architecture isn't a folder layout you're trusted to maintain. It's a check that runs in your pipeline.

That's also why Clean isn't in the catalog. As usually implemented it emits the same tree as hexagonal, and two options producing one result is a menu, not a choice.

🔗 The feature I'm happiest with is smaller and less visible. When you select both a backend and a frontend, the backend publishes its API contract, a build task generates a TypeScript client from it, and the generated page consumes that client. Change a field on a backend object, regenerate, and the frontend fails to typecheck at the exact call site.

Without that, a full-stack generator is two unrelated folders in a zip.

✅ Underneath it all: roughly 96 combinations, enumerated from the catalog itself rather than a hand-written list, sharded, generated, unzipped and actually built in containers every night in under twenty minutes. Real builds, real tests, real linting of the emitted CI pipelines.

♻️ Plus a weekly job that bumps framework and library versions across every recipe, runs the full matrix, and opens a merge request only when it's green. Scaffolding rots by default. That job is the only thing that stops it, and it's what makes a six-month-old preset still produce a modern project.

Here's what still bothers me, though. Every green badge in this post was produced by me, on a schedule I control, for combinations I chose. The next milestone hands that button to everyone — and I was fairly sure I couldn't afford to.

🧱 Building Kitbash — part 6 of 8. Earlier posts are linked in the comments.

#SoftwareEngineering #SoftwareArchitecture #Kotlin #OpenAPI #PlatformEngineering

---

## Notes

- Swap "last week" for whatever is true. The specific timeframe is what makes the diff claim
  credible.
- The architecture-test paragraph starts the best discussions; if you cut anything, cut the
  weekly-bump paragraph instead.
- The closing admission is the series' strongest hand-off — it sets part 7 up as a puzzle
  rather than a feature announcement.
