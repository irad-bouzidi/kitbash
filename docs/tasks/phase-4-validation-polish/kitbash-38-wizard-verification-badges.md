# kitbash-38-wizard-verification-badges

**Phase** 4 — Validation and polish · **Depends on** `kitbash-37-on-demand-verify-job` · **Plan** §8, §9, §12

## Goal

Verification stops being an internal CI concern and becomes something the user sees while
choosing, at the moment the choice is made.

## Context

§8 lists per-combination verification badges as part of what the metadata endpoint carries, and
§9 is specific about placement: combinations the nightly matrix reports red get a warning badge
**at the option pairing**. Not a page banner, not a footnote — at the pairing, because that is
where the decision happens and where the information is actionable.

§12 closes the loop: results publish to a status page *and feed the wizard's badges*. This task
is that second half.

## Scope

- The metadata endpoint's verification-status field (reserved in `kitbash-15`) is populated from
  matrix results, per combination.
- **Red combinations show a warning badge at the offending option pairing**, with the failure
  reason and a link to the log on hover or click.
- Green combinations show when they were last verified and against which catalog digest —
  a green badge with no date is a claim, not evidence.
- **"Verify this build"** in the wizard's bottom bar (§12): posts the current selection, shows
  progress, and renders the log when it completes.
- The instant-result path (a previously verified identical selection) is visibly distinct from a
  fresh run, so users understand why one took a second and another took four minutes.
- Queue-depth 429 is rendered as a readable message with the depth, not a generic error.
- Badge data is cached with the catalog digest ETag, so badges cannot show results from a
  different catalog.

## Out of scope

No badge for combinations that have never been verified — absence of data is shown as "not
verified", never as green.

## Implementation notes

- Keep the badge component generic over the pairing it decorates; hardcoding "backend × frontend"
  would reintroduce exactly the technology knowledge §9 keeps out of React.
- The log viewer should stream or paginate — a failing Gradle build log is long, and rendering it
  wholesale will jank the wizard.

## Files and modules touched

`/server/api/**` (badge data in metadata), `/server/verify/**` (result publication),
`/web/src/wizard/**` (badges, verify button, log viewer).

## Done when

- A red nightly cell becomes a visible badge on the offending option pairing within one catalog
  refresh.
- The log viewer renders a real failing build log.
- **Phase exit:** a user can verify their own combination from the UI and read the build log.
