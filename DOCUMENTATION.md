# Project O — Phase 1: Lexical Analyzer

Compiler Construction course, Innopolis University.
Language **O**, implementation in Kotlin on the JVM.

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
├── DOCUMENTATION.md              This document
├── docs/
│   └── Project-O-specification.pdf   The course hand-out this phase
│                                 implements; section 5 below maps its grammar
│                                 onto the token set
│
├── src/main/kotlin/o/
│   ├── Token.kt                  TokenType enum, Token data class,
│   │                             LexerException
│   ├── Lexer.kt                  The scanner: Lexer(source).tokenize()
│   └── Main.kt                   CLI: token table, error report, exit codes
│
├── src/test/kotlin/o/
│   └── LexerTest.kt              51 unit tests (kotlin.test)
│
├── examples/                     O programs, at the project root so that
│   │                             ./gradlew run --args="examples/..." works
│   ├── 01_minimal.o              Smallest valid program
│   ├── 02_arithmetic.o           Method-call arithmetic, '=>' bodies
│   ├── 03_tricky.o               All lexer edge cases in one file  <-- demo
│   ├── 04_error.o                Contains '@' — error demo         <-- demo
│   ├── 03_declarations.o … 12_*.o   Wider suite for the later phases
│   └── README.md                 What each example covers
│
└── tests/                        Small .o fixtures consumed by LexerTest:
    ├── lexer_*.o                 must tokenize cleanly (':' vs ':=', numbers,
    │                             keywords, line comments, block comments,
    │                             a file with no trailing newline)
    ├── error_*.o                 must raise LexerException (illegal character,
    │                             bare '=', unterminated block comment)
    └── README.md
```

`examples/` and `tests/` live at the project root, next to `build.gradle.kts`,
so every path in this document is relative to the root and the demo command is
literally `./gradlew run --args="examples/03_tricky.o"`.

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

### Linux / macOS

```bash
./gradlew build                                  # compile + run tests
./gradlew run --args="examples/03_tricky.o"      # tokenize a file
./gradlew run --args="examples/01_minimal.o"
./gradlew run --args="examples/04_error.o"       # error demo, exits 1
```

### Windows

```bat
gradlew.bat build
gradlew.bat run --args="examples\03_tricky.o"
gradlew.bat run --args="examples\01_minimal.o"
gradlew.bat run --args="examples\04_error.o"
```

(Forward slashes also work on Windows: `--args="examples/03_tricky.o"`.)

### Standalone executable (cleaner output)

`./gradlew run` on a failing input appends Gradle's own "BUILD FAILED" banner
after the error report, because the program exits with code 1. To see only the
compiler's own output, build the start scripts once:

```bash
./gradlew installDist

build/install/o-compiler/bin/o-compiler examples/04_error.o          # Linux/macOS
build\install\o-compiler\bin\o-compiler.bat examples\04_error.o      # Windows
```

### Expected output

```
$ ./gradlew run --args="examples/01_minimal.o"

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
| fixtures | every `tests/lexer_*.o` lexes to a stream ending in `EOF`; every `tests/error_*.o` throws; every `examples/*.o` except the error one lexes |

The last group walks the directories, so dropping a new `.o` file into `tests/`
adds a test case without touching any Kotlin.

---

## 11. Next steps

| Phase | Deliverable | Notes |
|---|---|---|
| **2. Parser** | recursive-descent parser over this token stream | O's grammar is LL(1)-friendly once the ambiguity in `Expression` is rewritten as `Primary { '.' Identifier [ Arguments ] }` — see the open questions in `examples/README.md`. The `EOF` token and the exact positions produced here are what the parser's error messages will build on. |
| **3. AST** | a typed node hierarchy (Kotlin sealed classes) | Every node keeps the `line`/`column` of the token it started at, propagated straight from `Token`. |
| **4. Semantic analysis** | symbol tables, scope resolution, type checking, overload resolution | Checks the rules the grammar cannot express: undeclared names, argument/parameter mismatch, return-type conformance, valid overriding. |
| **5. Code generation** | JVM bytecode via **Jasmin** | Each O class becomes one `.j` file assembled into a `.class`; method-call arithmetic (`a.Plus(b)`) maps onto `invokevirtual` against a small runtime library of `Integer`, `Real`, `Boolean`, `Array`. |
| **6. Optimisation** *(if in scope)* | constant folding, dead-code elimination | Operates on the AST before emission. |

Nothing in this phase anticipates those stages beyond one decision: tokens carry
their source positions. That is the single piece of information which is
impossible to reconstruct later and which every subsequent phase needs.
