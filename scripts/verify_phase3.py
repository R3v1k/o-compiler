#!/usr/bin/env python3
"""Exercise the installed CLI, including its real process exit statuses.

Run ./gradlew clean build installDist first, then python3 scripts/verify_phase3.py.
Uses only the Python standard library. Logs are generated below build/phase3-smoke/.
"""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "build/install/o-compiler/bin/o-compiler"
LOGS = ROOT / "build/phase3-smoke"


def main():
    if not CLI.is_file():
        raise SystemExit("Build the CLI first: ./gradlew build installDist")
    LOGS.mkdir(parents=True, exist_ok=True)
    results = []

    def check(label, args, code=0, contains=(), excludes=()):
        result = subprocess.run([str(CLI), *args], cwd=ROOT, text=True, capture_output=True)
        output = result.stdout + result.stderr
        (LOGS / f"{len(results) + 1:02d}-{label}.txt").write_text(output)
        assert result.returncode == code, f"{label}: expected exit {code}, got {result.returncode}\n{output}"
        for text in contains:
            assert text in output, f"{label}: missing {text!r}\n{output}"
        for text in excludes:
            assert text not in output, f"{label}: unexpected {text!r}\n{output}"
        results.append((label, code))

    check("help", ["--help"], contains=("--analyze", "--passes="))
    check("usage", ["--unknown"], 2, contains=("usage:",))
    check("missing-file", ["no-such-source-file.o"], 1, contains=("file not found",))
    check("legacy-tokens", ["--tokens", "examples/03_tricky.o"], contains=("LEXEME", "EOF"))
    check("legacy-parser", ["examples/08_polymorphism.o"], contains=("O LANGUAGE PARSER", "Class Penguin"))
    check("lexical-error", ["examples/04_error.o"], 1, contains=("LEXICAL ERROR",))
    check("syntax-error", ["examples/13_parse_error.o"], 1, contains=("SYNTAX ERROR",))

    failures = {
        "semantic_undefined_variable.o": "counter",
        "semantic_type_error.o": "Integer",
        "semantic_wrong_arguments.o": "pick",
        "semantic_inheritance_cycle.o": "cycle",
    }
    excluded = {"04_error.o", "11_probe_spec_examples.o", "13_parse_error.o", *failures}
    valid = sorted(p for p in (ROOT / "examples").glob("*.o") if p.name not in excluded)
    for path in valid:
        check("analyze-" + path.stem, ["--analyze", str(path.relative_to(ROOT))],
              contains=("Semantic analysis successful",))
        check("optimize-" + path.stem, ["--optimize", str(path.relative_to(ROOT))],
              contains=("AST AFTER",), excludes=("SEMANTIC ERROR",))
    for name, diagnostic in failures.items():
        check("reject-" + Path(name).stem, ["--optimize", "--ast-before", "examples/" + name], 1,
              contains=("SEMANTIC ERROR", diagnostic), excludes=("AST AFTER", "Exception in thread"))

    check("fold-before-after", ["--optimize", "--ast-before", "--passes=fold",
                               "examples/optimization_constant_folding.o"],
          contains=("AST BEFORE", "AST AFTER", "IntLit 14", "BoolLit false"))
    check("scope-preserved", ["--ast-after", "--passes=fold,conditional",
                              "examples/optimization_if_simplification.o"],
          contains=("BoolLit true", "Var branchValue"), excludes=("IntLit 20",))
    check("unreachable-removed", ["--optimize", "--passes=unreachable",
                                   "examples/optimization_unreachable.o"],
          excludes=("Var unreachable", "Var unused"))
    check("unused-safety", ["--optimize", "--passes=unused",
                             "examples/optimization_unused_variables.o"],
          contains=("Var effectful", "Var constructed", "Var mayFail"), excludes=("Var removable",))

    report = ["# Phase 3 installed CLI verification", "",
              f"All {len(results)} process-level checks passed.", "",
              "Command: `python3 scripts/verify_phase3.py` after `./gradlew clean build installDist`.", "",
              "Each check asserts the process exit code and relevant output. Full transcripts are in `build/phase3-smoke/`.", "",
              "| Check | Expected and actual exit |", "|---|---|"]
    report.extend(f"| {label} | {code} |" for label, code in results)
    (LOGS / "VERIFICATION.md").write_text("\n".join(report) + "\n")
    print(f"PASS: {len(results)} installed CLI checks; logs: {LOGS.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
