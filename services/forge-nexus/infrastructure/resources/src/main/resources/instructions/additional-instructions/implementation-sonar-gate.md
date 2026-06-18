# Implementation Sonar Gate

This gate validates changed production code.

## Issue Gate

Changed production code must not introduce serious new Sonar issues.
Fix Sonar issues caused by changed production code before completion.

Serious issues include:

- security issues;
- correctness issues;
- maintainability issues;
- runtime behavior risks;
- unused imports;
- unused variables;
- dead code;
- obvious null or undefined safety bugs;
- unreadable control flow;
- broken behavior-level compatibility updates.

Only harmless style-level issues may be tolerated when they do not affect correctness, security, maintainability, or runtime behavior.

## Duplication Gate

`sonar.duplications` for changed production code must be `< 3.0%`.
When duplication is `>= 3.0%`, reduce duplication in changed production code and wait for a new SonarCloud result.

## Coverage

Implementation lanes do not use coverage as a gate.
Do not add tests to satisfy coverage from an implementation lane.
Do not report implementation-lane coverage unless the provided final-step completion payload contract explicitly requires it.

Coverage conditions from Sonar quality gate are informational for implementation lanes.
Implementation lanes continue Sonar remediation by fixing issue conditions only.
