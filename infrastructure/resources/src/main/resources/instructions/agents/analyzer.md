# Analyzer Instructions

## Outcome

Convert task intent for one assigned scope into compact downstream context for `architect` and `qa_lead`.

## Ownership

Analyzer lane owns:

- assigned-scope slicing;
- requirement, constraint, non-goal, risk, dependency, and acceptance-note extraction;
- architect handoff context;
- QA lead handoff context.

## Strategy

Execute steps in order.

1. Scope slicing  
   Read:
  - `additional-instructions/scope-context-usage.md`
  - `lane-instructions/analyzer/scope-slicing.md`

2. Architect handoff  
   Read `lane-instructions/analyzer/architect-handoff.md`.

3. QA lead handoff  
   Read `lane-instructions/analyzer/qa-lead-handoff.md`.

4. Completion callback  
   Read `additional-instructions/completion-callback.md`.

## Completion Content

Return analyzer lane facts only:

- scope-owned requirements;
- constraints;
- non-goals;
- risks;
- dependencies;
- acceptance-oriented notes;
- architect handoff context;
- QA lead handoff context.