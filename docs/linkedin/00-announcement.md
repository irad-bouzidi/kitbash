# Part 1 — Announcement

**Publish when:** first. This is the one that goes to the whole network — engineers, managers,
people who'll never read the technical follow-up.

**Length:** ~1,900 characters

**Hook:** the two-days-per-service cost, stated as repetition rather than as a technical problem.

**Opens by:** nothing to call back to — this one sets the premise for the whole series.

**Ends by:** promising the engineering story, and asking the question part 2 opens with.

**Goal:** name the project, make the problem recognizable, invite a conversation. No
architecture, no jargon, one idea.

---

Every time a team here starts a new service, they lose two days before writing a single line of business logic.

Wiring a frontend to a backend. Docker. CI. Migrations. Linting. Auth. A README nobody updates. Then the next team does the same two days, slightly differently, and six months later no two projects in the organization look alike.

🧱 I'm building something to delete those two days. It's called Kitbash.

Pick a backend, a frontend, an architecture, a build tool and the features you need. Click generate. Get a project that compiles, runs and passes its own tests on the first try — with the database, the container setup, the CI pipeline and a working example feature already wired together.

Not a starter template you spend a day adapting. A project your team can start working in immediately, assembled from parts that are maintained, tested and kept current.

🧩 The name comes from scale modelling: kitbashing is building something new by combining parts from several kits. That's exactly the idea. Instead of maintaining one template per combination of technologies, every piece is a part, and the parts know how to fit together.

♻️ Why it matters beyond the two days: every generated project follows the same standards, and when a framework releases a new version, that improvement reaches every future project instead of aging quietly in a template nobody owns.

Here's the thing though — the obvious way to build this doesn't work. I'll explain why in the next post, because the reason is the entire design.

💬 First, I want to hear it from you: how does your team handle new project setup today? Copy the last repo and delete things? A template that's slowly gone stale? Genuinely curious what's out there.

🧱 Building Kitbash — part 1 of 8. I'll post each milestone as it lands.

#SoftwareEngineering #DeveloperExperience #EngineeringLeadership #Productivity #DevTools

---

## Notes

- **This is the broad-audience post.** Part 2 is the technical follow-up. Publish this first,
  give it a few days, then post the kickoff for the engineers who engaged.
- The question is the engagement driver _and_ the setup for part 2, which opens by quoting the
  answers. Don't cut it — and do read the replies, because the best one becomes part 2's first
  line.
- "The obvious way doesn't work" is the series' first open loop. Part 2 closes it in its third
  paragraph.
- If the audience skews non-technical, cut the kitbashing paragraph; the post works without it.
- Emoji follow the series key in the README: 🧱 is the project mark, the rest anchor a
  paragraph that functions as a list item. If this post goes to a more formal audience, the
  three in the body can come out without touching a sentence — leave the 🧱 footer, since that
  is what makes the series recognizable in the feed.
- Do not imply a public launch or a signup. Nothing here promises availability to anyone
  outside the organization, and it should stay that way until that's a real decision.
