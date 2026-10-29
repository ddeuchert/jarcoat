# CLAUDE.md

Guidance for Claude Code (and other agents) working in this repository.

## Development standards

### Features are spec-driven

No feature is implemented without a spec. Before writing code for a feature:

1. Write (or update) the spec first, in `docs/`. The top-level design is `docs/SPEC.md`; a
   feature large enough to need its own document goes in `docs/specs/<feature-name>.md` and is
   linked from the top-level spec.
2. Every feature spec in `docs/specs/` opens with a **Functional summary** section, right after
   the header/parent-spec block and before any scope or design detail: a bulleted list in the
   form "After this feature, the user will be able to X." Keep it as low on technical detail as
   the feature's own user-facing vocabulary allows — name the goals/options/commands a user would
   actually type or see, not the mechanism behind them. Less is more: a handful of bullets a
   non-implementer can read to know what changed.
3. Get the spec to a state the user has agreed to before starting implementation.
4. Implement against the spec.
5. **Commit the spec together with the code it describes**, in the same commit (or the same PR
   if the change spans multiple commits). A spec change and its implementation should never land
   separately.

If a feature's behavior diverges from its spec during implementation, update the spec before or
alongside the code change that caused the divergence — don't let them drift apart.

### User-visible changes update the docs in the same PR

A PR that changes anything a user can see — a plugin goal or parameter, a CLI option, output, a
default, a manifest entry in a wrapped jar — updates the README/docs pages it affects **in the
same PR**, the same way the spec lands with the code. Example output in the docs is captured from
a real run, not written from memory.

### Sign-off reviews happen in an artifact, not the thread

When a spec is large enough that sign-off means working through several open decisions, run the
review off a published **Artifact**, not a wall of terminal text.

- Give the page section anchors and a table of contents, and enumerate every open decision with a
  stable id (`#1`, `#2`, …) the reviewer can cite by number.
- The artifact is a review aid, not a deliverable. The spec file stays the source of truth; fold
  the agreed decisions back into it before implementation starts.

### Copyright header

Every source file (Java and POM) must carry a copyright header, using the Apache License 2.0
boilerplate, e.g. for Java:

```java
/*
 * Copyright 2026 David Deuchert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

- The year is the year the file was **first created**, and is never edited afterward — no
  `2026-2029`-style ranges to maintain.
- Copyright holder is `David Deuchert`.
- Package root is `org.jarcoat`.
- XML files use the same text inside an `<!-- ... -->` comment after the XML declaration.

### Name non-trivial lambdas before passing them

When a lambda has a block body, or captures several enclosing variables, assign it to a local
variable typed by the functional interface, named for what it does, before passing it, rather
than passing it inline. Short single-expression lambdas (`.map(x -> x.name())`,
`Comparator.comparing(...)`) stay inline.

### Spikes still branch and merge, but the deliverable is a doc

A spike is exploratory work with no committed spec and no shippable code as its goal. The thing
that lands is a **written findings doc** — what was learned and which open question(s) it answers
(link back to the spec section that raised them). The exploratory code need not be committed
unless a piece is worth keeping, in which case it graduates into a real feature branch built
against a spec.

### Licensing

JarCoat is Apache-2.0. Do not copy code from LGPL/GPL projects (see `docs/SPEC.md` licensing note).
