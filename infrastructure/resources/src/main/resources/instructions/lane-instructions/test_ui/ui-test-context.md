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

## Scope Discovery

Identify:

- SPA app or frontend package path;
- affected route/page/component/hook/client/state/mapper/style/UI package;
- user-visible behavior changed by Implement FE;
- QA Lead UI behavior cases for this scope;
- generated frontend artifacts or contract types used by affected flow;
- local test framework and existing test style;
- relevant helpers, fixtures, mocks, and test utilities.

## Test Target Facts

Create or update UI tests only for behavior affected by this lane.
Keep exact reasons when a QA case is duplicate, already covered, impossible, unsafe, or outside assigned scope.

## Result

Keep these facts for later steps:

- assigned frontend scope;
- affected SPA app/package;
- covered QA cases;
- skipped QA cases with reasons;
- affected UI surfaces;
- generated artifact inputs used by tests;
- local test command;
- local package manager.
