# O Language — Example Programs

Test suite for the Compiler Construction project (Project O).
Target: Kotlin, hand-written lexer + recursive-descent parser, JVM bytecode via Jasmin.

## Files

| File | Feature under test |
|---|---|
| `01_minimal.o` | Smallest valid program; constructor as entry point |
| `02_arithmetic.o` | Method-call arithmetic; `=>` short body; Integer/Real mixing |
| `03_declarations.o` | Type inference from initializer; all literal forms |
| `03_tricky.o` | **Lexer demo**: `:` vs `:=`, `1.5` vs `1.Plus(2)`, `=>`, comments |
| `04_conditionals.o` | `if/then/else/end`; nesting; Boolean combinators |
| `04_error.o` | **Lexer demo**: illegal character `@`, error reporting (does not lex) |
| `05_loops.o` | `while/loop/end`; nested loops; loop-local variables |
| `06_arrays.o` | `Array[T]` as a parameter and local; `get`/`set`/`Length` |
| `07_inheritance.o` | `extends`; transitivity; constructors with parameters |
| `08_polymorphism.o` | Override; dispatch on dynamic type; base ref to derived object |
| `09_overloading.o` | Same name, different signatures; resolution in the library |
| `10_forward.o` | Method header without body; mutual recursion |
| `11_probe_spec_examples.o` | The spec's own examples that do not parse |
| `12_probe_missing_features.o` | Constructs promised or needed but absent |
| `13_parse_error.o` | **Parser demo**: `if` without `then`, syntax error reporting (lexes, does not parse) |

Files 01–10 are the working suite. Files 11–12 are deliberately not compilable —
they are the list of inconsistencies the project outline asks us to find.

`03_tricky.o` and `04_error.o` belong to the lexer phase: they are the inputs
shown in the phase-1 demo. `08_polymorphism.o` and `13_parse_error.o` are the
phase-2 (parser) demo inputs. See `DOCUMENTATION.md`.

## Conventions chosen

The specification contradicts itself in several places. Where it does, we picked
one reading and recorded it here. All of these need confirmation.

1. **Variable declaration uses `:`**, per the grammar
   (`var Identifier : Expression`), not `is` as in the examples.
2. **Array indices are 1-based**, following the `MaxInt` example. Never stated.
3. **`Array.Length`**, not `.Size` — the class listing wins over the example.
4. **Comments are `//` to end of line, and `/* ... */` blocks** — neither is
   defined in the grammar. `//` is used inside the library class listings in
   section 2 of the spec, so it is certainly part of O. `/* ... */` is used
   only once, in section 1, to comment out the generics paragraph — that is
   the specification document annotating itself, not an O program, so this
   one is a guess. The lexer accepts both; blocks do not nest.
5. **`ClassName [ Arguments ]` with no arguments** is written `C()`, so that a
   bare identifier always means a name and never a construction.
6. **Real literals** are `1.0`, `3.14` — a digit is required on both sides of
   the dot. This resolves the apparent ambiguity of `0.Minus(1)` with no space
   and no special case: the dot joins the number only when a digit follows it,
   so `0.Minus(1)` lexes as `INT_LITERAL DOT IDENTIFIER ...` as intended.
   There is no sign in a literal — negation is the method `UnaryMinus`.

The following were fixed when the parser was written (phase 2). Each answers
one of the questions below for now; see `DOCUMENTATION.md`, Phase 2, for the
grammar as implemented.

7. **Expression grammar** is rewritten without left recursion as
   `Expression : Primary { . Identifier [ Arguments ] }` and
   `Primary : literal | this | ClassName [ Arguments ]` (question 9).
8. **Expression statements are accepted** — an *extension* of the grammar.
   Without them `a.set(i, v)`, which the specification itself writes and
   `06_arrays.o` needs, cannot appear anywhere (question 2).
9. **The assignment target is a bare identifier**, exactly as in the grammar.
   `a.set(i) := 55` and `this.field := x` are syntax errors (question 5).
10. **Generic type arguments are accepted where a class is *used*** —
    `Array[Integer]` as a type, `Array[Integer](10)` as a construction — but a
    generic class *declaration* `class C[T]` is rejected as out of scope
    (questions 7 and 14).
11. **No top-level methods**: a program is a sequence of classes only, as the
    grammar says (question 8).
12. **An empty parameter list `()` is allowed**, although the grammar's
    `Parameters` requires at least one parameter: the library itself declares
    `Boolean.toInteger()`.
13. **The parser does not distinguish constructor calls from method calls.**
    `Foo(1)` may construct a `Foo` or call a method `Foo` of the current
    object (`get(i)`); a bare `x` may be a variable or a class. The parser
    builds one neutral node and semantic analysis resolves it.

## Questions for the lecturer

Ordered by how much they block us.

1. **Input/output.** Slide P5 lists it among the statements; the grammar and the
   library have nothing. Without it a compiled program cannot report anything.
   What form should it take?
2. **Expression statements.** `Statement` covers only assignment, while, if and
   return. How is `a.set(i, v)` — or any call to a method returning nothing —
   supposed to appear in a body?
3. **`var x : E` vs `var x is E`.** Which is the real syntax?
4. **Base constructor invocation.** No syntax exists. Is a derived constructor
   expected to run the base one implicitly?
5. **Assignment to members.** `Assignment : Identifier := Expression` allows only
   a bare name. Is `this.field := x` intended to be legal?
6. **`Integer.Min` / `Integer.Max`.** Declared as `var` but used as class-level
   constants. Are there static members?
7. **Generic classes in declarations.** `class C[T] is ... end` appears in the
   examples but `ClassDeclaration` has no type parameter list. Should the parser
   accept it and ignore it, or reject it?
8. **Top-level methods.** `MaxInt` is declared outside any class in the examples,
   but `Program` is a sequence of class declarations only.
9. **Expression grammar.** `Expression : ... | Expression { . Expression }` and
   `FunctionCall : Expression [ Arguments ]` are ambiguous and left-recursive.
   Is a rewrite to `Primary { . Identifier [ Arguments ] }` acceptable?
10. **`null` and reference initialization.** Every variable needs an initializer,
    but a reference member has nothing to point at yet.
11. **Return type of `List.append`** is `List`, not `List[T]` — is the element
    type meant to be lost?
12. **Empty return.** `return` with no expression in a method that declares a
    return type — error, or allowed?
13. **Block comments.** Is `/* ... */` part of O, or is it only the notation the
    specification document uses for its own annotations? If it is part of the
    language, are blocks meant to nest? We assumed yes to the first and no to
    the second; see convention 4 above.
14. **`ClassName : Identifier // [ [ ClassName ] ]`.** The generic part of this
    production is commented out, consistent with generics being out of scope —
    yet section 3 uses `Array[Integer]`, `List[Real]` and `class C[T]`. Should
    `[ ... ]` be accepted after a class name or rejected? (The lexer emits
    `LBRACKET`/`RBRACKET` either way, so only the parser is affected.)
