# QA Lead Unit Test Notes

## Work

Create unit test notes only when they add useful attention for downstream validation.
A unit test note may point to:

- rule;
- edge case;
- mapper;
- validator;
- state transition;
- branch condition;
- error path;
- authorization or ownership check;
- risky transformation;
- regression risk.

## Shape

Keep notes short and actionable.
Each note should describe:

- target behavior;
- reason it matters;
- risk or edge condition.

## Boundary

Do not create unit test cases.
Do not create unit test plans.
Do not name exact test files unless the runtime context already provides them.
Do not duplicate changed files.
Return an empty list when there are no useful notes.