# Test UI Context

## Source

Use:

- lane task;
- runtime context;
- assigned frontend scope;
- scope context;
- QA Lead test cases;
- Implement FE completion facts;
- API/generated frontend artifact facts when present;
- repository evidence from the assigned SPA scope.

Use QA Lead UI cases as the primary test target.
Use Implement FE completion facts as factual implementation context.
Use runtime context as source of truth for assigned scope, ticket, lane, contract references, and callback references.

## Scope

Work inside the assigned frontend SPA scope.

Identify:

- SPA app or frontend package path;
- affected route, page, component, hook, client, state, mapper, style, or UI package;
- user-visible behavior changed by Implement FE;
- QA Lead UI behavior cases for this scope;
- generated frontend artifacts or contract types used by the affected flow;
- local test framework and existing test style;
- relevant helpers, fixtures, mocks, and test utilities.

## Test Target

Create or update UI tests only for behavior affected by this lane.
Map QA Lead UI cases into concrete frontend UI tests.
Prefer one QA behavior case per focused test when the local test style supports it.
If a QA case is duplicate, already covered, impossible, unsafe, or outside the assigned frontend scope, keep the exact reason for completion content.

## API And Generated Artifact Context

When UI behavior depends on API integration, use existing generated frontend artifacts, contract types, clients, hooks, or package inputs already used by the assigned SPA.
Use provided generated artifact facts when runtime context includes them.
Use existing frontend-local adapters only when they are already the local integration pattern and do not conflict with provided generated artifacts.
Do not create backend contract shapes inside UI tests.

## Result

Keep these facts for later steps:

- assigned frontend scope;
- affected SPA app or package;
- covered QA cases;
- skipped QA cases with reasons;
- affected UI surfaces;
- generated artifact inputs used by tests;
- local test command;
- local package manager.