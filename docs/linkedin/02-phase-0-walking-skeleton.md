# Part 3 — Phase 0, the walking skeleton

**Publish when:** the CI job that unzips a generated project and runs `./gradlew build` is green.
**Length:** ~2,300 characters
**Hook:** paying off part 2's closing line — most of this milestone is designed to be deleted.
**Opens by:** the "deliberately unambitious" promise from part 2.
**Ends by:** setting up part 4 — everything hardcoded here becomes data, and that's the phase
that decides whether the project survives.

---

I said the first Kitbash milestone would be deliberately unambitious. It shipped, and most of what I wrote is designed to be deleted.

No recipe engine. No composition. One hardcoded stack — Java, Spring Boot, Gradle, Postgres — and one honest end-to-end path: click a button, get a zip, unzip it, run one command, have it work. With CI proving that on every merge request.

Why build something you plan to throw away? Because it forces every awkward question to the surface while the system is still small enough to answer them.

What it took:

📐 A reference project. Not a template — an actual service that compiles. One example entity with a migration, repository, service and documented REST endpoint. A unit test. An integration test against a real database in a container. A non-root multi-stage Dockerfile, and a compose file with a database healthcheck so the app doesn't race it on startup.

That project is the source of truth. The recipes will be derived from it, never the reverse. Editing a real project that compiles beats editing template soup, and a test will eventually assert the two never drift apart.

📦 A zip writer that is deterministic. Fixed entry timestamps, sorted entries, normalized line endings, correct file modes, and a git repository with one reproducible initial commit. Two identical requests produce byte-identical archives, asserted across separate runs.

✅ And a CI job that generates, unzips and runs the real build inside a container with no network beyond a dependency proxy. When it fails, it prints the exact input that produced the failure and the one-line command to reproduce it locally.

🔑 The decision I'd defend hardest: determinism went in now, when it was nearly free. It's the foundation for caching and for reproducing a six-month-old build exactly. Retrofitting it later would have meant chasing timestamp bytes through an engine that already existed.

⚙️ Next is the one that matters. Everything hardcoded here becomes data — recipes, a resolver, typed patches — and that's the milestone that decides whether this project survives its first year or joins the abandoned ones.

🧱 Building Kitbash — part 3 of 8. Earlier posts are linked in the comments.

#SoftwareEngineering #Java #SpringBoot #CI #DeveloperExperience

---

## Notes

- The "why build something you plan to throw away" paragraph is what makes this post more than
  a status update. Keep it even if you cut elsewhere.
- If the React shell and download test landed too, one line fits after the zip-writer paragraph:
  "Plus a web shell with exactly one button, downloading via a real navigation so the browser
  shows native download progress."
- The closing line deliberately raises the stakes for part 4, which is the strongest post in
  the series. Don't soften it.
