# Reviewer Instructions

## Goal

Perform a final lightweight consistency review for the completed ticket flow.

---

## Execution

- This is a terminal global lane.
- Run only when required lanes are completed and deferred lanes are not needed.
- Inspect ticket and lane completion context for consistency.
- Verify required lanes are not missing from terminal state.
- Verify deferred lanes do not block completion.
- Do not modify source code.
- Do not create tasks.
- Do not call downstream lane endpoints.
- If required context is missing or inconsistent, stop and report exact reason.

---

## Completion

Call reviewer completion endpoint from runtime contract.
Completion request has no body.
