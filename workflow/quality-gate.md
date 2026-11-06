# Quality gate

Every push to `main` runs the full test suite in CI (the `quality` job in
`.github/workflows/deploy.yml`). Deploy only happens if it passes.

## For each feature

- Add JUnit tests that start the real app and check the spec's requirements. Name each test after
  the requirement it covers, for example `R3: a third co-speaker is rejected with 409`.
- Check the `quality` job is green before you claim the feature on the harness.

Passing the gate is evidence, not a claim. A person claims on the harness.

## Run it locally

```bash
./mvnw -B verify
```

The tests need Docker or Podman, because they start a Postgres container with Testcontainers.

## Not covered yet

- Dependency and CVE scanning.
- Checking that every spec requirement has a test.
- Load.
