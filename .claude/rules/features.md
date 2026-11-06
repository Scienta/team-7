# Drip features

- Before you change code for a feature, read its spec from the harness. Use the recipe in
  `.claude/agents/spec-test-writer.md`. Don't use copies in `.fagdag/feedback/`, which are local only.
- Ambiguous behaviour (status codes, edge cases) is decided by the team. Record the decision in
  `workflow/decisions/<slug>.md` before you implement it. Don't guess.
- Write the feature's acceptance tests with the `spec-test-writer` agent, and make sure they pass with
  `./mvnw -B verify`.
- Never claim a feature on the harness. Only a person does that. Passing tests are evidence to show them.
- Quality gate: see `workflow/quality-gate.md`.
