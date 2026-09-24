# Pushing to GitLab

`POST /api/v1/push` creates a project in a GitLab group and puts the generated repository in it as
the initial commit. §17 asked for it; §18 deferred it on one condition — *the zip path must be
excellent first* — and this was built after that condition was met, which is why it reuses the zip
path entirely instead of growing a second one.

**The zip is still the default.** Generate remains the primary button, and this sits beside it.
The zip needs no forge, no account and no credential; a push target that quietly became the default
would put the simplest way to take delivery behind a token.

## The token, and why it is the first section

This feature **creates repositories in your namespace**. That is the privilege being handed over,
and it is worth naming before anything else on this page.

| | |
| --- | --- |
| **Scope needed** | `api` |
| **Scopes not needed** | `sudo`, `admin_mode`, `read_registry`, `write_registry`, anything else |
| **Where it is stored** | nowhere |
| **Where it is logged** | nowhere |

`api` is the smallest scope that works, and it is bigger than this feature needs — GitLab has no
narrower one. There is no `create_project` scope, and `write_repository` covers pushing but not
creating, so a token that could only push would still need the project to exist first. The honest
statement is therefore: **this asks for more privilege than it uses**, because GitLab's scope
vocabulary has no smaller unit. Two consequences worth acting on:

- Use a **project access token or a short-lived personal access token with an expiry**, not a
  long-lived one. The expiry is the control GitLab does give you.
- Give it the narrowest **role** the operation needs: `Developer` in the target group. `api` is
  bounded by the role, so a Developer's `api` token cannot delete the group.

### What happens to it

It is typed into the dialog, sent in the request body over TLS, used for three things, and
dropped when the request ends:

1. `GET /api/v4/groups/:path` — to turn the group path into an id.
2. `POST /api/v4/projects` — to create the project.
3. The `git push` remote, as `https://oauth2:<token>@…`.

It is never written to the database, never put in a session, never returned in a response, and
never logged. The third use is the one that needs care, because a remote URL is exactly the kind of
string that ends up in an error message — so `GitPusher` redacts `https://…@` out of everything git
writes to stderr before any of it is used, and that redaction has its own test.

The server holds no token of its own. There is no OAuth application to register and no consent
screen, because the alternative — this service holding a credential that creates repositories on
your behalf whenever it likes — is a bigger thing to operate than a field in a dialog.

## What lands in the project

The same commit the zip carries. Not a re-render, not an equivalent tree: the push runs from the
pipeline's output, the one `GeneratedProject` that the same request would have zipped.

That matters because §4 made generation byte-identical for a given selection and catalog, and the
initial commit is built from the tree with a fixed author, a fixed committer and a fixed timestamp.
So the commit id is a function of the selection — and `PushedCommitTest` checks the claim the way a
reader would want it checked: it generates once, pushes to a real repository, and asserts that

```
project.commitId()  ==  git rev-parse main   (on the remote)
```

with the tree compared file for file as well. One string comparison is enough only because
determinism makes it enough.

The project is created **without** `initialize_with_readme`. A project that already has a commit
cannot receive this one as its first — the push would be a non-fast-forward, and the guarantee
above would be gone.

Defaults: **private** visibility, default branch `main`, no branch protection changed, no merge
request opened. Everything after the initial commit is yours.

## When it goes wrong

Every one of these is a §14 envelope with a hint that names the next action. `docs/errors.md` has
the full list; this is the push-specific part of it.

| `error` | Status | What happened |
| --- | --- | --- |
| `TOKEN_REJECTED` | 422 | GitLab did not accept the token. Expired, or for a different instance. |
| `GROUP_NOT_FOUND` | 422 | No group at that path *that this token can see* — GitLab does not distinguish the two, and neither does the message. |
| `GROUP_FORBIDDEN` | 422 | The account cannot create projects there. Needs Developer or above. |
| `PROJECT_EXISTS` | 422 | The name is taken in that group. Nothing was created; nothing was written to the existing project. |
| `PROJECT_REFUSED` | 422 | A group rule rejected the name. The hint quotes what GitLab said. |
| `GITLAB_UNREACHABLE` | 422 | The instance did not answer. Nothing was created. |
| `PUSH_PARTIAL` | 502 | **The project was created and the code did not reach it.** |

The last row is the one that needed a decision. A project exists that did not exist a minute ago,
and it is empty. Rolling it back would delete something the user can already see and may have
started using; reporting a generic failure would leave them with an empty repository and no idea
where it came from. So it is reported as exactly what it is, and the envelope carries
`projectUrl` and `path`:

```json
{
  "title": "Created, but not pushed",
  "status": 502,
  "error": "PUSH_PARTIAL",
  "projectUrl": "https://gitlab.example.com/acme/platform/billing",
  "path": "acme/platform/billing",
  "detail": "The project was created and the code did not reach it: remote: GitLab: You are not allowed to push code to this project.",
  "hint": "The project is at https://gitlab.example.com/acme/platform/billing and is empty. Nothing was rolled back, so download the zip and push it there by hand — or delete the project and try again."
}
```

Retrying blindly after this fails with `PROJECT_EXISTS`, which is why the hint says to push by hand
or delete first rather than "try again".

## Configuration

| Property | Default | What it is |
| --- | --- | --- |
| `kitbash.push.gitlab-url` | `https://gitlab.com` | The instance. Set it to your own; it is the base for both the API calls and the remote. |

The server image needs `git` on its `PATH` — `server/Dockerfile` installs it. A push runs
`git push <remote> HEAD:refs/heads/main` in a temporary directory with a 120-second timeout, and
the directory is removed whether it succeeded or not.

## In history

A generation that was pushed records where it went. `generation.pushed_project_url` is added by
`V2__pushed_project.sql` and surfaces as `pushedProjectUrl` on `GET /api/v1/generations` — null for
the ordinary case, and when it is set it is the thing somebody came back to history to find.

It is written **after** the push succeeds, so no row ever claims a project that is empty. If the
write itself fails the push is still reported as a success, because it was one: the code is in
GitLab, and losing the receipt is not worth turning that into an error.

## Not in scope

GitHub, unless and until somebody asks. Pushing into a repository that already exists. Branches and
merge requests beyond the initial commit. §46 draws all three lines, and the reason is the same one
each time: this is a way to take delivery, not a git client.
