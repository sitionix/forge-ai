# Lazy Instruction Strategy

Use the agent instruction file as the execution strategy.

Execute strategy steps in the listed order.

For each step:

1. read only the instruction file referenced by the active step;
2. apply the referenced file only to the active step;
3. complete the active step before moving to the next step;
4. load later-step files only when their step starts.

The active step defines the current allowed action.

Shared instructions remain always active.

## Instruction Root
When an agent file defines `Instruction Root`, resolve relative instruction paths from that root.
`forge-ai/infrastructure/resources/src/main/resources/instructions`