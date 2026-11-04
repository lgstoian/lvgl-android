# Machine-Readable Comment Schema

> Status: Accepted (ADR-011)
> Enforced by: `lvgl-android/src/test/kotlin/com/lvgl/android/annotations/MachineCommentValidationTest.kt`
> Trail file: `docs/agent-breadcrumbs.json`

This schema lets an AI agent (or any tool) read the *why* behind non-obvious code
without parsing prose. Annotations live in ordinary `//` or `/* */` comments and
are ignored by the compiler.

## Grammar

An annotation is a `@tag` followed by a payload:

```
@tag key=value key2="quoted value"
```

Rules:

- A tag is lowercase, `[a-z][a-z0-9-]*`.
- Payload is space-separated `key=value` pairs. A value is either a bare token
  (no spaces, no `"`, no `@`) or a double-quoted string.
- A payload ends at the end of the comment unit (end of line for `//`, end of
  the block for `/* */`) or at the next `@tag`.
- Annotations may appear on their own line inside a KDoc/block comment.

## Known tags

| Tag | Payload | Meaning |
|-----|---------|---------|
| `@breadcrumb` | `BC-NNN` | The decision this code implements. Must exist in `docs/agent-breadcrumbs.json`. |
| `@decision` | `ADR-NNN` | The architecture decision record. Must exist in `ROADMAP.md`. |
| `@guard-rule` | `rule=NAME [test=ClassName]` | A rule enforced by a test; `test` names the enforcing test class. |
| `@invariant` | free-form | A property that must hold at this point. |
| `@contract` | `requires=... ensures=...` | Pre/post conditions. |
| `@machine-json` | `{...}` | A JSON object; must parse as valid JSON. |
| `@ai-hint` | free-form | A short hint for an AI agent. |
| `@why` | free-form | Short rationale. |
| `@do-not` | free-form | What must not be done here. |
| `@trap` | free-form | A known footgun in this code. |

Unknown tags are a build failure. Dangling `@breadcrumb`/`@decision` references
are a build failure. Unparsable `@machine-json` is a build failure.

## Standard KDoc tags

The parser also recognises the standard KDoc documentation tags so that API
documentation can live next to the machine annotations:

`@param`, `@return`, `@property`, `@throws`, `@exception`, `@see`, `@since`,
`@deprecated`, `@author`, `@sample`, `@suppress`

They carry prose, not `key=value` pairs, so they are **not** validated as
machine annotations: no reference checks, no orphan checks, no JSON checks. Do
not use them to carry machine-readable data — add a machine tag instead.

## Examples

```kotlin
/**
 * Binds the shared display to [owner].
 *
 * @breadcrumb BC-001
 * @decision ADR-002
 * @invariant activeOwner == null || activeOwner === owner
 * @do-not Never call shutdown() from an activity.
 * @machine-json {"owner_scope":"activity","rebinding":true}
 */
fun initDisplay(owner: Any, surface: Surface, width: Int, height: Int, dpi: Int) { ... }
```

```c
/*
 * Fast path: keep the display and rebind the window.
 * @breadcrumb BC-002
 * @trap Joining the render thread from the UI thread here causes an ANR.
 */
```

## Validation rules

`MachineCommentValidationTest` scans `lvgl-android/src/{main,test}` and
`app/src/main` for `.kt`, `.cpp` and `.h` files and enforces:

1. Every annotation uses a known tag.
2. Every `@breadcrumb BC-NNN` resolves to an entry in `agent-breadcrumbs.json`.
3. Every `@decision ADR-NNN` resolves to an `### ADR-NNN` heading in `ROADMAP.md`.
4. Every `@machine-json` payload parses as JSON.
5. Every breadcrumb entry is referenced by at least one annotation (no orphans).
6. Every breadcrumb entry has all required fields.
7. The scanner found at least one annotation and at least one source file
   (guards against a broken repo-root path silently validating nothing).
