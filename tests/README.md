# Lexer fixtures

Small `.o` inputs used by `src/test/kotlin/o/LexerTest.kt`.

* `lexer_*.o` must tokenize without an error.
* `error_*.o` must raise a `LexerException`.

`LexerTest.allFixturesBehaveAsTheirNameSays` walks this directory, so adding a
file here adds a test case; no Kotlin change is needed.
