# Project O — Phase 3 lab demonstration

Run commands from the repository root. Java 21 is the configured toolchain;
the Gradle wrapper supplies Gradle. These commands analyze and transform ASTs;
the project does not yet execute O programs or generate bytecode.

## Build and test

```bash
./gradlew clean build installDist
./gradlew test
python3 scripts/verify_phase3.py
build/install/o-compiler/bin/o-compiler --help
```

Use the installed launcher for demonstrations so invalid-source exit codes are
visible directly, without Gradle's task-failure banner. The equivalent Gradle
form is `./gradlew run --args="--analyze examples/semantic_valid.o"`.

## 1. Preserve the earlier phases

```bash
build/install/o-compiler/bin/o-compiler examples/08_polymorphism.o
build/install/o-compiler/bin/o-compiler --tokens examples/03_tricky.o
build/install/o-compiler/bin/o-compiler examples/13_parse_error.o
```

The first prints the original parse tree, the second prints tokens, and the
third demonstrates a syntax error with a source caret and exit status 1.
The default remains parsing; semantic analysis is requested explicitly.

## 2. Semantic analysis without AST changes

```bash
build/install/o-compiler/bin/o-compiler --analyze --ast-before examples/semantic_valid.o
```

Point out inferred local types, a forward method completed later in the same
class, Integer/Real overloads, inherited `read`, and the assignment of a
`NamedCounter` instance to a variable inferred as `Counter`. Analysis builds
symbol/type information separately from the immutable parser tree.

Each command below parses successfully, then reports a semantic error and exits
1. Run `echo $?` immediately after one command to show its exit status.

```bash
build/install/o-compiler/bin/o-compiler --analyze examples/semantic_undefined_variable.o
build/install/o-compiler/bin/o-compiler --analyze examples/semantic_type_error.o
build/install/o-compiler/bin/o-compiler --analyze examples/semantic_wrong_arguments.o
build/install/o-compiler/bin/o-compiler --analyze examples/semantic_inheritance_cycle.o
```

Explain respectively: undeclared `counter`; Boolean assignment to an Integer
variable; a real method with no overload matching zero arguments; and a cycle
between two otherwise known classes. Diagnostics include the location and
source excerpt. Optimization of any such file is rejected before a pass runs:

```bash
build/install/o-compiler/bin/o-compiler --optimize --ast-after examples/semantic_type_error.o
```

## 3. Four independent transformations

```bash
build/install/o-compiler/bin/o-compiler --optimize --passes=fold --ast-before --ast-after examples/optimization_constant_folding.o
build/install/o-compiler/bin/o-compiler --optimize --passes=fold,conditional --ast-before --ast-after examples/optimization_if_simplification.o
build/install/o-compiler/bin/o-compiler --optimize --passes=unreachable --ast-before --ast-after examples/optimization_unreachable.o
build/install/o-compiler/bin/o-compiler --optimize --passes=unused --ast-before --ast-after examples/optimization_unused_variables.o
```

| Pass | Before | What to show afterward |
|---|---|---|
| `fold` | `2.Plus(3.Mult(4))` | `IntLit 14`; relation and Boolean/Real calls also become literals. `1.Div(0)` remains a call. |
| `conditional` | `if 3.Less(5)` with then/else | Folding gives `true`; the else branch disappears. The `If true` wrapper remains because `branchValue` needs its own lexical scope. |
| `unreachable` | A declaration after `return 42`; a declaration after an if/else whose branches return | Both declarations disappear; reachable returns remain. |
| `unused` | `removable : 7`, an actually read `used`, and three unsafe initializers | Only `removable` disappears. The used local, arbitrary call, constructor, and possible division failure remain. |

Internally each pass recursively reconstructs immutable nodes. Folding visits
children first, handles only recognized built-in literal operations, and checks
overflow, division by zero, and safe Real results. Conditional simplification
selects a known branch and preserves a scope wrapper when its declarations
would otherwise escape. Unreachable elimination scans statement lists after
guaranteed return, including if/else with returning branches. Unused elimination
resolves bindings across nested scopes and removes only unread, unassigned
locals whose initializers are literals. It never removes fields.

## 4. Combined pipeline

```bash
build/install/o-compiler/bin/o-compiler --optimize --ast-before --ast-after examples/optimization_constant_folding.o
build/install/o-compiler/bin/o-compiler --ast-after examples/optimization_if_simplification.o
```

`--optimize` enables all passes by default. `--ast-after` also implies
optimization. Selected passes run in the canonical order `fold`, `conditional`,
`unreachable`, `unused`, repeating until the AST is unchanged. This allows one
pass to expose another safe transformation without relying on the order of
names in `--passes`.

Exit status 0 means successful parsing/analysis/optimization or help; 1 means an
input, lexical, syntax, or semantic error; 2 means an invalid command line.
For example `--passes=unknown` is a usage error, and `--passes=fold` requires
`--optimize` or `--ast-after`.

The Python verifier invokes the installed launcher, checks real process exit
codes, validates the working example suite, exercises semantic failures and
the four passes, and checks preservation of earlier CLI behavior.

Verified results: **278 automated tests passed** (0 failures/errors/skips), and
**49 installed-CLI process checks passed**. Generated process transcripts are
in `build/phase3-smoke/` after running the verifier.

For test totals and known limits, use `PHASE3_REPORT.md`; its PDF is
`../output/pdf/phase3-report.pdf`. For likely discussion questions, use
`PHASE3_DEFENSE.md`.

To regenerate the report PDF from its Markdown source (optional, requires the
Python `reportlab` package):

```bash
python3 scripts/render_phase3_report.py
```

The compiler build itself has no Python or ReportLab dependency.
