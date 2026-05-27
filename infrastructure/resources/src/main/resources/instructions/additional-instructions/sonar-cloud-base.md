# SonarCloud Base Gate

## Source

Use only real SonarCloud output for completion metrics.
Do not invent, estimate, default, infer, or locally calculate Sonar values.
A zero value is valid only when SonarCloud explicitly reports zero.

## PR Requirement

SonarCloud result is available only after the PR workflow creates or updates the PR for the current lane changes.
Use the SonarCloud result for the PR update that contains the current lane changes.

## Git Connectivity

Before push, PR update, or Sonar polling, verify git remote connectivity and authentication for the current repository.
Keep exact git transport evidence when connectivity or authentication fails.

## Polling

Actively poll PR checks until SonarCloud result is available or retry budget is exhausted.
Minimum retries:

- attempt 1: wait `30s`;
- attempt 2: wait `60s`;
- attempt 3: wait `90s`;
- attempt 4: wait `120s`;
- attempt 5: wait `150s`.

Only infrastructure-level SonarCloud unavailability after retries is a valid reason to stop Sonar verification.

## Result Facts

Keep these facts for the lane completion step:

- PR number;
- PR URL;
- commit hash or branch;
- SonarCloud check name;
- SonarCloud status;
- reported issue count;
- reported duplication value when available;
- reported coverage value when the active lane uses coverage.