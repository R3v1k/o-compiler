# Test fixtures

Small `.o` inputs used by the unit tests. The file name prefix says what the
file must do:

| Prefix | Used by | Must |
|---|---|---|
| `lexer_*.o` | `LexerTest` | tokenize without an error |
| `error_*.o` | `LexerTest` | raise a `LexerException` |
| `parser_*.o` | `ParserTest` | lex **and** parse without an error |
| `parse_error_*.o` | `ParserTest` | lex cleanly, then raise a `ParseException` |

`LexerTest.allFixturesBehaveAsTheirNameSays` treats only `error_*` as failing,
so it checks that the `parser_*` and `parse_error_*` files lex as well; a
syntax error fixture must contain a *syntax* error, not a lexical one.
`ParserTest.allParserFixturesBehaveAsTheirNameSays` looks only at the two
`parse*` prefixes.

Both tests walk this directory, so adding a file here adds a test case; no
Kotlin change is needed.
