# skills-ref — reference validator for the Agent Skills open standard

This directory is a vendored, byte-for-byte copy of the `skills-ref` reference
library from the **Agent Skills** open-standard repository, used by
`NaruSkillsStandardValidatorTest` to run the open standard's reference
validator against sample skills.

- Upstream: `https://github.com/agentskills/agentskills` — `skills-ref/`
- Fetched: 2026-10-09, branch `main`
- License: Apache-2.0 (see `LICENSE`)

Only the `.py` sources and `pyproject.toml` are required by the test: the
test sets `PYTHONPATH` to `src/`, installs `strictyaml` (the library's own
parsing dependency) into a throwaway virtualenv, and calls
`skills_ref.validate(skill_dir)`. It does not ship or install this code into
any artifact — it exists purely as a test fixture so the reference validator
behaviour is pinned alongside the tests.

NARU's own loader does *not* use this code. NARU deliberately validates
leniently (warn, never reject) while the open standard's validator rejects,
so the test only *observes* the reference validator's verdicts and checks
that well-formed standard skills are also parsed correctly by NARU.