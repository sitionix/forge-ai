# Architect Implementation Handoff

## Purpose

Prepare implementation-ready context for the downstream implementer.

## Shape

Implementation handoff must be:

- scope-local;
- concise;
- ordered;
- executable;
- explicit about ownership boundary;
- explicit about dependencies and constraints;
- clear about expected behavior.

## Content

Include:

- scope-owned requirements;
- architecture direction;
- target module or layer when known;
- affected components when known;
- API request decision summary;
- event request decision summary;
- dependencies;
- constraints;
- non-goals;
- risks;
- acceptance-oriented implementation notes.

## Compression

Compress analyzer input into an actionable implementation packet.
Use acceptance-oriented notes only when they clarify expected implementation behavior.
Implementation handoff is not QA strategy.
Test planning, concrete test files, and Given/When/Then scenarios belong to QA/test lanes.