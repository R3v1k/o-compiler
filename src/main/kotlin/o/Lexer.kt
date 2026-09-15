package o

/**
 * Hand-written scanner for the O language.
 *
 * The source text is consumed strictly character by character: there is no
 * regular expression applied to a whole line, because positions of individual
 * lexemes must stay exact and because the tricky cases below (`:` vs `:=`,
 * `1.5` vs `1.Plus(2)`) are decided by a single character of lookahead.
 *
 * Usage:
 * ```
 * val tokens = Lexer(File("Program.o").readText()).tokenize()
 * ```
 */
class Lexer(private val source: String) {

    /** Offset of the next character to read. */
    private var pos = 0

    /** 1-based line of the character at [pos]. */
    private var line = 1

    /** 1-based column of the character at [pos]. */
    private var column = 1

    private val tokens = mutableListOf<Token>()

    /**
     * Converts the whole source into a token list.
     *
     * The result always ends with a single [TokenType.EOF] token, so the
     * parser can look ahead one token without checking for the end of the
     * list. Throws [LexerException] on the first illegal character.
     */
    fun tokenize(): List<Token> {
        tokens.clear()
        pos = 0
        line = 1
        column = 1

        while (!isAtEnd()) {
            val c = peek()
            when {
                c == '/' && peekNext() == '/' -> skipLineComment()
                c == '/' && peekNext() == '*' -> skipBlockComment()
                c.isWhitespace() -> advance()
                c.isDigit() -> readNumber()
                isIdentifierStart(c) -> readIdentifierOrKeyword()
                else -> readSymbol()
            }
        }

        tokens.add(Token(TokenType.EOF, "", line, column))
        return tokens.toList()
    }

    // ---------------------------------------------------------------- numbers

    /**
     * Reads an integer or a real literal.
     *
     * The dot is absorbed into the number **only** when a digit follows it
     * immediately. That single condition is what separates `1.5`
     * (one REAL_LITERAL) from `1.Plus(2)` (INT_LITERAL, DOT, IDENTIFIER, ...),
     * which in a language without infix operators is an everyday expression.
     */
    private fun readNumber() {
        val startLine = line
        val startColumn = column
        val text = StringBuilder()

        while (!isAtEnd() && peek().isDigit()) {
            text.append(advance())
        }

        var isReal = false
        if (!isAtEnd() && peek() == '.' && peekNext()?.isDigit() == true) {
            isReal = true
            text.append(advance()) // the '.'
            while (!isAtEnd() && peek().isDigit()) {
                text.append(advance())
            }
        }

        val type = if (isReal) TokenType.REAL_LITERAL else TokenType.INT_LITERAL
        tokens.add(Token(type, text.toString(), startLine, startColumn))
    }

    // ------------------------------------------------------ names & keywords

    /**
     * Reads a maximal run of identifier characters and only then decides what
     * it is, by a single lookup in [KEYWORDS].
     *
     * Matching keywords character by character inside the main loop would make
     * `isEven` start as the keyword `is`; scanning the whole word first makes
     * that impossible by construction.
     */
    private fun readIdentifierOrKeyword() {
        val startLine = line
        val startColumn = column
        val text = StringBuilder()

        while (!isAtEnd() && isIdentifierPart(peek())) {
            text.append(advance())
        }

        val lexeme = text.toString()
        val type = KEYWORDS[lexeme] ?: TokenType.IDENTIFIER
        tokens.add(Token(type, lexeme, startLine, startColumn))
    }

    // ---------------------------------------------------------------- symbols

    private fun readSymbol() {
        val startLine = line
        val startColumn = column
        val c = advance()

        when (c) {
            ':' -> {
                // ':' and ':=' differ by one character of lookahead.
                if (!isAtEnd() && peek() == '=') {
                    advance()
                    add(TokenType.ASSIGN, ":=", startLine, startColumn)
                } else {
                    add(TokenType.COLON, ":", startLine, startColumn)
                }
            }

            '=' -> {
                // '=' is not a token of O on its own: the language has no
                // infix operators, so equality is `a.Equal(b)`. The character
                // is legal only as the first half of the '=>' arrow.
                if (!isAtEnd() && peek() == '>') {
                    advance()
                    add(TokenType.ARROW, "=>", startLine, startColumn)
                } else {
                    throw LexerException(
                        "Unexpected character '=' at line $startLine, column $startColumn " +
                            "('=' is not an operator in O; did you mean '=>' ?)",
                        startLine,
                        startColumn
                    )
                }
            }

            '.' -> add(TokenType.DOT, ".", startLine, startColumn)
            ',' -> add(TokenType.COMMA, ",", startLine, startColumn)
            '(' -> add(TokenType.LPAREN, "(", startLine, startColumn)
            ')' -> add(TokenType.RPAREN, ")", startLine, startColumn)
            '[' -> add(TokenType.LBRACKET, "[", startLine, startColumn)
            ']' -> add(TokenType.RBRACKET, "]", startLine, startColumn)

            else -> throw LexerException(
                "Unexpected character '$c' at line $startLine, column $startColumn",
                startLine,
                startColumn
            )
        }
    }

    // --------------------------------------------------------------- comments

    /** Drops `// ...` up to (but not including) the line terminator. */
    private fun skipLineComment() {
        while (!isAtEnd() && peek() != '\n') {
            advance()
        }
    }

    /**
     * Drops a slash-star ... star-slash block, which may span any number of
     * lines.
     *
     * Blocks do not nest: the first star-slash closes the comment, so a second
     * opener inside a block is just ordinary comment text. Reaching the end of
     * the file with the block still open is a lexical error reported at the
     * position where the block was *opened* — that is the line the author has
     * to go and fix, not the last line of the file.
     */
    private fun skipBlockComment() {
        val startLine = line
        val startColumn = column
        advance() // '/'
        advance() // '*'

        while (true) {
            if (isAtEnd()) {
                throw LexerException(
                    "Unterminated block comment opened at line $startLine, column $startColumn " +
                        "(expected a closing '*/')",
                    startLine,
                    startColumn
                )
            }
            if (peek() == '*' && peekNext() == '/') {
                advance()
                advance()
                return
            }
            advance()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun add(type: TokenType, lexeme: String, line: Int, column: Int) {
        tokens.add(Token(type, lexeme, line, column))
    }

    private fun isAtEnd(): Boolean = pos >= source.length

    private fun peek(): Char = source[pos]

    private fun peekNext(): Char? = if (pos + 1 < source.length) source[pos + 1] else null

    /**
     * Consumes one character and keeps the line/column counters in sync.
     * This is the only place where [pos], [line] and [column] change.
     */
    private fun advance(): Char {
        val c = source[pos]
        pos++
        if (c == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return c
    }

    private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_'

    private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    companion object {
        /**
         * Reserved words of O.
         *
         * `true` and `false` are deliberately mapped to BOOL_LITERAL rather
         * than to keywords of their own: for every later phase they are
         * constants of type Boolean, exactly like `42` is a constant of type
         * Integer.
         */
        val KEYWORDS: Map<String, TokenType> = mapOf(
            "class" to TokenType.CLASS,
            "extends" to TokenType.EXTENDS,
            "is" to TokenType.IS,
            "end" to TokenType.END,
            "var" to TokenType.VAR,
            "method" to TokenType.METHOD,
            "this" to TokenType.THIS,
            "while" to TokenType.WHILE,
            "loop" to TokenType.LOOP,
            "if" to TokenType.IF,
            "then" to TokenType.THEN,
            "else" to TokenType.ELSE,
            "return" to TokenType.RETURN,
            "true" to TokenType.BOOL_LITERAL,
            "false" to TokenType.BOOL_LITERAL
        )
    }
}
