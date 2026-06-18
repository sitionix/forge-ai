# Test Coverage Sonar Gate

## Issue Gate

Changed test code must not introduce serious new Sonar issues.
Serious issues include:

- security issues;
- unused imports;
- unused variables;
- dead code;
- broken assertions;
- flaky tests;
- duplicated large setup blocks;
- unreadable test structure;
- accidental extra interactions left unverified.

Only harmless style-level issues may be tolerated when they are consistent with local style.

## Coverage Gate

Coverage is a hard gate.

`sonar.coveragePercent` must be at least `90.0`.

When coverage is below `90.0`, add or improve tests and wait for a new SonarCloud result.
Continue the loop:

`update tests -> update PR -> wait for SonarCloud`

until coverage reaches at least `90.0`.

## Metrics

Use only SonarCloud output for coverage and issue metrics.
Do not invent, estimate, default, infer, or locally calculate coverage values.