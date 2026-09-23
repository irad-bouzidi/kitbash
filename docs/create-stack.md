# `npx create-stack`

The same generator, from a terminal, for developers who would rather not open a web app.

```bash
npx create-stack
```

It asks what the wizard asks, in the same order, and unpacks the project where you are.

## Why this is cheap

§8 decided that `/api/v1/metadata` carries **everything** needed to render an interface. This tool
is the test of that claim: it is a second client against the same API, and **nothing in it names an
option**. Adding a recipe changes the prompts with no release of this package.

Any option knowledge that turns out to be missing from `/metadata` is a gap in the endpoint, and
belongs fixed there rather than patched here. That rule is what keeps two clients from drifting
into two products.

## Usage

```bash
npx create-stack [directory]                  # answer prompts drawn from the catalog
npx create-stack --selection selection.json   # non-interactive, from a §7 envelope
npx create-stack --preset <id>                # non-interactive, from a saved preset
npx create-stack --version                    # the tool, and the catalog the API is serving
```

| Option | |
| --- | --- |
| `--api <url>` | The kitbash API. Default `$KITBASH_API`, then `http://localhost:8080`. |
| `--issuer <url>` | OIDC issuer for the device-code sign-in. Default `$KITBASH_ISSUER`. |
| `--client <id>` | OIDC client id. Default `$KITBASH_CLIENT_ID`, then `create-stack`. |
| `--dry-run` | Resolve and report what would be generated; write nothing. |

`KITBASH_TOKEN` short-circuits the sign-in. That is the path CI takes, and it is checked first, so
a scripted caller never starts an interactive flow it cannot complete.

## Signing in

A **device-code** flow, not a browser redirect. A redirect assumes a browser on the machine running
the tool, and `npx create-stack` over SSH, in a container or on a build box has neither a browser
nor a loopback the provider can reach. Device code degrades honestly: a URL and a code, completed
wherever you do have a browser.

An issuer that does not advertise a device-code endpoint gets a message saying so and pointing at
`KITBASH_TOKEN`, rather than a redirect that cannot work.

## Two numbers in `--version`

```
create-stack 0.1.0
catalog sha256:56006f27d2ab44d4282350f41bd06dd640d3bd9b97606c1daab66e40977b39df
  from http://localhost:8080
```

This tool carries no catalog — but it talks to one, and which one is what makes a bug report
actionable. Same reason the web footer shows it, and the same reason
[the binary](cli.md) shows the one it embeds.

The metadata response is cached by entity tag, and that tag **is** the catalog digest — so a 304 is
proof the catalog has not moved, which is a stronger thing to know than a cache hit usually gets to
say.

## It produces the same project the wizard does

Byte for byte, for the same selection. Both clients build a §7 envelope and post it to
`/api/v1/generate`; equality then follows from §4's determinism rather than from luck.

`test/parity.test.ts` asserts it by running both paths against a live API and comparing the trees,
including file modes. It runs in CI in the `create-stack` job — and fails rather than skips if the
API is not up, because a parity test that silently skips is the same as not having one, except that
it looks like having one.

## What is deliberately not here

**Offline operation and an embedded catalog.** That is [the self-contained binary](cli.md), which
carries its own recipes and makes the opposite trade: forty megabytes and a glibc floor, in exchange
for generating with no network. This tool is a few kilobytes over `npx` and always talks to a
server. Two tools, two trade-offs, named rather than blended.
