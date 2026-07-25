# Instruction Ownership Map

## Purpose

This file helps Reviewer find the correct instruction owner when the user reports an agent behavior problem.

This file is not the source of truth for agent behavior.

The source of truth is always the actual instruction file referenced here.

Use this file only as a navigation and ownership map.

---

## Core Rule

Before changing any instruction:

1. identify the problem type;
2. find the owner file;
3. read the current owner file;
4. check whether a rule already exists;
5. update only the narrowest correct file;
6. avoid duplicated or ticket-specific rules.

---

## Source Of Truth

Do not rely on this map for detailed behavior.

Always open and read the actual file before editing:

- lane strategy file;
- lane instruction file;
- shared instruction file;
- additional instruction file;
- final-step completion payload contract if completion payload shape is involved;
- runtime/config file if the issue may be orchestration-related.

If this map conflicts with the actual file, trust the actual file and update this map only if the ownership/navigation is outdated.

---

# Instruction Groups

## Shared Instructions

Shared instructions contain rules used by all or most agents.

| File | Owns |
|---|---|
| `instructions/shared/common-rules.md` | universal lane behavior, scope discipline, ownership boundaries |
| `instructions/shared/scope-context-usage.md` | how to interpret scope context and ownership metadata |

Use shared files only for cross-agent rules.

Do not put lane-specific payload, implementation, testing, or workflow-specific rules there.

---

## Additional Instructions

Additional instructions contain reusable workflow/style/domain rule packs attached to selected agents.

| File | Owns |
|---|---|
| `instructions/additional-instructions/pr-workflow.md` | PR lifecycle, push/update PR, CI/Sonar waiting rules |
| `instructions/additional-instructions/preparation-to-work.md` | documented repository preparation workflow |
| `instructions/additional-instructions/java-style-basics.md` | reusable Java style rules |
| `instructions/additional-instructions/java-test-style.md` | reusable Java unit-test style, if present |
| `instructions/additional-instructions/generation-workflow.md` | generic API generation lifecycle |
| `instructions/additional-instructions/api-contract-rules.md` | REST contract layout and contract-change rules |
| `instructions/additional-instructions/api-artifact-generation-rules.md` | API targets, artifact evidence, metadata |
| `instructions/additional-instructions/version-rules.md` | API/event version synchronization |
| `instructions/additional-instructions/event-contract-rules.md` | event contract layout, metadata, versioning |
| `instructions/additional-instructions/event-artifact-generation-rules.md` | event artifact generation targets and evidence |
| `instructions/lane-instructions/architect/api-decision.md` | Architect API required/not-required decision |
| `instructions/lane-instructions/architect/event-decision.md` | Architect event required/not-required decision |
| `instructions/additional-instructions/instruction-ownership-map.md` | navigation map for Reviewer instruction ownership |

Use additional files when the rule is reusable across several agents but not universal.

Do not put lane-only payload semantics there.

---

## Lane Strategy Instructions

`lane-strategies.yml` owns lane step order and decides which instruction refs are attached to each step.
Lane instruction files own lane-specific behavior for the step that references them.

| Lane | Owner |
|---|---|
| `analyzer` | `lane-strategies.yml` + `instructions/lane-instructions/analyzer/*` |
| `architect` | `lane-strategies.yml` + `instructions/lane-instructions/architect/*` |
| `api` | `lane-strategies.yml` + `instructions/lane-instructions/api/*` + API additional instructions |
| `qa_lead` | `lane-strategies.yml` + `instructions/lane-instructions/qa-lead/*` |
| `implement_be` | `lane-strategies.yml` + `instructions/lane-instructions/implement-be/*` |
| `implement_fe` | `lane-strategies.yml` + `instructions/lane-instructions/implement-fe/*` |
| `test_unit` | `lane-strategies.yml` + `instructions/lane-instructions/test-unit/*` + Java test style |
| `test_it` | `lane-strategies.yml` + `instructions/lane-instructions/test-it/*` |
| `test_ui` | `lane-strategies.yml` + `instructions/lane-instructions/test-ui/*` |
| `event` | `lane-strategies.yml` + `instructions/lane-instructions/event/*` + event additional instructions |
| `reviewer` | `lane-strategies.yml` + reviewer lane instructions when the reviewer lane is enabled |

Use lane instruction files for rules that apply only to one lane step.

---

# Rule Placement Decision

## Put the rule in shared instructions when

The rule applies to all or almost all agents.

Examples:

- scope boundaries;
- no invented context;
- completion response rules;
- scope ownership interpretation.

Do not put lane-specific rules in shared files.

---

## Put the rule in additional instructions when

The rule is reusable across multiple agents but belongs to a specific workflow or style area.

Examples:

- PR workflow;
- Java style;
- API generation workflow;
- API target/evidence rules;
- Architect API/Event decision rules.

Do not put one-lane payload semantics in additional files.

---

## Put the rule in an agent file when

The rule is specific to one lane.

Examples:

- BE must not write new tests;
- IT reports only `coveredCases`;
- QA Lead writes test cases but not test code;
- FE completion reports changed files, surfaces, and UI behavior;
- API reports generated contracts and artifact evidence;
- Reviewer waits for explicit user command.

---

## Do not edit instructions when

The problem is not prompt/instruction-related.

Runtime/API/config/test issues require implementation tasks.

Examples:

- wrong branch checked out before lane starts;
- lane starts too early;
- missing endpoint;
- invalid body accepted by API;
- missing OpenAPI validation;
- not-needed lane blocks completion;
- instruction file lookup fails;
- missing dependency in lane graph;
- missing test coverage.

---

# Existing Rule Check

Before adding a new rule, Reviewer must check whether an existing rule already covers the problem.

Search order:

1. the suspected owner file;
2. related shared file if the rule may be universal;
3. related additional file if the rule may belong to a reusable workflow/style pack.

Classify the result:

| Result | Meaning | Action |
|---|---|---|
| `rule exists and is sufficient` | The instruction already covers the issue clearly | Do not edit instructions |
| `rule exists but weak` | The instruction exists but is vague or easy to bypass | Strengthen existing rule |
| `rule missing` | No relevant rule exists | Add one concise general rule |
| `rule duplicated` | Same meaning appears in multiple files | Keep or move toward correct owner |
| `rule misplaced` | Rule exists but belongs elsewhere | Move/recommend moving to correct owner |

Do not add a duplicate rule when a sufficient rule already exists.

---

# Runtime vs Instruction

| Problem | Correct action |
|---|---|
| Agent misunderstood its lane responsibility | update instruction owner |
| Agent ignored weak/vague rule | strengthen instruction owner |
| Agent invented completion payload field | update lane file or completion response rules |
| Agent did work owned by another lane | update shared or lane instruction owner |
| Agent wrote tests in implementation lane | update implementation lane file |
| Agent reported fake Sonar values | update lane file and consider API validation guard |
| Completion API accepts invalid payload | fix API validation |
| Lane starts too early | fix runtime resolver |
| Wrong branch before Codex starts | fix preparation/launcher gate |
| Missing endpoint | implement endpoint |
| Missing tests | add tests |
| Optional lane blocks final state | fix lifecycle/runtime logic |
| Instruction file lookup fails | fix config/path/runtime loader |

---

# Common Owner Examples

## Backend implementation mistake

Example:

- BE wrote new unit tests.
- BE changed integration tests without compatibility reason.
- BE reported coverage.

Owner:

- `lane-strategies.yml`
- `instructions/lane-instructions/implement-be/*`

Reason:

- This is lane-specific backend implementation ownership.

---

## Unit-test style mistake

Example:

- Unit tester used MockMvc.
- Unit tester duplicated object builders.
- Unit tester mocked data objects instead of building real value objects.

Owner:

- `lane-strategies.yml`
- `instructions/lane-instructions/test-unit/*`
- or `instructions/additional-instructions/java-test-style.md` if the rule is reusable across Java test lanes.

---

## Integration-test style mistake

Example:

- IT tester ignored ForgeIT.
- IT tester reported test files instead of `coveredCases`.
- IT tester duplicated QA Lead test objects in completion payload.

Owner:

- `lane-strategies.yml`
- `instructions/lane-instructions/test-it/*`

---

## API generation mistake

Example:

- API agent invented artifact version.
- API agent guessed generation target.
- API agent reported dependency without evidence.

Owner:

- `instructions/additional-instructions/api-artifact-generation-rules.md`
- `instructions/additional-instructions/version-rules.md`
- or `instructions/lane-instructions/api/*` if the issue is API lane output semantics.

---

## Completion response mistake

Example:

- Agent claimed completion success without returning the required final-step response.
- Agent guessed completion payload shape from memory instead of the provided final-step contract.
- Agent skipped validation rules.

Owner:


---

## PR / Sonar timing mistake

Example:

- Agent completed before PR update.
- Agent used local run as Sonar evidence.
- Agent did not wait for CI/Sonar when required by its lane.

Owner:

- `instructions/additional-instructions/pr-workflow.md`
- and lane file only if the rule is lane-specific.

---

## Runtime/platform mistake

Example:

- repository is still on `develop`;
- lane starts before dependencies are terminal;
- missing final-step completion contract;
- not-needed lane blocks final completion.

Owner:

- no instruction owner.

Correct action:

- create runtime/API/config implementation task.

---

# Map Maintenance

Update this file only when:

- a new instruction file is added;
- an instruction file path changes;
- ownership/navigation changes;
- a new reusable instruction group is introduced.

Do not update this file for every new behavioral rule.

Behavioral rules belong in their owner instruction files.
