# Project O — Phase 1: Lexical Analyzer, Phase 2: Syntax Analyzer

Compiler Construction course, Innopolis University.
Language **O**, implementation in Kotlin on the JVM.

This document covers the first two phases of the compiler:

* **Phase 1 — lexical analysis**, sections 1–11.
* **Phase 2 — syntax analysis**, sections 12–19.

The Phase 1 text is unchanged except where Phase 2 changed a fact it states
(the project layout, the command line, the test counts); those places say so.

---

## 1. Overview

A compiler turns a source file into an executable artifact in a sequence of
phases. This deliverable implements the **first** one.

```
  Program.o
      |
      v
  +--------------------+     token stream      +----------+     AST
  |  LEXICAL ANALYSIS  | --------------------> |  PARSER  | -------->  ...
  |     (this phase)   |   CLASS, IDENT, ...   |          |
  +--------------------+                       +----------+
      |                                              |
      v                                              v
  LexerException                              SEMANTIC ANALYSIS
  (illegal character)                                |
                                                     v
                                            CODE GENERATION (Jasmin)
                                                     |
                                                     v
                                                 .class file
```

The **lexer** (scanner, lexical analyzer) reads the raw character stream and
groups it into **tokens** — the indivisible words of the language: keywords,
identifiers, literals and punctuation. It also throws away everything that
carries no meaning for the grammar: whitespace, line breaks and comments.

Its output is a flat `List<Token>`. Each token carries four things:

| Field | Purpose |
|---|---|
| `type` | the lexical category, e.g. `ASSIGN`, `IDENTIFIER`, `REAL_LITERAL` |
| `lexeme` | the exact text that was matched, e.g. `":="`, `"counter"`, `"1.5"` |
| `line` | 1-based line where the lexeme starts |
| `column` | 1-based column of its **first** character |

The positions are not decoration. Every later phase reports its diagnostics
through them — "undefined variable `x` at 12:9", "type mismatch at 40:15" — and
a token that does not know where it came from makes those messages impossible.
That is why positions are part of `Token` from the very first phase rather than
retrofitted later.

What the lexer deliberately does **not** do: it has no idea whether the token
sequence forms a valid program. `end class 5 :=` lexes perfectly well. Checking
structure is the parser's job (phase 2).

---

## 2. Technology stack

| Choice | Rationale |
|---|---|
| **Kotlin 2.0.21 / JVM 21** | The target of the whole project is JVM bytecode via Jasmin, so staying on the JVM keeps the toolchain uniform. Kotlin gives sealed/data classes and exhaustive `when`, which the later AST phases will use heavily. |
| **Gradle (Kotlin DSL)** | One command builds, tests and runs; the `application` plugin provides `./gradlew run`, so a demo needs no manual classpath. |
| **kotlin.test on JUnit 5** | Standard, zero extra configuration, readable assertions. |
| **Hand-written scanner** | See below. |

### Why a hand-written lexer and not a generator?

A generator (JFlex, ANTLR, Flex) was rejected on purpose:

1. **Error messages.** A generator reports "no viable alternative at input" or a
   generic token-recognition error. This lexer produces
   `Unexpected character '@' at line 13, column 16` with a caret under the
   character — which is exactly what the course asks to demonstrate.
2. **Control over the tricky cases.** The three ambiguities of O (`:`/`:=`,
   the isolated `=`, the dot after a digit) are each decided by one character of
   lookahead. Written by hand, each is three lines of obvious code; expressed as
   generator rules, they become longest-match interactions that are much harder
   to reason about and to explain at a defence.
3. **No build-time code generation.** No extra plugin, no generated sources in
   the build directory, nothing for a grader to install. The whole scanner is
   ~200 lines that can be read top to bottom.
4. **Educational value.** The point of the course is to understand scanning, not
   to configure a tool that does it.

The cost — we must write the character loop ourselves — is small for a language
whose alphabet is this narrow: O has no string literals, no escape sequences, no
nested comments and no infix operators.

---

## 3. Project structure

```
o-compiler/
├── build.gradle.kts              Gradle build: Kotlin JVM + application plugin,
│                                 mainClass = o.MainKt, JUnit 5 for tests
├── settings.gradle.kts           Project name (o-compiler)
├── gradle.properties
├── gradlew / gradlew.bat         Gradle wrapper — no local Gradle needed
├── .gitignore                    Notably un-ignores examples/*.o and tests/*.o,
│                                 since '*.o' is normally a C object file
├── DOCUMENTATION.md              This document (phases 1 and 2)
├── docs/
│   └── Project-O-specification.pdf   The course hand-out this phase
│                                 implements; section 5 below maps its grammar
│                                 onto the token set
│
├── src/main/kotlin/o/
│   ├── Token.kt                  TokenType enum, Token data class,
│   │                             LexerException
│   ├── Lexer.kt                  The scanner: Lexer(source).tokenize()
│   ├── Ast.kt                    (phase 2) AST node classes
│   ├── Parser.kt                 (phase 2) ParseException and the parser:
│   │                             Parser(tokens).parseProgram()
│   ├── AstPrinter.kt             (phase 2) AST -> indented tree for the CLI
│   └── Main.kt                   CLI: syntax tree or token table, error
│                                 report, exit codes
│
├── src/test/kotlin/o/
│   ├── LexerTest.kt              51 unit tests (kotlin.test)
│   └── ParserTest.kt             (phase 2) 53 unit tests
│
├── examples/                     O programs, at the project root so that
│   │                             ./gradlew run --args="examples/..." works
│   ├── 01_minimal.o              Smallest valid program
│   ├── 02_arithmetic.o           Method-call arithmetic, '=>' bodies
│   ├── 03_tricky.o               All lexer edge cases in one file  <-- demo
│   ├── 04_error.o                Contains '@' — error demo         <-- demo
│   ├── 08_polymorphism.o         Inheritance and overriding  <-- phase 2 demo
│   ├── 13_parse_error.o          'if' without 'then'         <-- phase 2 demo
│   ├── 03_declarations.o … 12_*.o   Wider suite for the later phases
│   └── README.md                 What each example covers, conventions
│                                 chosen where the spec contradicts itself
│
└── tests/                        Small .o fixtures consumed by the tests:
    ├── lexer_*.o                 must tokenize cleanly (':' vs ':=', numbers,
    │                             keywords, line comments, block comments,
    │                             a file with no trailing newline)
    ├── error_*.o                 must raise LexerException (illegal character,
    │                             bare '=', unterminated block comment)
    ├── parser_*.o                (phase 2) must parse
    ├── parse_error_*.o           (phase 2) must raise ParseException
    └── README.md
```

`examples/` and `tests/` live at the project root, next to `build.gradle.kts`,
so every path in this document is relative to the root and the demo command is
literally `./gradlew run --args="--tokens examples/03_tricky.o"`.

---

## 4. Token types

27 token types in total. `TokenType` is declared in `src/main/kotlin/o/Token.kt`.

### Keywords (13)

Reserved words are recognised through a single `Map<String, TokenType>` lookup.

| Token type | Lexeme | Used for |
|---|---|---|
| `CLASS` | `class` | class declaration |
| `EXTENDS` | `extends` | inheritance |
| `IS` | `is` | opens a class body, a method body, a constructor |
| `END` | `end` | closes any block |
| `VAR` | `var` | variable declaration |
| `METHOD` | `method` | method declaration |
| `THIS` | `this` | current object; also the constructor header |
| `WHILE` | `while` | loop header |
| `LOOP` | `loop` | opens the loop body |
| `IF` | `if` | conditional |
| `THEN` | `then` | opens the then-branch |
| `ELSE` | `else` | opens the else-branch |
| `RETURN` | `return` | return statement |

### Literals (3)

| Token type | Example lexemes | Note |
|---|---|---|
| `INT_LITERAL` | `0`, `42`, `100` | a run of digits |
| `REAL_LITERAL` | `1.5`, `2.5`, `3.14159` | digits, a dot, digits |
| `BOOL_LITERAL` | `true`, `false` | **not** keywords — see below |

`true` and `false` are mapped to `BOOL_LITERAL` on purpose. For every later
phase they are constants of type `Boolean`, exactly as `42` is a constant of
type `Integer`; making them keywords would force the parser to translate them
back into literals anyway.

### Identifiers (1)

| Token type | Example lexemes |
|---|---|
| `IDENTIFIER` | `Calculator`, `counter`, `Plus`, `isEven`, `x1`, `_tmp` |

An identifier starts with a letter or `_` and continues with letters, digits or
`_`. Note that library method names such as `Plus`, `Mult`, `Less` are ordinary
identifiers — the lexer knows nothing about the standard library.

### Symbols (9)

| Token type | Lexeme | Appears in |
|---|---|---|
| `COLON` | `:` | `var x : Integer`, `method f() : Integer` |
| `ASSIGN` | `:=` | `x := 5` |
| `DOT` | `.` | `a.Plus(b)` — the only way to call anything |
| `COMMA` | `,` | argument and parameter lists |
| `LPAREN` | `(` | calls, constructor invocations |
| `RPAREN` | `)` | " |
| `LBRACKET` | `[` | generic type arguments: `Array[Integer]` |
| `RBRACKET` | `]` | " |
| `ARROW` | `=>` | short method body: `method add(...) : Integer => a.Plus(b)` |

### End of stream (1)

| Token type | Lexeme | Note |
|---|---|---|
| `EOF` | `""` | always the last element of the list |

The stream always ends with exactly one `EOF`, including for an empty file. The
parser can therefore always look at "the next token" without a bounds check —
a small invariant that removes a whole class of `IndexOutOfBounds` bugs in
phase 2.

### What is *not* a token

O has **no infix operators at all**: `a + b` is written `a.Plus(b)`. So there is
no `PLUS`, `MINUS`, `LESS`, `EQUAL` — and, importantly, no standalone `=`.
Arithmetic and comparison reach the parser as `IDENTIFIER DOT IDENTIFIER
LPAREN … RPAREN`, indistinguishable from any other method call. That is a
language design decision that makes the lexer's symbol table unusually small.

---

## 5. Conformance to the language specification

Every decision in this scanner traces back to *Project O — Object-oriented
language*, the informal specification handed out with the course, kept in this
repository as `docs/Project-O-specification.pdf`. This section
shows that the token set is **derived from**, not invented alongside, the
grammar in section 1 of that document. Throughout this section,
"section N" refers to the specification PDF, not to this file.

### The grammar, and the terminals it requires

```
Program              : { ClassDeclaration }
ClassDeclaration     : class ClassName [ extends ClassName ] is
                           { MemberDeclaration }
                       end
ClassName            : Identifier          // [ [ ClassName ] ]
MemberDeclaration    : VariableDeclaration | MethodDeclaration
                     | ConstructorDeclaration
VariableDeclaration  : var Identifier : Expression
MethodDeclaration    : MethodHeader [ MethodBody ]
MethodHeader         : method Identifier [ Parameters ] [ : Identifier ]
MethodBody           : is Body end | => Expression
Parameters           : ( ParameterDeclaration { , ParameterDeclaration } )
ParameterDeclaration : Identifier : ClassName
Body                 : { VariableDeclaration | Statement }
ConstructorDeclaration : this [ Parameters ] is Body end
Statement            : Assignment | WhileLoop | IfStatement | ReturnStatement
Assignment           : Identifier := Expression
WhileLoop            : while Expression loop Body end
IfStatement          : if Expression then Body [ else Body ] end
ReturnStatement      : return [ Expression ]
Expression           : Primary | ConstructorInvokation | FunctionCall
                     | Expression { . Expression }
ConstructorInvokation: ClassName [ Arguments ]
FunctionCall         : Expression [ Arguments ]
Arguments            : ( ) | ( Expression { , Expression } )
Primary              : IntegerLiteral | RealLiteral | BooleanLiteral | this
```

In this metalanguage `{ }` means repetition and `[ ]` means an optional part —
they are **notation, not O syntax**, which is why the scanner rejects `{` and
`}` as illegal characters while accepting `[` and `]` (those do occur in real
programs, as generic type arguments: `Array[Integer]`).

Collecting every terminal that actually appears in the productions above gives
exactly the 27 token types of `TokenType`, with nothing missing and nothing
spare:

| Terminal in the specification | TokenType | First appears in |
|---|---|---|
| `class` | `CLASS` | `ClassDeclaration` |
| `extends` | `EXTENDS` | `ClassDeclaration` |
| `is` | `IS` | `ClassDeclaration`, `MethodBody`, `ConstructorDeclaration` |
| `end` | `END` | `ClassDeclaration`, `MethodBody`, `WhileLoop`, `IfStatement` |
| `var` | `VAR` | `VariableDeclaration` |
| `method` | `METHOD` | `MethodHeader` |
| `this` | `THIS` | `ConstructorDeclaration`, `Primary` |
| `while` | `WHILE` | `WhileLoop` |
| `loop` | `LOOP` | `WhileLoop` |
| `if` | `IF` | `IfStatement` |
| `then` | `THEN` | `IfStatement` |
| `else` | `ELSE` | `IfStatement` |
| `return` | `RETURN` | `ReturnStatement` |
| `:` | `COLON` | `VariableDeclaration`, `MethodHeader`, `ParameterDeclaration` |
| `:=` | `ASSIGN` | `Assignment` |
| `.` | `DOT` | `Expression` |
| `,` | `COMMA` | `Parameters`, `Arguments` |
| `(` `)` | `LPAREN` `RPAREN` | `Parameters`, `Arguments` |
| `[` `]` | `LBRACKET` `RBRACKET` | `ClassName` (generic type arguments) |
| `=>` | `ARROW` | `MethodBody` |
| `Identifier` | `IDENTIFIER` | almost every production |
| `IntegerLiteral` | `INT_LITERAL` | `Primary` |
| `RealLiteral` | `REAL_LITERAL` | `Primary` |
| `BooleanLiteral` | `BOOL_LITERAL` | `Primary` |
| *(end of input)* | `EOF` | — |

Note what the grammar does **not** contain: no `+`, `-`, `*`, `/`, `<`, `>`,
`=`, `;`, `{`, `}`, and no string literal. The specification is explicit about
why:

> For these library classes, methods are defined that implement the operations
> traditional for these types of operations — arithmetic, logical, and
> relational operations, as well as type conversion operations. All these
> operations are presented **in the form of appropriate methods**. Thus, the
> traditional infix notation of expressions is not supported in the language.

That single sentence is the reason `a.Plus(b)` is the only spelling of addition,
and therefore the reason the `1.Plus(2)` case in §7(c) below matters so much:
in O, calling a method on an integer literal is not an exotic corner, it is how
ordinary arithmetic is written.

### Literal syntax

The specification deliberately leaves the lexical form of literals open:

> The basic components of expressions are primary. These are either literals of
> library types (**their lexical syntax is up to implementers; it should be very
> simple and easy-to-read**), or the keyword `this` […]

The rules chosen here are the simplest ones that satisfy that instruction:

| Literal | Rule | Examples |
|---|---|---|
| `IntegerLiteral` | one or more decimal digits | `0`, `42`, `100` |
| `RealLiteral` | digits, `.`, digits — a digit is required on **both** sides | `1.5`, `2.5`, `3.14159` |
| `BooleanLiteral` | the words `true` and `false` | `true`, `false` |

Requiring a digit after the dot is not cosmetic: it is precisely what keeps
`1.Plus(2)` working (§7(c) below). There is no sign in a numeric literal,
because negation in O is the method `UnaryMinus`, not a prefix operator — so
`-5` is a lexical error, exactly as the grammar implies.

### Comments

The specification never defines comment syntax in its grammar, but it *uses*
comments throughout, and in two forms. Both are supported here.

**Line comments** appear inside the library class listings of section 2, more
than twenty times:

```
class Integer extends AnyValue is
    // Constructors
    this(p: Integer)
    // Features
    var Min : Integer
```

**Block comments** appear in section 1, where the author comments out the
generic part of a production and then the paragraph explaining it:

```
ClassName : Identifier // [ [ ClassName ] ]

/*****
The following language feature is not a subject for implementation within the
Compiler Construction course: […] generics […]
*****/
```

The evidence for `//` is overwhelming — it occurs inside actual O code. The
evidence for `/* … */` is weaker: there it annotates the specification document
rather than an O program. We accept both anyway, for three reasons: the
character `/` has no other meaning anywhere in O, so nothing can conflict;
accepting a superset can never reject a valid program; and if block comments
turn out to be unintended, removing them is a five-line deletion. It is listed
as an open question for the lecturer in `examples/README.md`.

### Known contradictions in the specification

The specification contradicts itself in several places. None of them affects
this phase — every one of the disputed spellings lexes without complaint, and
the disagreement only surfaces in the parser. They are recorded, with the
reading we picked, in `examples/README.md`, and demonstrated in
`examples/11_probe_spec_examples.o` and `examples/12_probe_missing_features.o`.
The two that come closest to touching the lexer:

* `var i is 1` (section 3) versus `var Identifier : Expression` (section 1).
  Both lex; `is` is `IS` and `:` is `COLON`, and the parser decides.
* `ClassName : Identifier // [ [ ClassName ] ]` says the generic part is out of
  scope, yet section 3 writes `Array[Integer]`, `List[Real]` and `class C[T]`.
  The scanner emits `LBRACKET` / `RBRACKET` regardless, so whichever way the
  question is resolved, phase 2 has the tokens it needs.

---

## 6. Implementation

### 6.1 State

The scanner keeps three cursors and nothing else:

```kotlin
private var pos = 0      // offset of the next character
private var line = 1     // 1-based line of source[pos]
private var column = 1   // 1-based column of source[pos]
```

### 6.2 The main loop

`tokenize()` dispatches on the current character and delegates to a reader that
consumes one whole lexeme:

```kotlin
while (!isAtEnd()) {
    val c = peek()
    when {
        c == '/' && peekNext() == '/' -> skipLineComment()
        c == '/' && peekNext() == '*' -> skipBlockComment()
        c.isWhitespace()              -> advance()
        c.isDigit()                   -> readNumber()
        isIdentifierStart(c)          -> readIdentifierOrKeyword()
        else                          -> readSymbol()
    }
}
tokens.add(Token(TokenType.EOF, "", line, column))
```

Order matters: the comment tests come before everything else, so that `//` and
`/*` are never mistaken for two symbols; and `isDigit` comes before
`isIdentifierStart`, so that a leading digit always starts a number. A `/` that
begins neither comment form falls through to `readSymbol`, which rejects it —
`/` is not a token of O, since division is the method `Div`.

Each reader follows the same shape — remember the start position, consume, emit:

```kotlin
val startLine = line
val startColumn = column
// ... consume characters with advance() ...
tokens.add(Token(type, text, startLine, startColumn))
```

### 6.3 How positions are maintained

Exactly one function moves the cursor, which is what keeps line and column
honest no matter which reader is running:

```kotlin
private fun advance(): Char {
    val c = source[pos]
    pos++
    if (c == '\n') { line++; column = 1 } else { column++ }
    return c
}
```

Because the start position is captured *before* the lexeme is consumed, the
column of a multi-character token such as `:=` or `1.5` points at its first
character, as required.

### 6.4 How keywords are resolved

Keywords are **not** matched in the main loop. The scanner first reads a maximal
run of identifier characters, and only then asks a map what that word is:

```kotlin
while (!isAtEnd() && isIdentifierPart(peek())) text.append(advance())
val type = KEYWORDS[text.toString()] ?: TokenType.IDENTIFIER
```

This is the *maximal munch* rule, and it is what makes `isEven` an identifier.
A scanner that instead tried to match keyword spellings inside the main loop
would consume `is` from `isEven`, emit `IS`, and then emit `IDENTIFIER(Even)` —
a bug that only shows up on innocent-looking user code. Adding a keyword to O
now means adding one line to the map and one constant to the enum; the scanning
code does not change at all.

---

## 7. Tricky cases

These four are the reason the lexer is written by hand. Each is shown with its
input, the produced tokens, and what a naive implementation does instead.

### (a) `:` versus `:=`

`:` introduces a type, `:=` is assignment. They share a first character, so the
decision needs one character of lookahead.

Input (from `examples/03_tricky.o`):

```
var counter : Integer(0)
counter := this.add(local, 1)
```

Output:

```
VAR        var        COLON      :          <- declaration
IDENTIFIER counter    ...
IDENTIFIER counter    ASSIGN     :=         <- assignment
```

Implementation:

```kotlin
':' -> if (!isAtEnd() && peek() == '=') { advance(); add(ASSIGN, ":=") }
       else                             { add(COLON, ":") }
```

**Why the naive version breaks.** Emitting `COLON` as soon as `:` is seen turns
`x := 5` into `COLON` followed by a stray `=`. Since `=` is not a token of O,
the file then fails to lex at all — every assignment in every program becomes a
lexical error. The `!isAtEnd()` guard matters too: a file that ends with `:`
must yield `COLON`, not an index-out-of-bounds crash.

### (b) `=` is not a token on its own

Because O has no infix operators, equality is `a.Equal(b)`. The character `=`
therefore occurs only as the first half of `=>`.

Input / output:

```
method add(a: Integer, b: Integer) : Integer => a.Plus(b)
                                             ^^
                                             ARROW  "=>"
```

But:

```
method f(a: Integer) : Integer = a
                                ^
Unexpected character '=' at line 3, column 36 ('=' is not an operator in O; did you mean '=>' ?)
```

Implementation:

```kotlin
'=' -> if (!isAtEnd() && peek() == '>') { advance(); add(ARROW, "=>") }
       else throw LexerException("Unexpected character '=' ...", line, column)
```

**Why the naive version breaks.** A scanner that carries an `EQUAL` token "just
in case" pushes the problem downstream: the parser then has to reject `=` with a
syntax error that points at a token the language does not even have, and the
programmer gets "unexpected token EQUAL" instead of "did you mean `=>`". The
lexer is the phase that owns the alphabet; it should reject the character here.

### (c) A dot after a digit

This is the case that only a language without infix operators produces, and the
most instructive one in the whole scanner. Compare:

| Input | Tokens |
|---|---|
| `1.5` | `REAL_LITERAL("1.5")` |
| `1.Plus(2)` | `INT_LITERAL("1")` `DOT(".")` `IDENTIFIER("Plus")` `LPAREN` `INT_LITERAL("2")` `RPAREN` |
| `1.5.Plus(2.5)` | `REAL_LITERAL("1.5")` `DOT` `IDENTIFIER("Plus")` `LPAREN` `REAL_LITERAL("2.5")` `RPAREN` |
| `1.` | `INT_LITERAL("1")` `DOT(".")` |

**The rule:** the dot is absorbed into the number *only if the character
immediately after it is a digit*. Otherwise the number ends and the dot becomes
a separate `DOT`.

```kotlin
while (!isAtEnd() && peek().isDigit()) text.append(advance())

if (!isAtEnd() && peek() == '.' && peekNext()?.isDigit() == true) {
    isReal = true
    text.append(advance())                                   // the '.'
    while (!isAtEnd() && peek().isDigit()) text.append(advance())
}
```

**Why the naive version breaks.** "A number is digits, optionally followed by a
dot and more digits" — implemented without checking what follows the dot —
consumes the dot of `1.Plus(2)`, produces `REAL_LITERAL("1.")`, and then hands
the parser `IDENTIFIER(Plus)` with no `DOT` between them. In a language where
`1.Plus(2)` *is* how you write `1 + 2`, that breaks arithmetic on literals in
every program. Note also the second line of the table: because only one dot is
ever absorbed, `1.5.Plus(2.5)` — a method call on a real literal — still works.

### (d) An unknown character must not crash the compiler

Input (`examples/04_error.o`, line 13):

```
        ok := 1@2
```

Output:

```
  LEXICAL ERROR  examples/04_error.o:13:16

    13 |         ok := 1@2
       |                ^

  Unexpected character '@' at line 13, column 16
```

process exit code `1`.

`LexerException(message, line, column)` carries the position separately from the
message so the CLI can render the caret; the message itself is already
self-contained for any other caller.

**Why the naive version breaks.** Two common failures: falling off the end of a
`when` with no `else` branch, which silently *drops* the character and produces
a token stream that no longer corresponds to the file; or letting a raw
`IllegalStateException` escape, so the user sees a JVM stack trace through
Kotlin internals instead of a line and a column. Note also that the `@` inside a
comment in the same file is *not* an error — comments are discarded before any
character classification happens.

---

## 8. Error handling

There is a single error type:

```kotlin
class LexerException(message: String, val line: Int, val column: Int) : Exception(message)
```

There are two message formats. The common one:

```
Unexpected character '<char>' at line <line>, column <column>
```

with an optional parenthesised hint appended, as for `=`. And one for the only
error that is not about a single character — a block comment that is never
closed:

```
Unterminated block comment opened at line <line>, column <column> (expected a closing '*/')
```

Its position is the **opening** `/*`, not the end of the file: that is the line
the author has to go and fix. The lexer stops at the first error — it does not attempt error recovery, because a
character outside the alphabet gives no reliable point to resynchronise from.

`Main.kt` catches it and prints a three-part report: a location header, the
source line with a caret under the offending column, and the message. Tabs in
the excerpt are expanded to four spaces on both the text and the caret prefix,
so the caret stays aligned regardless of indentation style.

Exit codes:

| Code | Meaning |
|---|---|
| `0` | the file was tokenized; the table was printed |
| `1` | lexical error, or the input file does not exist |
| `2` | wrong command line usage (not exactly one argument) |

---

## 9. How to build and run

The Gradle wrapper is committed, so **no local Gradle installation is needed** —
only a JDK 21 or newer on the `PATH`.

Since phase 2 the default mode prints the syntax tree (section 18). The token
table of this phase is behind the `--tokens` flag:

### Linux / macOS

```bash
./gradlew build                                           # compile + run tests
./gradlew run --args="--tokens examples/03_tricky.o"      # tokenize a file
./gradlew run --args="--tokens examples/01_minimal.o"
./gradlew run --args="--tokens examples/04_error.o"       # error demo, exits 1
```

### Windows

```bat
gradlew.bat build
gradlew.bat run --args="--tokens examples\03_tricky.o"
gradlew.bat run --args="--tokens examples\01_minimal.o"
gradlew.bat run --args="--tokens examples\04_error.o"
```

(Forward slashes also work on Windows: `--args="--tokens examples/03_tricky.o"`.)

### Standalone executable (cleaner output)

`./gradlew run` on a failing input appends Gradle's own "BUILD FAILED" banner
after the error report, because the program exits with code 1. To see only the
compiler's own output, build the start scripts once:

```bash
./gradlew installDist

build/install/o-compiler/bin/o-compiler --tokens examples/04_error.o          # Linux/macOS
build\install\o-compiler\bin\o-compiler.bat --tokens examples\04_error.o      # Windows
```

### Expected output

```
$ ./gradlew run --args="--tokens examples/01_minimal.o"

====================================================================
  O LANGUAGE LEXER
  source: examples/01_minimal.o
====================================================================

  TYPE        | LEXEME  | LINE:COLUMN
  ------------+---------+------------
  CLASS       | class   | 6:1
  IDENTIFIER  | Main    | 6:7
  IS          | is      | 6:12
  THIS        | this    | 7:5
  IS          | is      | 7:10
  END         | end     | 8:5
  END         | end     | 9:1
  EOF         | <eof>   | 10:1
  ------------+---------+------------

  8 tokens
```

---

## 10. Testing

```bash
./gradlew test                       # Linux / macOS
gradlew.bat test                     # Windows
```

An HTML report is written to `build/reports/tests/test/index.html`.

`src/test/kotlin/o/LexerTest.kt` contains 51 tests grouped by concern:

| Group | What is asserted |
|---|---|
| `:` vs `:=` | both with and without surrounding spaces; `:` at end of input; a declaration and an assignment on adjacent lines |
| `=` and `=>` | `=>` is one `ARROW`; a bare `=`, a trailing `=`, and `=<` are all lexical errors with the right position |
| numbers | `1.5` is one `REAL_LITERAL`; `1.Plus(2)` is `INT_LITERAL DOT IDENTIFIER …`; `1.` is `INT_LITERAL DOT`; `1.2.3` absorbs only the first dot; `1.5.Plus(2.5)` |
| keywords vs identifiers | all 13 keywords; `isEven`, `classroom`, `endless`, `variable`, `thistle`, `ifs`, `loopCount` stay identifiers; case sensitivity (`Class`, `END`); `true`/`false` are `BOOL_LITERAL` but `truest` is not |
| comments | `//` to end of line is dropped; a comment containing `@ # $ := => 1.5 class` produces no tokens; code resumes on the next line with correct positions; a comment at EOF without a newline; a lone `/` is an error |
| block comments | `/* … */` is dropped inline and across lines, with the line counter still advancing; blocks do not nest; `/*` inside a `//` comment is plain text; an unterminated block reports the **opening** position; a stray `*/` is an error |
| positions | columns are 1-based and point at the first character; lines advance across `\n`; indentation and blank lines do not shift columns; the error position is exact |
| EOF | present for empty input, whitespace-only input, a comment-only file; exactly one `EOF`; an empty file lexes to `[EOF at 1:1]` |
| illegal characters | `@` throws with message and position; 15 further characters (`# $ ? ! % & + - * ; { } < > "`) are each rejected |
| specification snippets | the twelve code fragments of section 3 of the spec all tokenize, including the ones that do not parse; `0.Minus(1)` needs no special case; `-5` is an error, since negation is `UnaryMinus`; `Integer`, `Real`, `Boolean`, `Array`, `List`, `Class`, `AnyValue`, `AnyRef` are identifiers, not keywords |
| fixtures | every `tests/lexer_*.o` lexes to a stream ending in `EOF`; every `tests/error_*.o` throws; every `examples/*.o` except `04_error.o` lexes (since phase 2 this includes the syntax error demo `13_parse_error.o`, which must lex cleanly) |

The last group walks the directories, so dropping a new `.o` file into `tests/`
adds a test case without touching any Kotlin.

---

## 11. Next steps

| Phase | Deliverable | Notes |
|---|---|---|
| **2. Parser** | recursive-descent parser over this token stream | **Done** — sections 12–19. |
| **3. AST** | a typed node hierarchy (Kotlin sealed classes) | **Done** together with the parser — section 15. |
| **4. Semantic analysis** | symbol tables, scope resolution, type checking, overload resolution | Checks the rules the grammar cannot express: undeclared names, argument/parameter mismatch, return-type conformance, valid overriding. |
| **5. Code generation** | JVM bytecode via **Jasmin** | Each O class becomes one `.j` file assembled into a `.class`; method-call arithmetic (`a.Plus(b)`) maps onto `invokevirtual` against a small runtime library of `Integer`, `Real`, `Boolean`, `Array`. |
| **6. Optimisation** *(if in scope)* | constant folding, dead-code elimination | Operates on the AST before emission. |

Nothing in this phase anticipates those stages beyond one decision: tokens carry
their source positions. That is the single piece of information which is
impossible to reconstruct later and which every subsequent phase needs.

---
---

# Phase 2 — Syntax Analyzer

## 12. Overview

```
  Program.o
      |
      v
  +--------------------+   List<Token>   +--------------------+   Program (AST)
  |  LEXICAL ANALYSIS  | --------------> |  SYNTAX ANALYSIS   | ---------------+
  |     (phase 1)      | CLASS, IDENT... |  (this phase)      |                |
  +--------------------+                 +--------------------+                |
      |                                      |                                 v
      v                                      v                        SEMANTIC ANALYSIS
  LexerException                       ParseException                          |
  (illegal character)                  (token out of place)                    v
                                                                  CODE GENERATION (Jasmin)
                                                                               |
                                                                               v
                                                                           .class file
```

The **parser** (syntax analyzer) takes the flat token list produced by the
lexer and checks that it follows the grammar of O. While it checks, it builds
an **abstract syntax tree** (AST): a tree of Kotlin objects — `ClassDecl`,
`MethodDecl`, `While`, `Call`, … — that holds the structure of the program
without its punctuation. `is`, `end`, `:`, `(` and `,` have done their job once
the tree exists, and no later phase sees them again.

```kotlin
val tokens  = Lexer(source).tokenize()       // phase 1
val program = Parser(tokens).parseProgram()  // phase 2: Program, or ParseException
```

What the parser deliberately does **not** do is give names a meaning. In

```
var x : Foo(1)
```

it knows that `Foo(1)` is a name applied to one argument, but not whether `Foo`
is a class (a constructor call) or a method of the current object, nor whether
the class exists, nor whether `1` is an acceptable argument. Those questions
need the declarations of the whole program and belong to semantic analysis
(phase 3). Section 15.3 explains why that boundary is drawn exactly there.

---

## 13. Why a hand-written recursive-descent parser

The reasons that ruled out a lexer generator in phase 1 (section 2) apply
again, and more strongly. A parser generator (ANTLR, CUP, Bison) would have to
be fed the same rewritten grammar shown below — it does not remove the left
recursion problem for an LL tool, and for an LR tool it brings shift/reduce
conflicts to debug instead. In exchange it would take away the two things
this phase is graded on: **error messages** written in terms of O
("expected 'then' after the condition of 'if' at 13:13") rather than in terms
of parser states ("no viable alternative at input"), and **code that can be
explained line by line**.

Recursive descent is the most direct translation of a grammar into code: one
function per rule, and the body of each function reads like the rule it
implements.

```
WhileLoop : while Expression loop Body end
```

```kotlin
private fun parseWhile(): While {
    val whileToken = advance()                    // while
    val condition = parseExpression()             // Expression
    expect(LOOP, "'loop' after ...")              // loop
    val body = parseBody()                        // Body
    expectEnd(whileToken, "'while'")              // end
    return While(condition, body, whileToken.line, whileToken.column)
}
```

The whole parser is under 500 lines including comments, with no dependency and no
generated code.

The only prerequisite is that the grammar be **LL(1)** — at every decision
point the next token must determine which rule applies. The grammar in the
specification is not, so section 14 rewrites it until it is (with a single,
explicit two-token exception).

---

## 14. The grammar

### 14.1 The problem: left recursion and ambiguity

The expression part of the specification reads:

```
Expression            : Primary | ConstructorInvokation | FunctionCall
                      | Expression { . Expression }
ConstructorInvokation : ClassName [ Arguments ]
FunctionCall          : Expression [ Arguments ]
```

Two rules — `Expression : Expression { . Expression }` and
`FunctionCall : Expression [ Arguments ]` — are **left-recursive**: the
non-terminal being defined is the first symbol of its own right-hand side.

A recursive-descent parser cannot execute such a rule. Translated literally,

```kotlin
fun parseExpression(): Expr {
    val left = parseExpression()   // the first thing an Expression contains
    ...                            // is an Expression
}
```

calls itself before it has consumed a single token. Nothing changes between
the outer and the inner call, so the recursion never ends and the parser dies
with a `StackOverflowError` on the first expression of the first program.

The same rules are also **ambiguous**:

* `a.b.c` can be derived as `(a.b).c` or as `a.(b.c)`.
* `FunctionCall : Expression [ Arguments ]` with the arguments optional means
  every expression is also a function call of itself, which is a function call
  of itself, … — infinitely many derivations of `x`.
* `Expression . Expression` allows `a.1` or `a.this`, which mean nothing.

### 14.2 The rewrite

The standard transformation replaces a left-recursive rule
`A : A α | β` — "a β followed by any number of α" — with the iteration
`A : β { α }`, which accepts the same strings without recursing on the left.
Applied here, and with the right-hand side of a dot narrowed to the only
thing that can meaningfully stand there, a member name:

```
Expression : Primary { . Identifier [ Arguments ] }
Primary    : IntegerLiteral | RealLiteral | BooleanLiteral | this
           | ClassName [ Arguments ]
```

`ConstructorInvokation` is folded into `Primary`, and `FunctionCall` is gone:
a call is now "a name followed by an argument list", either at the start of
the chain (`Integer(0)`, `get(i)`) or after a dot (`a.get(i)`).

In code the `{ … }` becomes a `while` loop, and the tree is built as the loop
goes, so a chain comes out **left-associative** — `a.get(i).Greater(max)` is
`(a.get(i)).Greater(max)`, which is the only reading that makes sense:

```kotlin
private fun parseExpression(): Expr {
    var expr = parsePrimary()
    while (match(DOT)) {
        val name = expect(IDENTIFIER, "a method or field name after '.'")
        expr = MemberAccess(expr, name.lexeme, name.line, name.column)
        if (check(LPAREN)) {
            expr = Call(expr, parseArguments(), name.line, name.column)
        }
    }
    return expr
}
```

### 14.3 The grammar as implemented

```
Program         : { ClassDecl } EOF
ClassDecl       : class Identifier [ extends Identifier ] is { Member } end
Member          : VarDecl | MethodDecl | ConstructorDecl
VarDecl         : var Identifier : Expression
MethodDecl      : method Identifier [ Parameters ] [ : TypeName ] [ MethodBody ]
MethodBody      : is Body end | => Expression
ConstructorDecl : this [ Parameters ] is Body end
Parameters      : ( [ Param { , Param } ] )
Param           : Identifier : TypeName
TypeName        : Identifier [ '[' TypeName { , TypeName } ']' ]
Body            : { VarDecl | Statement }
Statement       : Assignment | WhileLoop | IfStatement | Return | ExprStatement
Assignment      : Identifier := Expression
WhileLoop       : while Expression loop Body end
IfStatement     : if Expression then Body [ else Body ] end
Return          : return [ Expression ]
ExprStatement   : Expression                                  -- EXTENSION
Expression      : Primary { . Identifier [ Arguments ] }
Primary         : IntegerLiteral | RealLiteral | BooleanLiteral | this
                | TypeName [ Arguments ]
Arguments       : ( [ Expression { , Expression } ] )
```

Here `'[' ']'` in quotes are the O tokens `LBRACKET` / `RBRACKET`; unquoted
`[ ]` and `{ }` are the metalanguage, as in the specification. Every
difference from the specification's grammar is listed in section 16.

### 14.4 Lookahead: how every decision is made

Each parsing function decides what to do by looking at the next token, and
nothing more, with one exception:

| Decision | Looked at |
|---|---|
| another class, or the end of the program | `class` / `EOF` |
| which member | `var` / `method` / `this` |
| does the method have parameters, a return type | `(` / `:` |
| which method body, if any | `is` → block, `=>` → expression, anything else → forward declaration |
| does the body go on | the next token can start a body item (FIRST set below) |
| which statement | `var`, `while`, `if`, `return` — or see below |
| **assignment or expression statement** | **`IDENTIFIER` followed by `:=`** — two tokens |
| does `return` have a value | the next token is in FIRST(Expression) |
| does the call chain go on | `.` |
| is a name called | `(` |
| does a type name have type arguments | `[` |

```
FIRST(Expression) = { INT_LITERAL, REAL_LITERAL, BOOL_LITERAL, this, IDENTIFIER }
FIRST(body item)  = FIRST(Expression) ∪ { var, while, if, return }
```

**The two-token case.** Both `x := 1` and `x.foo()` begin with an
`IDENTIFIER`, so the first token cannot choose between `Assignment` and
`ExprStatement`. The token after it can: only an assignment has `:=` in second
position.

```kotlin
check(IDENTIFIER) && peekNext().type == ASSIGN -> parseAssignment()
else -> parseExpressionStatement()
```

(The alternative — parse an expression first and, on seeing `:=`, check that
it was a bare name — keeps the grammar LL(1) but hides the rule inside an
`if`. Looking two tokens ahead states the rule `Identifier := Expression` as it
is written.)

**How `return` decides.** `return [ Expression ]` has an optional part and O
has no statement terminator, so the only evidence is the next token: if it
can start an expression, the expression belongs to the `return`. In practice
a value-less `return` stands before `end` or `else`, neither of which can
start an expression, so the rule does the right thing. The one input it reads
differently from what a human might mean is

```
return
x := 1
```

which is taken as `return x` followed by a stray `:=`. Code after a `return`
in the same block is unreachable anyway, so we document this rather than add
a line-sensitive rule to an otherwise layout-free grammar.

**How a body ends.** There are no braces and no separators, so `parseBody`
simply keeps going while the next token is in FIRST(body item). Whatever
stops it is checked by the caller, which knows which block it is closing:
`parseWhile` expects `end`, `parseIf` expects `else` or `end`. This is what
lets the error message name the open block (section 17).

---

## 15. The abstract syntax tree

### 15.1 Shape

The tree is a Kotlin sealed class hierarchy in `src/main/kotlin/o/Ast.kt`.
Sealed classes make every `when` over node types exhaustive: when semantic
analysis forgets a case, it does not compile.

```
Node                                  every node has  line, column
├── Program        (classes)
├── ClassDecl      (name, baseName?, members)
├── MethodDecl     (name, params, returnType?, body?)       : Member
├── ConstructorDecl(params, body)                           : Member
├── Param          (name, type)
├── TypeRef        (name, typeArgs)
├── Statement
│   ├── VarDecl       (name, init)                          : Member too
│   ├── Assignment    (name, value)
│   ├── While         (condition, body)
│   ├── If            (condition, thenBody, elseBody?)
│   ├── Return        (value?)
│   └── ExprStatement (expr)
└── Expr
    ├── IntLit (value: Int)   RealLit (value: Double)   BoolLit (value: Boolean)
    ├── This
    ├── Name         (type: TypeRef)
    ├── MemberAccess (target, name)
    └── Call         (callee, args)

MethodBody = Block(statements) | Arrow(expr)     -- not a Node; null = forward declaration
```

| Node | O source | Notes |
|---|---|---|
| `Program` | the whole file | only classes |
| `ClassDecl` | `class B extends A is … end` | `baseName` is a string: `extends` takes a plain identifier |
| `VarDecl` | `var x : Integer(0)` | a field when it is a member, a local when it is in a body |
| `MethodDecl` | `method f(a: Integer) : Integer …` | `body == null` is a forward declaration |
| `MethodBody.Block` / `.Arrow` | `is … end` / `=> e` | kept apart so the tree mirrors the source; semantically `=> e` is `is return e end` |
| `ConstructorDecl` | `this(a: Integer) is … end` | |
| `Param`, `TypeRef` | `a: Array[Integer]` | `TypeRef` is recursive: `List[Array[Real]]` |
| `Assignment` | `x := e` | target is a name, never an expression |
| `While`, `If`, `Return` | | `elseBody` is `null` without `else`, empty for `else end` |
| `ExprStatement` | `a.set(i, v)` | extension, see section 16 |
| `IntLit`, `RealLit`, `BoolLit`, `This` | `42`, `1.5`, `true`, `this` | values already converted from the lexeme |
| `Name` | `x`, `Integer`, `Array[Integer]` | neutral: variable or class, see 15.3 |
| `MemberAccess` | `a.Length` | a dot with no argument list |
| `Call` | `a.get(1)`, `Integer(0)` | a callee followed by an argument list |

Three choices in this shape are worth spelling out:

* **`VarDecl` is a `Statement`.** The specification writes
  `Body : { VariableDeclaration | Statement }`, so strictly a declaration is
  not a statement. Making it one lets a body be a single list in source order,
  and semantic analysis needs that order: a local is in scope only *after*
  its declaration. `VarDecl` is also a `Member`, so the same class serves for
  fields; its position in the tree says which it is.
* **Positions.** Every node carries the `line:column` of its first token —
  except `MemberAccess`, which carries the position of its **member name**,
  and `Call`, which carries the position of its callee. In
  `a.get(i).Greater(max)` the first token of every node is `a`, so an error
  such as "Integer has no method `Greater`" would otherwise point at `a`.
* **Literals hold values, not lexemes.** `IntLit` holds an `Int`. A literal
  too large for 32 bits (`99999999999`) is therefore rejected by the parser,
  with its position, instead of crashing code generation later.

### 15.2 Examples

These are produced by the CLI (section 18); each row shows `line:column`.

**A call on an object is a `Call` of a `MemberAccess`** — `a.get(1)`, and a
chain is nested from the inside out:

```
a.get(i).Greater(max)

Call                                3:81
├─ callee: MemberAccess .Greater    3:81
│  └─ Call                          3:74
│     ├─ callee: MemberAccess .get  3:74
│     │  └─ Name a                  3:72
│     └─ arg: Name i                3:78
└─ arg: Name max                    3:89
```

**A member without parentheses is a `MemberAccess` alone** — the parser does
not decide whether `toReal` is a field or a method called without arguments:

```
x.toReal

MemberAccess .toReal                5:26
└─ Name x                           5:24
```

**A method on a literal** — the case the lexer's dot rule (section 7(c)) exists for:

```
1.Plus(2)

Call                                4:29
├─ callee: MemberAccess .Plus       4:29
│  └─ IntLit 1                      4:27
└─ arg: IntLit 2                    4:34
```

**A generic construction** — the type arguments belong to the name:

```
Array[Integer](10)

Call                                2:19
├─ callee: Name Array[Integer]      2:19
└─ arg: IntLit 10                   2:34
```

**A call without a receiver** is the same shape as a construction:

```
x := get(i)

Assign x :=                         9:9
└─ Call                             9:14
   ├─ callee: Name get              9:14
   └─ arg: Name i                   9:18
```

**An expression statement, a forward declaration and an `=>` body:**

```
method later(n: Integer) : Boolean
method g : Integer => 1.Plus(2)
a.set(i, v)

Method later(n: Integer) : Boolean  (forward declaration)   6:5
Method g : Integer                                          4:5
└─ => Call                                                  4:29
   ...
ExprStatement                                               8:9
└─ Call                                                     8:11
   ├─ callee: MemberAccess .set                             8:11
   │  └─ Name a                                             8:9
   ├─ arg: Name i                                           8:15
   └─ arg: Name v                                           8:18
```

### 15.3 Why constructor call versus method call is left to semantic analysis

`Integer(0)` and `get(i)` are the same token sequence —
`IDENTIFIER LPAREN … RPAREN`. One constructs an object, the other calls a
method of `this`. Likewise, a bare `x` and the `Integer` in `Integer.Min` are
both a lone `IDENTIFIER`; one is a variable, the other a class.

Nothing in the syntax separates them. O has no `new` keyword, and nothing
says class names are capitalised (`Penguin` and `isEven` are both just
identifiers). The only way to tell is to know **which classes exist**, and
that is not something the parser can know at that point:

* a class may be used before its declaration (`Zoo` could come first in
  `08_polymorphism.o`);
* library classes (`Integer`, `Array`, …) are not declared in the file at all;
* a method and a class may share a name.

A parser that guessed — say, "capitalised means class" — would build a wrong
tree for legal programs and would encode a semantic rule in the grammar. So
the parser builds one neutral node (`Name`, `Call(Name, …)`) and semantic
analysis, which has the symbol table, decides what each one is.

---

## 16. Deviations from the specification

Each of these is also recorded, with the numbered list of open questions, in
[`examples/README.md`](examples/README.md) under "Conventions chosen".

| # | Specification | This parser | Why |
|---|---|---|---|
| 1 | `Expression : … \| Expression { . Expression }`, `FunctionCall : Expression [ Arguments ]` | `Expression : Primary { . Identifier [ Arguments ] }` | Left-recursive and ambiguous; section 14. Accepts every expression the specification actually writes. (Convention 7.) |
| 2 | `Statement` has no expression statement | **EXTENSION:** `ExprStatement : Expression` | Otherwise a call whose result is not used — `a.set(i, v)`, written by the specification itself and needed by `06_arrays.o` — cannot appear anywhere. Semantic analysis may later restrict it to calls. (Convention 8.) |
| 3 | Grammar: `var x : E`; section 3 examples: `var x is E` | `:` only | We follow the grammar. `var x is …` gets its own message naming the conflict. (Convention 1.) |
| 4 | `Assignment : Identifier := Expression`; section 3 writes `a.set(i) := 55` | bare identifier only, as in the grammar | `a.set(i) := 55` gets a specific message; so does `this.x := 1`. (Convention 9.) |
| 5 | `ClassName : Identifier // [ [ ClassName ] ]` (generics commented out), yet `Array[Integer]` and `class C[T]` in examples | type arguments accepted where a class is *used*; `class C[T]` rejected | Arrays are unusable without `Array[Integer]`; generic *declarations* are explicitly out of the course scope, and the error says so. (Convention 10.) |
| 6 | `Program : { ClassDeclaration }`, yet `MaxInt` is declared at file level | classes only | We follow the grammar; a top-level `method` gets a specific message. (Convention 11.) |
| 7 | `Parameters : ( Param { , Param } )` needs one parameter, yet the library has `Boolean.toInteger()` | `()` accepted | The library's own declaration must be expressible. Empty *argument* lists were already allowed. (Convention 12.) |
| 8 | `ConstructorInvokation` and `FunctionCall` are separate rules | one neutral `Call` node | The syntax cannot tell them apart; section 15.3. (Convention 13.) |

The probe file `examples/11_probe_spec_examples.o` collects the
specification's own snippets that this grammar rejects. It must fail to parse,
and a test checks that it does.

---

## 17. Error reporting

### 17.1 The exception

```kotlin
class ParseException(message: String, val line: Int, val column: Int) : Exception(message)
```

The same shape as `LexerException`, so the CLI prints both with one function
(`printSourceError` in `Main.kt`): the location, the source line, a caret, the
message. The position is always the token at which the parser got stuck.

The parser **stops at the first error**. Recovering — skipping to a
synchronising token such as `end` or `method` and continuing, so that one run
reports several errors (*panic-mode recovery*) — is future work. It is less
simple in O than it looks: because blocks are closed by a bare `end` (17.3),
the parser often cannot tell which block a given `end` was meant to close, and
a naive recovery would produce a cascade of follow-up errors.

### 17.2 Messages

Every message says what was expected and what was found. The found token is
written as `TYPE 'lexeme'`, or `end of file`.

| Situation | Message |
|---|---|
| a required token is missing | `expected 'loop' after the condition of 'while' at 12:9, found IDENTIFIER 'x'` |
| a block is not closed | `expected 'end' to close 'while' opened at 12:5, found ELSE 'else'` |
| a list is not closed | `expected ',' or ')' to close the argument list opened at 2:20, found END 'end'` |
| no expression where one is needed | `expected an expression, found END 'end'` |
| something else in a class body | `expected a member ('var', 'method' or 'this') or 'end' to close class 'A' opened at 1:1, found IDENTIFIER 'x'` |

For an unclosed block, the message points back at the **opener**: the error is
detected where the `end` should have been, but the line the programmer needs
to look at is the one that opened the block.

The deviations of section 16 get messages of their own, because a programmer
who copied from the specification deserves to be told why:

```
    3 |     var i is 1
      |           ^

  variable declarations use ':', not 'is': write 'var i : ...' (the grammar is
  'var Identifier : Expression'; the 'is' form only appears in the examples of
  the specification)
```

```
    4 |         a.set(1) := 55
      |         ^

  cannot assign to this expression: the left side of ':=' must be a plain
  variable name (the grammar is 'Identifier := Expression')
```

and likewise for `class C[T]` ("… are out of scope …") and a top-level
`method` ("a method must be declared inside a class …").

### 17.3 Demo: `examples/13_parse_error.o`

The file lexes cleanly — every character is legal — but its `if` has no
`then`:

```
13 |            if a.get(i).Greater(max)
14 |                max := a.get(i)
```

```
$ ./gradlew run --args="examples/13_parse_error.o"

====================================================================
  O LANGUAGE PARSER
  source: examples/13_parse_error.o
====================================================================

  SYNTAX ERROR  examples/13_parse_error.o:14:17

    14 |                 max := a.get(i)
       |                 ^

  expected 'then' after the condition of 'if' at 13:13, found IDENTIFIER 'max'
```

process exit code `1`.

**Why the demo is a missing `then` and not a missing `end`.** O has no layout
rule, so a missing `end` is usually not detected where it is missing. In

```
class A is
    method f(x: Integer) : Integer is
        if x.Less(0) then
            return 0
        return x            // the 'if' should have ended above this line
    end
end
```

the `end` on line 6 closes the `if`, the `end` on line 7 closes the method, and
the error only surfaces at the end of the file:

```
  expected a member ('var', 'method' or 'this') or 'end' to close class 'A'
  opened at 1:1, found end of file
```

The message is correct — the class really is unclosed — but it names the
wrong block from the programmer's point of view. Every language that closes
blocks with one shared keyword has this property; a parser can only
guess better by looking at indentation, which O does not define. A test
(`missingEndOfIfIsAbsorbedByTheEnclosingBlocks`) pins down this behaviour.

### 17.4 Exit codes

| Code | Meaning |
|---|---|
| `0` | the file was parsed; the tree (or, with `--tokens`, the token table) was printed |
| `1` | lexical error, syntax error, or the input file does not exist |
| `2` | wrong command line usage |

---

## 18. How to run

```bash
./gradlew run --args="examples/08_polymorphism.o"          # print the syntax tree
./gradlew run --args="examples/13_parse_error.o"           # syntax error demo, exits 1
./gradlew run --args="--tokens examples/03_tricky.o"       # phase 1 token table
```

Windows: `gradlew.bat run --args="examples\08_polymorphism.o"`. As in phase 1,
`./gradlew installDist` builds `build/install/o-compiler/bin/o-compiler`, which
prints the same output without Gradle's banner on a failing input.

The tree is drawn with `├─ └─ │` connectors like the `tree` command, with the
position of every node in the right-hand column. Rows such as `then`, `else`
and `loop` group a block's statements and have no position of their own;
`cond:`, `callee:` and `arg:` say what a child is to its parent.

```
$ ./gradlew run --args="examples/08_polymorphism.o"

====================================================================
  O LANGUAGE PARSER
  source: examples/08_polymorphism.o
====================================================================

  Program                              6:1
  ├─ Class Animal                      6:1
  │  ├─ Method legs : Integer          7:5
  │  │  └─ => IntLit 4                 7:30
  │  ├─ Method noise : Integer         8:5
  │  │  └─ => IntLit 0                 8:31
  │  └─ Constructor                    9:5
  ├─ Class Bird extends Animal         13:1
  │  ├─ Method legs : Integer          14:5
  │  │  └─ => IntLit 2                 14:30
  │  └─ Constructor                    15:5
  ├─ Class Penguin extends Bird        19:1
  │  ├─ Method noise : Integer         20:5
  │  │  └─ => IntLit 1                 20:31
  │  └─ Constructor                    21:5
  └─ Class Zoo                         25:1
     └─ Constructor                    26:5
        ├─ Var a                       28:9
        │  └─ Call                     28:17
        │     └─ callee: Name Animal   28:17
        ├─ Assign a :=                 29:9
        │  └─ Call                     29:14
        │     └─ callee: Name Penguin  29:14
        ├─ Var l                       32:9
        │  └─ MemberAccess .legs       32:19
        │     └─ Name a                32:17
        └─ Var n                       35:9
           └─ MemberAccess .noise      35:19
              └─ Name a                35:17

  4 classes
```

The tree printer (`AstPrinter.kt`) works in two steps: it first turns the AST
into a generic tree of labelled rows (the only code that knows node types),
then draws the connectors for any such tree. A new node type needs one new
branch in a `when`; the drawing code never changes.

---

## 19. Testing

```bash
./gradlew test
```

104 tests in total: the 51 lexer tests of phase 1, all still passing, and 53
in `src/test/kotlin/o/ParserTest.kt`:

| Group | What is asserted |
|---|---|
| program | empty file and comment-only file give no classes; several classes keep their order; with and without `extends` |
| members | all three member kinds; forward declaration (also as the last member); `=>` body; block body; parameters without return type; empty `()` in parameters and arguments; constructors with and without parameters |
| types | `Array[Integer]` as a parameter type; `Array[Integer](10)` is `Call(Name Array[Integer], [10])`; nested `List[Array[Real]]` |
| expressions | all literals and `this`; a bare name; `a.Length` is a `MemberAccess`; `a.get(1)` is a `Call` of a `MemberAccess`; `a.get(i).Greater(max)` is left-associative; `1.Plus(2)` and `1.5.Plus(2.5)`; nested arguments |
| statements | assignment versus expression statement (the two-token decision); `get(i)` as a statement; `a.set(i, v)`; declarations and statements keep source order; `if` with no `else`, with `else`, with an empty `else`; nested `if`/`while`; `return` with a value, before `end`, before `else`, and the next-line case of 14.4 |
| positions | exact `line:column` of class, field, initializer, method, parameter, its type, assignment, call, member access, argument and return; a program starts at its first token |
| syntax errors | each asserts the exception, its exact position and its message: unclosed `while` points back at its opener; unclosed class at end of file; the absorbed missing `end` of 17.3; `var x is 1`; `class C[T]`; top-level `method` and `var`; `a.set(i) := 55`; missing `)` in arguments and in parameters; missing `then`; missing `loop`; missing expression; statement directly in a class; empty `[]`; integer literal too large; only the first of two errors is reported |
| fixtures | every `tests/parser_*.o` parses; every `tests/parse_error_*.o` raises `ParseException` |
| examples | every `examples/*.o` parses, except `04_error.o` (must raise `LexerException`) and `11_probe_spec_examples.o` and `13_parse_error.o` (must raise `ParseException`) |

No example needed to change for the parser: every program in 01–10 and 12
parses as written.

As in phase 1, the fixture tests walk the `tests/` directory, so adding a
`parser_*.o` or `parse_error_*.o` file adds a test case without touching any
Kotlin. See `tests/README.md`.
