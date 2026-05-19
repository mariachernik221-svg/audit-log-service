---
name: spec-self-eval
description: This skill should be used when the user asks to "evaluate a spec", "self-eval", "spec self evaluation", "run spec-self-eval", "validate spec", or invokes /spec-self-eval. It validates `.specs/<feature>/{requirements,design,tasks}.md` against the bundled checklist `.codex/skills/spec-self-eval/references/_eval-checklist.md` and writes a PASS / FAIL / WEAK report to `.specs/<feature>/eval-report-<current_timestamp>.md`.
version: 1.2.0
---

# spec-self-eval

Validate a feature spec against the repo checklist and emit a dated report.

## Inputs

- `<feature>` — feature directory name under `.specs/`. Required.
- `<current_timestamp>` — UTC timestamp in `YYYY-MM-DD_HH-MM-SSZ` form (filesystem-safe). The invoking prompt supplies this verbatim; do not call any clock or change the format.

## Files read (in this exact order)

1. `.codex/skills/spec-self-eval/references/_eval-checklist.md` — list of checklist items, bundled with the skill.
2. `.specs/<feature>/requirements.md`
3. `.specs/<feature>/design.md`
4. `.specs/<feature>/tasks.md`

If any of the four files is missing, abort and report which file is missing. Do not write a report.

## Procedure (deterministic)

1. Parse `.codex/skills/spec-self-eval/references/_eval-checklist.md`. Each top-level `-` bullet under `## Checklist for specs:` is one checklist item. Preserve their order and original wording verbatim as the `Item` column.
2. For each checklist item, in order, assign exactly one verdict:
   - **PASS** — the spec files contain explicit, quotable evidence that satisfies the item.
   - **WEAK** — evidence exists but is partial, ambiguous, or only implicit.
   - **FAIL** — no evidence, or evidence contradicts the item.
3. For each item, the `Evidence` cell must cite the source file and the concrete content that drove the verdict (field names, section headings, numeric bounds, quoted phrases). No paraphrase-only evidence.
4. Verdict rules — apply in order, do not re-interpret:
   - Pick **PASS** only if every part of the checklist item is directly supported by quotable content in the spec files.
   - Pick **FAIL** only if no supporting content exists, or content directly contradicts the item.
   - Otherwise pick **WEAK**.
5. Do not invent checklist items. Do not merge or split items. Do not reorder them.

## Output file

Path: `.specs/<feature>/eval-report-<current_timestamp>.md`

If the file already exists, overwrite it.

## Output format (exact)

```markdown
# Spec evaluation report — <current_timestamp>

Target: `.specs/<feature>/` (`requirements.md`, `design.md`, `tasks.md`)
Checklist: `.codex/skills/spec-self-eval/references/_eval-checklist.md`

## Results

| # | Item | Verdict | Evidence |
|---|------|---------|----------|
| 1 | <item 1 text> | **<VERDICT>** | <evidence> |
| 2 | <item 2 text> | **<VERDICT>** | <evidence> |
| ... | ... | ... | ... |

## Summary

<P> / <N> PASS. <details on FAIL/WEAK, or the literal text "No FAIL or WEAK findings." when none>.
```

Where:
- `<N>` is the total number of checklist items.
- `<P>` is the count of PASS verdicts.
- The Checklist path is the repo-relative path to the bundled checklist; render it exactly as shown (no link).
- Verdict cell is bold: `**PASS**`, `**WEAK**`, `**FAIL**`.

## Determinism rules

- Same inputs → identical bytes out. The only variable content is `<current_timestamp>`, which is supplied by the invoking prompt — do not call a clock, do not regenerate, do not reformat.
- Walk checklist items in source order. Walk spec files in the order listed above.
- Quote evidence verbatim from the spec files; do not summarize with synonyms.

## Non-goals

- Do not edit the spec files.
- Do not edit the checklist.
- Do not invent new checklist items, scoring tiers, or sections.
- Do not run tests, builds, or external tools.
