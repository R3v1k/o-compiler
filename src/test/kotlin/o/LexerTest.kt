package o

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LexerTest {

    private fun lex(source: String): List<Token> = Lexer(source).tokenize()

    /** Token types without the trailing EOF, for compact expectations. */
    private fun types(source: String): List<TokenType> =
        lex(source).dropLast(1).map { it.type }

    /** Lexemes without the trailing EOF. */
    private fun lexemes(source: String): List<String> =
        lex(source).dropLast(1).map { it.lexeme }

    // ------------------------------------------------- (a) ':' versus ':='

    @Test
    fun colonIsNotAssign() {
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.COLON, TokenType.IDENTIFIER),
            types("x : Integer")
        )
    }

    @Test
    fun assignIsOneToken() {
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.ASSIGN, TokenType.INT_LITERAL),
            types("x := 5")
        )
        assertEquals(listOf("x", ":=", "5"), lexemes("x := 5"))
    }

    @Test
    fun colonAndAssignAreDistinguishedWithoutSurroundingSpaces() {
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.ASSIGN, TokenType.INT_LITERAL),
            types("x:=5")
        )
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.COLON, TokenType.INT_LITERAL),
            types("x:5")
        )
    }

    @Test
    fun colonFollowedByEndOfInputIsStillAColon() {
        assertEquals(listOf(TokenType.COLON), types(":"))
    }

    @Test
    fun declarationAndAssignmentOnAdjacentLines() {
        val tokens = lex(
            """
            var x : Integer(0)
            x := 5
            """.trimIndent()
        )
        assertEquals(TokenType.COLON, tokens[2].type)
        assertEquals(TokenType.ASSIGN, tokens[8].type)
    }

    // ------------------------------------------------- (b) '=' and the arrow

    @Test
    fun arrowIsOneToken() {
        val tokens = lex("=> a")
        assertEquals(TokenType.ARROW, tokens[0].type)
        assertEquals("=>", tokens[0].lexeme)
    }

    @Test
    fun shortMethodBodyIsTokenized() {
        assertEquals(
            listOf(
                TokenType.METHOD, TokenType.IDENTIFIER, TokenType.LPAREN,
                TokenType.IDENTIFIER, TokenType.COLON, TokenType.IDENTIFIER,
                TokenType.RPAREN, TokenType.COLON, TokenType.IDENTIFIER,
                TokenType.ARROW, TokenType.IDENTIFIER, TokenType.DOT,
                TokenType.IDENTIFIER, TokenType.LPAREN, TokenType.INT_LITERAL,
                TokenType.RPAREN
            ),
            types("method inc(a: Integer) : Integer => a.Plus(1)")
        )
    }

    @Test
    fun bareEqualsIsALexicalError() {
        val e = assertFailsWith<LexerException> { lex("a = b") }
        assertEquals(1, e.line)
        assertEquals(3, e.column)
        assertTrue("'='" in e.message!!, "message should quote the character: ${e.message}")
    }

    @Test
    fun equalsAtEndOfInputIsALexicalError() {
        assertFailsWith<LexerException> { lex("a =") }
    }

    @Test
    fun equalsFollowedBySomethingElseIsALexicalError() {
        assertFailsWith<LexerException> { lex("a =< b") }
    }

    // --------------------------------- (c) real literal versus integer + dot

    @Test
    fun realLiteralIsOneToken() {
        val tokens = lex("1.5")
        assertEquals(TokenType.REAL_LITERAL, tokens[0].type)
        assertEquals("1.5", tokens[0].lexeme)
        assertEquals(TokenType.EOF, tokens[1].type)
    }

    @Test
    fun methodCallOnIntegerLiteralIsNotAReal() {
        assertEquals(
            listOf(
                TokenType.INT_LITERAL, TokenType.DOT, TokenType.IDENTIFIER,
                TokenType.LPAREN, TokenType.INT_LITERAL, TokenType.RPAREN
            ),
            types("1.Plus(2)")
        )
        assertEquals(listOf("1", ".", "Plus", "(", "2", ")"), lexemes("1.Plus(2)"))
    }

    @Test
    fun methodCallOnRealLiteral() {
        assertEquals(
            listOf(
                TokenType.REAL_LITERAL, TokenType.DOT, TokenType.IDENTIFIER,
                TokenType.LPAREN, TokenType.REAL_LITERAL, TokenType.RPAREN
            ),
            types("1.5.Plus(2.5)")
        )
        assertEquals(listOf("1.5", ".", "Plus", "(", "2.5", ")"), lexemes("1.5.Plus(2.5)"))
    }

    @Test
    fun trailingDotIsASeparateToken() {
        assertEquals(listOf(TokenType.INT_LITERAL, TokenType.DOT), types("1."))
        assertEquals(listOf("1", "."), lexemes("1."))
    }

    @Test
    fun multiDigitAndLongRealLiterals() {
        assertEquals(listOf("100", "3.14159", "0", "0.0"), lexemes("100 3.14159 0 0.0"))
        assertEquals(
            listOf(
                TokenType.INT_LITERAL, TokenType.REAL_LITERAL,
                TokenType.INT_LITERAL, TokenType.REAL_LITERAL
            ),
            types("100 3.14159 0 0.0")
        )
    }

    @Test
    fun realLiteralHasOnlyOneDot() {
        // "1.2.3" is REAL(1.2) DOT INT(3) - the second dot is not part of a number.
        assertEquals(
            listOf(TokenType.REAL_LITERAL, TokenType.DOT, TokenType.INT_LITERAL),
            types("1.2.3")
        )
    }

    // ------------------------------------- keywords versus plain identifiers

    @Test
    fun keywordsAreRecognized() {
        assertEquals(
            listOf(
                TokenType.CLASS, TokenType.EXTENDS, TokenType.IS, TokenType.END,
                TokenType.VAR, TokenType.METHOD, TokenType.THIS, TokenType.WHILE,
                TokenType.LOOP, TokenType.IF, TokenType.THEN, TokenType.ELSE,
                TokenType.RETURN
            ),
            types("class extends is end var method this while loop if then else return")
        )
    }

    @Test
    fun identifiersStartingWithAKeywordStayIdentifiers() {
        assertEquals(
            listOf(TokenType.IDENTIFIER),
            types("isEven"),
            "'isEven' must not be split into IS + 'Even'"
        )
        for (name in listOf("isEven", "classroom", "endless", "variable", "thistle", "ifs", "loopCount")) {
            assertEquals(listOf(TokenType.IDENTIFIER), types(name), "'$name' should be an IDENTIFIER")
        }
    }

    @Test
    fun keywordsAreCaseSensitive() {
        assertEquals(listOf(TokenType.IDENTIFIER), types("Class"))
        assertEquals(listOf(TokenType.IDENTIFIER), types("END"))
    }

    @Test
    fun identifiersMayContainDigitsAndUnderscores() {
        assertEquals(listOf(TokenType.IDENTIFIER), types("_private_name2"))
        assertEquals(listOf("x1"), lexemes("x1"))
    }

    @Test
    fun booleansAreLiteralsNotKeywords() {
        assertEquals(
            listOf(TokenType.BOOL_LITERAL, TokenType.BOOL_LITERAL),
            types("true false")
        )
        assertEquals(listOf("true", "false"), lexemes("true false"))
        // ... but only as whole words
        assertEquals(listOf(TokenType.IDENTIFIER), types("truest"))
    }

    // ---------------------------------------------------------- comments

    @Test
    fun lineCommentIsSkipped() {
        assertEquals(listOf(TokenType.VAR, TokenType.IDENTIFIER), types("var x // this is dropped"))
    }

    @Test
    fun commentContentIsNeverTokenized() {
        // The comment holds characters that would otherwise be errors or tokens.
        assertEquals(emptyList(), types("// @ # $ := => 1.5 class"))
    }

    @Test
    fun codeResumesOnTheLineAfterAComment() {
        val tokens = lex("// header\nvar x")
        assertEquals(TokenType.VAR, tokens[0].type)
        assertEquals(2, tokens[0].line)
        assertEquals(1, tokens[0].column)
    }

    @Test
    fun commentAtEndOfFileWithoutNewline() {
        assertEquals(listOf(TokenType.END), types("end // done"))
    }

    @Test
    fun singleSlashIsNotAComment() {
        // '/' is not a token of O, so a lone slash is a lexical error
        // rather than the silent start of a comment.
        assertFailsWith<LexerException> { lex("a / b") }
    }

    // -------------------------------------------------------- line / column

    @Test
    fun columnsAreOneBasedAndPointAtTheFirstCharacter() {
        val tokens = lex("var x : 10")
        //               123456789...
        assertEquals(Token(TokenType.VAR, "var", 1, 1), tokens[0])
        assertEquals(Token(TokenType.IDENTIFIER, "x", 1, 5), tokens[1])
        assertEquals(Token(TokenType.COLON, ":", 1, 7), tokens[2])
        assertEquals(Token(TokenType.INT_LITERAL, "10", 1, 9), tokens[3])
    }

    @Test
    fun linesAreCountedAcrossNewlines() {
        val tokens = lex("class A is\n    var x : 1\nend")
        assertEquals(1, tokens[0].line)           // class
        assertEquals(2, tokens[3].line)           // var
        assertEquals(5, tokens[3].column)
        val end = tokens[tokens.size - 2]
        assertEquals(TokenType.END, end.type)
        assertEquals(3, end.line)
        assertEquals(1, end.column)
    }

    @Test
    fun positionAfterAMultiCharacterTokenIsCorrect() {
        val tokens = lex("x := 5")
        assertEquals(3, tokens[1].column)         // ':=' starts at 3
        assertEquals(6, tokens[2].column)         // '5' starts at 6
    }

    @Test
    fun blankLinesAndIndentationDoNotShiftPositions() {
        val tokens = lex("\n\n        this")
        assertEquals(3, tokens[0].line)
        assertEquals(9, tokens[0].column)
    }

    @Test
    fun errorPositionIsReported() {
        val e = assertFailsWith<LexerException> {
            lex("class A is\n    var x : 1\n    var y : 2@3\nend")
        }
        assertEquals(3, e.line)
        assertEquals(14, e.column)
    }

    // ------------------------------------------------------------- EOF

    @Test
    fun streamAlwaysEndsWithEof() {
        for (source in listOf("", "   ", "// only a comment", "class A is end", "\n\n")) {
            val tokens = lex(source)
            assertEquals(TokenType.EOF, tokens.last().type, "no EOF for: '$source'")
            assertEquals("", tokens.last().lexeme)
            assertEquals(1, tokens.count { it.type == TokenType.EOF }, "exactly one EOF expected")
        }
    }

    @Test
    fun emptySourceIsJustEof() {
        val tokens = lex("")
        assertEquals(1, tokens.size)
        assertEquals(Token(TokenType.EOF, "", 1, 1), tokens[0])
    }

    // ------------------------------------------------- unexpected characters

    @Test
    fun unknownCharacterThrowsInsteadOfCrashing() {
        val e = assertFailsWith<LexerException> { lex("var x : 1@2") }
        assertEquals(1, e.line)
        assertEquals(10, e.column)
        assertEquals("Unexpected character '@' at line 1, column 10", e.message)
    }

    @Test
    fun severalKindsOfUnknownCharacters() {
        for (c in listOf('@', '#', '$', '?', '!', '%', '&', '+', '-', '*', ';', '{', '}', '<', '>', '"')) {
            val e = assertFailsWith<LexerException>("'$c' should be rejected") { lex("x $c y") }
            assertTrue("'$c'" in e.message!!, "message should quote '$c': ${e.message}")
        }
    }

    @Test
    fun tokensBeforeTheErrorDoNotMatterTheExceptionStops() {
        // Nothing is returned on failure: the caller gets an exception,
        // not a half-built token list.
        assertFailsWith<LexerException> { lex("class A is\n@\nend") }
    }

    // ----------------------------------------------------- brackets & commas

    @Test
    fun bracketsParensAndCommas() {
        assertEquals(
            listOf(
                TokenType.IDENTIFIER, TokenType.LBRACKET, TokenType.IDENTIFIER,
                TokenType.RBRACKET, TokenType.LPAREN, TokenType.INT_LITERAL,
                TokenType.COMMA, TokenType.INT_LITERAL, TokenType.RPAREN
            ),
            types("Array[Integer](1, 2)")
        )
    }

    // ------------------------------------------------------ whole program

    @Test
    fun wholeProgramIsTokenized() {
        val source = """
            class Calculator is
                var result : Integer(0)
                method add(a: Integer, b: Integer) : Integer => a.Plus(b)
                this is
                    result := this.add(2, 3)
                end
            end
        """.trimIndent()

        val tokens = lex(source)
        assertEquals(TokenType.CLASS, tokens.first().type)
        assertEquals(TokenType.EOF, tokens.last().type)
        assertTrue(tokens.none { it.type == TokenType.IDENTIFIER && it.lexeme == "is" })
        assertEquals(1, tokens.count { it.type == TokenType.ARROW })
        assertEquals(1, tokens.count { it.type == TokenType.ASSIGN })
        assertEquals(4, tokens.count { it.type == TokenType.COLON })
        assertEquals(2, tokens.count { it.type == TokenType.COMMA })
    }

    // -------------------------------------------------------- file fixtures

    @Test
    fun allFixturesBehaveAsTheirNameSays() {
        val dir = File("tests")
        if (!dir.isDirectory) return
        val fixtures = dir.listFiles { f -> f.extension == "o" }?.sortedBy { it.name } ?: return
        assertTrue(fixtures.isNotEmpty(), "no .o fixtures found in ${dir.absolutePath}")

        for (file in fixtures) {
            val source = file.readText()
            if (file.name.startsWith("error_")) {
                assertFailsWith<LexerException>("${file.name} should not lex") { lex(source) }
            } else {
                val tokens = lex(source)
                assertEquals(TokenType.EOF, tokens.last().type, "${file.name} must end with EOF")
                assertTrue(tokens.size > 1, "${file.name} produced no tokens")
            }
        }
    }

    @Test
    fun allShippedExamplesExceptTheErrorOneLex() {
        val dir = File("examples")
        if (!dir.isDirectory) return
        val examples = dir.listFiles { f -> f.extension == "o" }?.sortedBy { it.name } ?: return
        for (file in examples) {
            if ("error" in file.name) continue
            val tokens = lex(file.readText())
            assertEquals(TokenType.EOF, tokens.last().type, "${file.name} must end with EOF")
        }
    }
}
