# Phase 3 defense notes

Use `PHASE3_DEMO.md` for executable commands and `PHASE3_REPORT.md` for the
short academic report. The following answers explain implementation decisions.

## Which changes meet the mandatory requirements?

Semantic validation does not transform the tree. Symbol/declaration resolution
and type checking are two independent required checks; return-path, duplicate,
inheritance, and overload validation extend them. Four AST transformations
operate on validated input: constant folding, constant conditional
simplification, unreachable statement removal, and conservative unused-local
removal. They reconstruct data-class nodes, so the parsed tree remains
available for comparison.

## Why does `var x : 1` give x type Integer?

The colon introduces an initializer, not a type annotation. O infers a
variable's type from the initializer; only parameters and method results have
explicit type references. An assignment checks against the inferred type.
`var x : 1` followed by `x := true` therefore fails. Integer-to-Real conversion
is explicit; numeric library overloads can still accept both numeric types.

## How can a parser confuse construction and calls?

`Counter()` and `calculate()` are both `Call(Name, args)`. Capitalization is
not a language rule. Semantic lookup uses classes, variables, methods, and the
current class to resolve the neutral parser nodes. Similarly, `a.read` can be
a field read or a zero-argument method call. Resolution is performed with
signatures and types rather than guesses from spelling. Locals and fields
take precedence over current-object methods, which take precedence over class
construction. `this.method(...)` explicitly selects a method when its name
also names a class; class-constant access honors the same shadowing rules.

## How do scopes and declaration order work?

A scope stores local bindings and has an outer scope. Initializers are checked
before their new local is entered, so `var x : x` can read an outer x but does
not silently initialize itself. Duplicate declarations within one scope fail;
a nested branch or loop may shadow an outer local. Parameters are visible in
their routine. Class signatures are collected separately to support method
resolution and forward declaration completion. Inherited lookup follows the
validated single-inheritance chain.

## Why keep an `If true` after conditional optimization?

The parser has no standalone scoped-block node. Flattening a selected branch
that declares locals can leak those locals or collide with an outer binding.
The pass drops the unreachable alternative but retains a canonical
`If(true, selectedBody, null)` wrapper if that scope is needed. A selected
branch without top-level declarations can be flattened. The combined pipeline
repeats until unchanged, so another pass removing a harmless local can later
permit flattening.

## Why not fold every call named Plus?

User classes can define arbitrary methods, including `Plus`. Folding only
recognizes supported built-in operations on literal receivers and literal
arguments after semantic validation. It visits nested calls first, so
`2.Plus(3.Mult(4))` becomes 14. Constructors and arbitrary user methods remain
calls. The optimizer does not implement function inlining or speculative
evaluation.

## What about zero division, overflow, and floating-point rounding?

Integer folding checks range and keeps possible overflow and division or
remainder by zero as calls. No runtime overflow policy is invented by a
compile-time rewrite. Real folding is conservative: operands/results must be
finite and the computed operation must be exactly representable under the
implemented checks. Expressions such as `0.1.Plus(0.2)` remain calls. The
current phase has no runtime, so these safeguards avoid claiming equivalence
to an unspecified execution policy.

## Why is unused-variable removal so conservative?

An unread variable can still matter through its initializer. A constructor can
mutate state; an arbitrary method can have side effects; arithmetic can fail.
The pass removes only literal-initialized locals with no references to their
specific binding. Assignments count as uses, as do reads in nested scopes.
Fields are kept. This sacrifices some optimization opportunities to preserve
observable behavior. `optimization_unused_variables.o` shows both deletion
and retention in one tree.

## What proves a statement is unreachable?

A direct return ends execution of its statement list. An if/else also ends
that list if both alternatives definitely return. The analysis descends into
nested bodies and removes following statements. It does not assume an
arbitrary loop terminates or returns. The semantic analyzer still checks
unreachable source before optimization, so dead text cannot hide type errors.

## What are the limits of the demonstration?

The CLI parses, checks, and prints transformed ASTs. It does not run O
programs, generate bytecode, or demonstrate actual dynamic dispatch at runtime.
General user-defined generics, whole-program effects, alias analysis, static
array bounds proofs, constant propagation, and function inlining are outside
this phase. Library Array/List type uses are supported separately from generic
class declarations. Tests establish AST changes, diagnostic behavior,
nonmutation, and regression properties; they are not an interpreter-based
proof of equivalence.
