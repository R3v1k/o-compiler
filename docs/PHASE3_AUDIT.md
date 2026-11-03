# Phase 3 repository audit and implementation plan

Audit reference: `docs/Project-O-specification.pdf`, `DOCUMENTATION.md`, the
complete existing Kotlin source and test classes, build configuration, and all
shipped `.o` examples and fixtures. The initial workspace was empty; the
requested GitHub repository was cloned into it at commit
`b5fd2842ca7c487a8619b54f78f9d56152658914`. The cloned working tree was clean.

## Baseline and structure

The project uses Kotlin 2.0.21, Gradle Kotlin DSL, JVM toolchain 21, the
`application` plugin with `o.MainKt`, and `kotlin.test` on JUnit 5. The root
agent ran the unmodified suite successfully: **104 tests, 0 failures**
(51 lexer tests and 53 parser tests). This is the pre-implementation baseline,
not the final Phase 3 test count.

The implementation retains the existing grammar and build configuration;
no parser framework or new production dependency was introduced.

Production sources initially comprised `Token.kt`, `Lexer.kt`, `Ast.kt`,
`Parser.kt`, `AstPrinter.kt`, and `Main.kt`. `src/test/kotlin/o` held the two
test classes. `tests/` contains lexer/parser fixtures; `examples/` contains
course demonstrations and specification probes. No interpreter, bytecode
generator, Jasmin integration, or runtime library implementation existed.

## AST and locations

Every `Node` carries one-based `line` and `column`. The initial hierarchy is:

```text
Program -> ClassDecl -> Member
  Member = VarDecl | MethodDecl | ConstructorDecl
  MethodDecl -> Param, TypeRef, MethodBody?
  MethodBody = Block(List<Statement>) | Arrow(Expr)
  Statement = VarDecl | Assignment | While | If | Return | ExprStatement
  Expr = IntLit | RealLit | BoolLit | This | Name | MemberAccess | Call
```

`TypeRef` recursively stores type arguments. `VarDecl` is both a statement and
a member; its container distinguishes local variables from fields. Nullable
method bodies represent forward declarations. `Name` deliberately does not
decide between class, variable, and method names. `Call(Name, args)` can be a
constructor or unqualified method invocation. `Call(MemberAccess, args)` is
an explicit receiver invocation; bare `MemberAccess` also permits zero-argument
methods. These ambiguities require symbol resolution, not grammar changes.

Most positions refer to the first token. `MemberAccess` and member-call `Call`
positions refer to the member name. Inherited base names are stored as strings,
so inheritance diagnostics can preserve only the enclosing class location.
The AST consists of immutable Kotlin data classes and read-only lists.

## Supported grammar and reconciled specification issues

The hand-written parser accepts a sequence of classes; optional single
inheritance; initialized fields and locals; overloaded method headers with
optional parameter lists and return types; block and arrow method bodies;
forward headers; constructors; identifier assignments; `if`, `while`, return,
and expression statements. Expressions are literal/name/`this` primaries
followed by member chains and optional argument lists. Arithmetic is a method
call, for example `2.Plus(3.Mult(4))`, never an infix AST operation.

Existing conventions retained for Phase 3:

| Specification discrepancy | Project convention |
|---|---|
| Examples use `var x is 1`; grammar uses `:` | Only `var x : expression` parses; type is inferred. |
| Ambiguous, left-recursive expression grammar | Iterative `Primary { . Identifier [ Arguments ] }`. |
| No expression statements in grammar, but `a.set(i,v)` appears | Expression statements remain accepted. |
| Examples assign to `a.set(i)` | Assignment target remains a bare identifier. |
| Generics excluded, but library uses `Array[T]` / `List[T]` | Generic type uses parse; generic class declarations do not. |
| Library has empty parameter lists despite grammar | `()` remains legal. |
| Example has a top-level `MaxInt` method | Program remains a sequence of classes. |
| Zero-argument methods appear without parentheses | Both supported invocation forms remain representable. |
| Fields `Min`/`Max` are used through class names | Library constant access needs an explicit semantic convention. |
| Return may omit an expression | Allowed syntactically; semantic return context determines validity. |

The specification describes `Class`, `AnyValue`, `AnyRef`, `Integer`, `Real`,
`Boolean`, `Array`, and `List` as implicitly available classes. Numeric
cross-type constructors and arithmetic overloads are explicit; this does not
justify implicit numeric assignment conversion. The specification does not
define integer overflow, floating-point representation, I/O, strings, null,
base-constructor invocation, or array bounds precisely. Phase 3 must document
its bounded implementation and avoid inventing syntax for these features.

All legacy examples except `04_error.o`, `11_probe_spec_examples.o`, and
`13_parse_error.o` parse. The first fails lexically and the other two fail
syntactically. `12_probe_missing_features.o` parses because unsupported ideas
are comments. Successful parsing alone does not establish semantic validity.
The parser rejects out-of-range `Int` literals and exposes no negative-literal,
infix-expression, `break`, or standalone block construct. A return consumes a
following expression even across a newline; a declaration after a return is a
safe way to demonstrate unreachable code without this layout ambiguity.

## Implementation plan followed

1. Keep the lexer, grammar, AST representation, parser-only CLI behavior, and
   all 104 existing tests. Add semantic symbols and lexical scopes beside the
   parser. Collect class/signature information, validate inheritance, resolve
   inferred variables and calls, and check types and return paths without
   mutating the AST.
2. Add four independent immutable AST passes: literal built-in call folding,
   constant-conditional simplification, unreachable statement elimination,
   and conservative unused-local elimination. Gate transformations on semantic
   success and preserve scope, source positions, errors, and side effects.
3. Extend the CLI with explicit analysis, selected optimization passes, and
   before/after AST output. Keep source diagnostics positioned and usage errors
   distinct from invalid-source errors.
4. Add semantic, optimizer, integration, nonmutation, idempotence, and legacy
   regression tests; execute all tests and the shipped demonstrations. Publish
   this audit, a demo guide, a defense note, and the English report/PDF.

Final implementation conventions and verification results are recorded in
`DOCUMENTATION.md` section 20 onward and `PHASE3_REPORT.md`.
