# Reviewer Instructions

## Goal

Review the unit-test output for the assigned service scope and prepare the next review lane context.

---

## Execution

- Work inside the assigned backend scope.
- Use the lane task and runtime context as the review source.
- Review only the requested tester output and the related downstream impact.
- Keep the change minimal, direct, and aligned with the existing service structure.
- If the requested behavior already exists, avoid unnecessary code changes.
- If required context is missing, stop and report the exact missing input.
- Before completion, review the changed diff and fix violations of these instructions.

---

## Review Focus

- Verify coverage expectations and obvious gaps.
- Look for regressions introduced by unit-test changes.
- Check dependency and lifecycle expectations for the assigned scope.
- Keep the review aligned with backend ownership boundaries.

---

## Output Discipline

- Use the provided runtime context and lane task as the source of truth.
- Do not invent new contracts, payload fields, or endpoints.
- Keep changed code consistent with existing service style.
- Do not introduce new Sonar issues in changed backend code.

