# Remaining synchronization improvements

Recorded 2026-11-19. Keep the original recommendation numbers below so another
session can resume the discussion without the chat history. These are pending
work items, not changes implemented by this document.

Improvements #1–#3 are complete on `main` in `8652be701`: authoritative
[baseline metadata](upstream.json), [subtree synchronization tooling and workflow](docs/synchronization.md),
and smaller hooks into fork-owned helpers. `Util` and `TcDiagnostics` now match
upstream; production hooks remain in `Pipeline`, `Elab`, `tc/package.scala` and
`ELPDiagnostics`. Read [AGENTS.md](AGENTS.md) and [ProjectStatus.md](ProjectStatus.md)
before continuing. Use the metadata file for the current baseline rather than
adding another independently maintained pin here.

## #4 — Make expression traversal exhaustive

- [ ] Replace the final wildcard in `ExhaustiveCase.checkExpr` with explicit
  handling of every variant of the sealed `Expr` hierarchy in `ast/Exprs.scala`.
- [ ] List intentionally unvisited variants explicitly and explain why they are
  skipped. Preserve existing traversal, environment propagation and diagnostics
  in this first change. Review wildcard matches by purpose; conservative coverage
  fallbacks elsewhere should not be removed mechanically.
- [ ] Verify a newly introduced expression variant would require a deliberate
  traversal decision: the compiler's exhaustivity warning must fail the build
  under the existing `-Werror`, rather than silently hiding nested cases.

The risk today is that a new upstream expression compiles successfully but its
nested cases are ignored. The initial improvement can preserve runtime behavior
while adding a compile-time safeguard. Visiting previously skipped children or
improving environments is separate feature work and can add/change diagnostics.

Validate with the Scala suite and literate Erlang examples, preserving the
November 19 dynamic-pattern and multi-template regressions. Prefer source
examples for supported syntax; retain AST tests for shapes the tested OTP cannot
parse. Run `make clean` if AST/protocol definitions change.

## #5 — Review integration changes outside the imported subtree

Partially addressed by #1: `scripts/sync-upstream.py` already reports integration
paths through `INTEGRATION` and also lists all other changes outside `eqwalizer/`.
An unchanged subtree alone does not establish compatibility with a newer ELP.

- [ ] Review and maintain the monitored paths as upstream evolves: Rust IPC and
  serialized AST/type definitions, `erlang_service` parsing/conversion, CLI
  diagnostic serialization/output, and project loading/OTP/include resolution.
  Inspect the other outside changes too; the path list is a review aid.
- [ ] Include the external integration review and its validation limits in each
  synchronization report, separately from checker-source synchronization.
- [ ] Select additional tests according to workflows actually used by the
  maintainer. Candidates are Buck/header discovery, daemon/LSP updates, and
  `ipc-check-funs`; do not automatically expand into all upstream services.
- [ ] Keep new fixtures and tooling fork-owned where practical. For changes to
  the monitoring tool, extend `tests/synchronization/` and run `make test-sync`.

The real-source suite covers the normal CLI path, not all these workflows.
`ipc-check-funs` currently calls `Pipeline.checkFunction` directly and does not
load raw strict directives; characterize that difference without silently
changing it. Preserve the boundary that fork production changes stay within the
checker module. Full ELP/Erlang integration validation may be performed locally
by the maintainer; state precisely which checks were actually run.

Monitoring and documentation do not change checker runtime behavior. New tests
can expose compatibility problems; any resulting behavior fix needs its own
explicit scope and regressions.

## #6 — Separate feature requirements from known-limit characterizations

- [ ] Add an explicit distinction to the literate source suite between required
  behavior and examples recording a known limitation. Decide a small annotation
  format, document it, and expose the distinction in runner output.
- [ ] Classify the existing examples, starting with `coarse_numeric/1` and
  `repeated_variable/2` in `tests/exhaustiveness/src/exhaustiveness.erl`. Their
  current lack of warnings is an approximation, not a correctness requirement.
- [ ] Keep characterization examples executable and retain their current
  expectations initially. Continue rejecting unexpected diagnostics and preserve
  the per-module execution probes; do not turn this into a blanket exemption.
- [ ] When a limitation is fixed, deliberately update its expectation, prose and
  classification together so the improvement is reviewed as such.
- [ ] Extend runner tests for annotation validation, matching and reporting; run
  `make test-runner` and the real-source suite through the matching ELP.

Classification changes test policy and documentation, not checker runtime
behavior. Fixing numeric coverage, repeated-variable equality or other limitations
is separate behavior-changing work.

## Suggested continuation

Start with #4's behavior-preserving traversal safeguard, then #6's explicit test
classification. Apply #5's review discipline on each upstream synchronization and
add integration coverage when a concrete maintainer workflow warrants it. Update
this checklist and `ProjectStatus.md` as work is completed.
