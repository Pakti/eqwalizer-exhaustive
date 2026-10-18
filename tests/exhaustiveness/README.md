# Executable documentation for the fork

The `.erl` files in [src](src) are ordinary Erlang modules with prose and test
expectations in comments. Read them as examples, or execute them with the real
ELP parser/type database and this fork's assembled checker. These are fork-owned
acceptance tests; they do not alter or depend on upstream's test suite.

## Run on Linux Mint

You need Java 11+, sbt 1.10.10, Python 3.9+, Erlang/OTP and ELP.
The tested combination is ELP **2026-10-14** and OTP **27.3.4.1** on Linux x86_64.
Use an ELP release asset built for your OTP major version. Use the same `erl` and
`escript` as your working ELP installation, including a source-built installation.
No system package installation is required by the tests.

By default, the runner leaves OTP source discovery to ELP, just like a normal
`elp eqwalize-all` invocation. ELP queries the selected runtime's root directory
and indexes the OTP sources available there for module types and specs. You do
not normally need to pass their location explicitly. The previous runner's
check for one installed-source layout was unnecessarily restrictive and has been
removed. No rebar3, network package dependencies, Rust checkout or ELP changes
are needed to run the fixtures.

If your matching OTP source tree is separate from the runtime installation and
ELP's default discovery is insufficient, use an optional source-root override:

```sh
OTP_SOURCE=/path/to/otp-source make feature-test
```

Or pass `--otp-source /path/to/otp-source` to the runner. This is the root of the
matching Erlang/OTP source checkout, containing `lib/` and
`erts/preloaded/src/erlang.erl`, not your application's source directory. An
installed development tree with `lib/erts-VERSION/src/erlang.erl` is also accepted.
The runner adds source apps as dependencies in its temporary ELP manifest; it
does not install packages, copy sources into your runtime, or modify the OTP tree.
Keep the source tree and selected runtime version aligned. The normal automatic
path does not add these overrides or impose this directory layout.

Install the [pinned ELP release](https://github.com/WhatsApp/erlang-language-platform/releases/tag/2026-10-14)
and verify the downloaded asset's published SHA-256. With the tools on PATH, run
from the repository root:

```sh
make feature-test
```

This builds our JAR and runs the examples. To choose an ELP executable explicitly:

```sh
ELP=/path/to/elp make feature-test
```

To run an existing build, customize paths, or retain generated files/logs:

```sh
python3 tests/exhaustiveness/run.py \
  --elp /path/to/elp \
  --erl /path/to/erl \
  --escript /path/to/escript \
  --jar /path/to/eqwalizer-strict.jar \
  --keep-workdir
```

`ELP`, `ERL` and `ESCRIPT` environment variables also select executables.
`--fixtures /path/to/features` accepts another directory with the same `src/` and
optional `include/` layout; source strings can be written into that directory.
Use `--timeout SECONDS` for slower machines (default 180 seconds per ELP run).
Missing dependencies, timeouts and ELP failures are failures, not skipped tests.

The runner creates a temporary custom-project JSON manifest and a Java wrapper,
then runs `elp eqwalize --no-connect --format json` on the explicit fixture module
list. It overrides ELP's checker with the requested JAR, leaving an existing
daemon and your projects alone. It copies sources/headers into the temporary
directory and does not write generated files into the checkout.

## Author an example

```erlang
%% @test a function must cover every declared atom alternative
%% @expect non_exhaustive_function contains "'c'"
-spec missing_atom(a | b | c) -> ok.
missing_atom(a) -> ok;
missing_atom(b) -> ok.
%% @end
```

Each region starts with `%% @test NAME` and ends with `%% @end`. Inside it declare
either `%% @expect-clean` or one or more `%% @expect CODE` lines. An expectation
can append `contains "message fragment"`; use a stable distinguishing fragment
instead of a complete formatted message. Repeated expectations assert exact
counts. Regions use source line ranges, so the runner does not need to parse
Erlang functions itself. Keep the whole function (or attribute example) inside
its region and give every example a distinct descriptive name within its module.

All diagnostics are checked, including ordinary upstream type/parse errors.
Missing, extra, wrong-message and out-of-region diagnostics fail. A clean region
allows no diagnostics at all. The pinned CLI's generic JSON `code` is `ELP`; the
adapter extracts the actual eqWAlizer code from its `name` field. Schema changes
fail explicitly instead of silently discarding records.

The runner appends a small missing-case probe to each copied module. It requires
exactly one corresponding custom diagnostic per module, including modules whose
examples all expect clean results. Thus empty output, skipped modules, or a stock
checker without our custom pass cannot produce a successful run. The probe is
absent from the checked-in files and its diagnostic is matched separately.

Keep prose in the source comments close to each example. Two scanner details
matter when writing fixtures:

- A real strict directive must be immediately adjacent to its `-spec`/`-record`;
  place expectation annotations before the directive, not between it and the form.
- The source scanner treats even a prose comment mentioning the standalone word
  `eqwalizer_strict` as a malformed directive unless it follows directive syntax.
  Avoid such mentions inside executable fixtures unless testing that diagnostic.

Known-limit examples assert **current behavior**, not correctness guarantees.
For example, the numeric-literal and repeated-variable cases currently expect no
warning even though the Erlang functions are not exhaustive. When fixing such a
limitation, change its explanatory prose and expectation together with the code.

## Feature map

| Source | Executable documentation |
| --- | --- |
| [exhaustiveness.erl](src/exhaustiveness.erl) | Atom/case/function coverage, interesting arguments, catch-alls, products and limits, tagged tuples, basic guards, generic aliases, coarse numeric and repeated-variable limitations. |
| [binary_coverage.erl](src/binary_coverage.erl) | Empty/nonempty binary coverage and unsupported exact sizes. |
| [selector_calls.erl](src/selector_calls.erl) | Local, remote and dynamic call selectors, declared results, unknown selectors. |
| [simple_guards.erl](src/simple_guards.erl) | Record guards, aliases, numeric guards and unsupported conjunctions. |
| [strict_directives.erl](src/strict_directives.erl) | Function-scoped skip suppression without hiding missing cases. |
| [invalid_directives.erl](src/invalid_directives.erl) | Unsupported suppression targets, adjacency and comment-only syntax. |
| [box_owner.erl](src/box_owner.erl), [box_client.erl](src/box_client.erl), [private_box.hrl](include/private_box.hrl) | Ownership enforced from a shared header, creation/update permissions, permitted field access and pattern matching. |

The suite currently has **45 examples in eight modules**, with eight additional
execution probes. It is targeted integration coverage for our features, not a
replacement for upstream's complete ELP tests. Internal type-operation and
scanner edge cases remain in the existing Scala tests, which are unchanged.

The annotation/JSON adapter has its own nine Python tests, runnable without
Java, Erlang or ELP:

```sh
make feature-test-runner
```

The independent [feature CI workflow](../../.github/workflows/features.yml)
installs a compatible OTP/ELP pair and runs these source tests. Future upgrades
should usually need changes only to the CLI adapter in `run.py` and the ELP pin;
the documented behavior stays in the Erlang source files.
