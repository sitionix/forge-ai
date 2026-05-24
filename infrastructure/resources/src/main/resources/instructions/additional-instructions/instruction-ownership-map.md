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

- agent instruction file;
- shared instruction file;
- additional instruction file;
- OpenAPI contract if callback payload is involved;
- runtime/config file if the issue may be orchestration-related.

If this map conflicts with the actual file, trust the actual file and update this map only if the ownership/navigation is outdated.

---

# Instruction Groups

## Shared Instructions

Shared instructions contain rules used by all or most agents.

| File | Owns |
|---|---|
| `instructions/shared/common-rules.md` | universal lane behavior, scope discipline, ownership boundaries |
| `instructions/shared/completion-callback.md` | callback mechanics, OpenAPI callback contract reading, HTTP delivery, retry, verification |
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
| `instructions/additional-instructions/api-generation-rules.md` | API targets, artifact evidence, metadata, versioning |
| `instructions/additional-instructions/api-decision-instruction.md` | Architect API required/not-required decision |
| `instructions/additional-instructions/event-decision-instruction.md` | Architect event required/not-required decision |
| `instructions/additional-instructions/instruction-ownership-map.md` | navigation map for Reviewer instruction ownership |

Use additional files when the rule is reusable across several agents but not universal.

Do not put lane-only payload semantics there.

---

## Agent Instructions

Agent files own lane-specific behavior.

| Agent | File | Owns |
|---|---|---|
| `analyzer` | `instructions/agents/analyzer.md` | scope analysis and handoff preparation |
| `architect` | `instructions/agents/architect.md` | architecture direction and downstream work decisions |
| `api` | `instructions/agents/api.md` | API contract/generation lane behavior |
| `qa_lead` | `instructions/agents/qa_lead.md` | QA planning context and test-lane required decisions |
| `implement_be` | `instructions/agents/implement_be.md` | backend production implementation behavior |
| `implement_fe` | `instructions/agents/implement_fe.md` | frontend production implementation behavior |
| `test_unit` | `instructions/agents/test_unit.md` | backend unit-test implementation behavior |
| `test_it` | `instructions/agents/test_it.md` | backend integration-test implementation behavior |
| `test_ui` | `instructions/agents/test_ui.md` | frontend UI test behavior, if active |
| `reviewer` | `instructions/agents/reviewer.md` | interactive reviewer behavior and instruction-maintenance workflow |

Use agent files for rules that apply only to one lane.

---

# Rule Placement Decision

## Put the rule in shared instructions when

The rule applies to all or almost all agents.

Examples:

- scope boundaries;
- no invented context;
- callback transport rules;
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
| Agent invented callback payload field | update lane file or callback rules |
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

- `instructions/agents/implement_be.md`

Reason:

- This is lane-specific backend implementation ownership.

---

## Unit-test style mistake

Example:

- Unit tester used MockMvc.
- Unit tester duplicated object builders.
- Unit tester mocked data objects instead of building real value objects.

Owner:

- `instructions/agents/test_unit.md`
- or `instructions/additional-instructions/java-test-style.md` if the rule is reusable across Java test lanes.

---

## Integration-test style mistake

Example:

- IT tester ignored ForgeIT.
- IT tester reported test files instead of `coveredCases`.
- IT tester duplicated QA Lead test objects in completion payload.

Owner:

- `instructions/agents/test_it.md`

---

## API generation mistake

Example:

- API agent invented artifact version.
- API agent guessed generation target.
- API agent reported dependency without evidence.

Owner:

- `instructions/additional-instructions/api-generation-rules.md`
- or `instructions/agents/api.md` if the issue is API lane output semantics.

---

## Callback delivery mistake

Example:

- Agent claimed callback success without HTTP response verification.
- Agent guessed callback payload from memory instead of OpenAPI.
- Agent skipped retry rules.

Owner:

- `instructions/shared/completion-callback.md`

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
- missing completion endpoint;
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