**This standalone fork adds conservative exhaustiveness checking, private record
constructors, and strict comment directives to eqWAlizer. These features are on `main`.**

Upstream baseline: [ELP `2026-10-14` (`b36ba37346a8e6122fd309420cb67886ccc5871c`)](https://github.com/WhatsApp/erlang-language-platform/tree/b36ba37346a8e6122fd309420cb67886ccc5871c/eqwalizer).
See [the upgrade report](docs/upstream-upgrade-2026-10-14.md) for compatibility,
customizations, validation results, and future upgrade instructions.

## Developing this fork

Use Java 11 or newer (validated here on Java 21), sbt 1.10.10, and the pinned Scala 3.6.4:

```sh
make test
make build
make smoke
make jar-path
```

The JAR is `eqwalizer/target/scala-3.6.4/eqwalizer-strict.jar`.
The Scala tests and synthetic IPC checks run without ELP or Erlang.
For readable source-based feature tests, run `make feature-test` with the pinned
ELP and matching OTP installed. OTP discovery uses your existing setup; a separate
source tree can optionally be selected with `OTP_SOURCE=/path/to/otp-source`. The Erlang fixtures are
[executable documentation](tests/exhaustiveness/README.md) for our custom features and
are kept separate from the imported checker and its tests.

To use the JAR with ELP, point `ELP_EQWALIZER_PATH` at an executable script containing:

```sh
#!/bin/sh
exec java -jar /absolute/path/to/eqwalizer-strict.jar "$@"
```

The wrapper inherits ELP's runtime environment, including `EQWALIZER_MODE`.
Installing ordinary ELP alone uses its bundled checker, not this fork.

---

# eqWAlizer

A type-checker for Erlang.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./logo/eqWAlizer_final_Full_Logo_White_Text.png">
  <img alt="eqWAlizer logo" src="./logo/eqWAlizer_final_Full__Logo_Black_Text.png" width="100%">
</picture>

> [!IMPORTANT]
For upstream issues, use [erlang-language-platform](https://github.com/whatsapp/erlang-language-platform). Report fork-specific issues in this repository.

## Using it with rebar3 projects

eqWAlizer is integrated and distributed with ELP (Erlang Language Platform).

1. [Install ELP](https://whatsapp.github.io/erlang-language-platform/docs/get-started/install/).
2. From the project directory run:
  - `elp eqwalize <module>` to type-check a single module
  - `elp eqwalize-all` to type-check all `src` modules in the project

ELP adds its bundled copy of the `eqwalizer_support` library to your project.
Specs that refer to `eqwalizer` types compile and run without the library, but other tools, such as Dialyzer,
report those types as unknown. If you use such tools, add the library to your dependencies, and ELP uses your copy:

```
{deps, [
  {eqwalizer_support,
    {git_subdir,
        "https://github.com/whatsapp/eqwalizer.git",
        {branch, "main"},
        "eqwalizer_support"}}
]}.
```

## Using it with non-rebar projects

1. [Install ELP](https://whatsapp.github.io/erlang-language-platform/docs/get-started/install/).
2. [Write a `project.json`](https://whatsapp.github.io/erlang-language-platform/docs/get-started/configure-project/custom-project/) file describing your project.
3. From the project directory, assuming your `.json` file is called `project.json` run:
  - `elp eqwalize <module> --project project.json` to type-check a single module
  - `elp eqwalize-all --project project.json` to type-check all `src` modules in the project

## FAQ

Please refer to [the FAQ document](./FAQ.md) for answers to some common questions,
including:

- What's the difference between eqWAlizer and Dialyzer?
- Why not extend Dialyzer, rather than creating a new tool?
- What about Elixir?

## Where can I learn more about eqWAlizer, its technical principles, its relation to dialyzer and elixir support feasibility?

[Here](https://www.beamrad.io/35).

## License

Imported upstream files carry [Apache](LICENSE-APACHE) / [MIT](LICENSE-MIT) dual-license notices.
Existing fork contributions retain their original [Apache](LICENSE) notices.
