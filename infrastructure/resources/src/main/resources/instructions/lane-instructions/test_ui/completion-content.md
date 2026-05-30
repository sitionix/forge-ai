# Test UI Completion Content

Use only lane facts that match fields supported by the provided completion contract.
If sonar coverage is less then 90%, you need to cover more to satisfy sonar

## Allowed Content

Use only these semantic groups when supported:

- `scope`;
- `summary`;
- `coveredCases`;
- `sonar`.

## Field Semantics

- `scope`: assigned frontend SPA scope from runtime context.
- `summary`: short factual summary of completed UI-test work.
- `coveredCases`: UI behavior cases truthfully validated in this lane.
- `sonar`: one aggregated real SonarCloud result for this lane.

## Covered Cases Boundary

Do not report skipped, duplicate, unsafe, impossible, or out-of-scope cases as covered.

## Boundary

Do not include backend details, integration/unit reports, reviewer status, implementation handoff, unrelated PR commentary, invented metrics, or generated artifact lists already reported by API lane.
