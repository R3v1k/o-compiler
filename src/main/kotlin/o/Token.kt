package o

/**
 * All lexical categories of the O language.
 *
 * O has no infix operators at all, so there is nothing here for `+`, `-`, `<`
 * and friends: arithmetic is written as method calls (`a.Plus(b)`), which the
 * lexer sees as IDENTIFIER DOT IDENTIFIER LPAREN ... RPAREN.
 */
enum class TokenType {
    // Keywords
    CLASS,
    EXTENDS,
    IS,
    END,
    VAR,
    METHOD,
    THIS,
    WHILE,
    LOOP,
    IF,
    THEN,
    ELSE,
    RETURN,

    // Literals
    INT_LITERAL,
    REAL_LITERAL,
    BOOL_LITERAL,

    // Names
    IDENTIFIER,

    // Symbols
    COLON,      // :
    ASSIGN,     // :=
    DOT,        // .
    COMMA,      // ,
    LPAREN,     // (
    RPAREN,     // )
    LBRACKET,   // [
    RBRACKET,   // ]
    ARROW,      // =>

    // End of input
    EOF
}

/**
 * A single lexeme together with its place in the source file.
 *
 * [line] and [column] are 1-based and [column] always points at the *first*
 * character of the lexeme. Every later compiler phase (parser, semantic
 * analysis, code generation) reports its diagnostics through these numbers,
 * so they are part of the token from the very beginning.
 */
data class Token(
    val type: TokenType,
    val lexeme: String,
    val line: Int,
    val column: Int
) {
    override fun toString(): String = "$type('$lexeme') at $line:$column"
}

/**
 * Raised when the character stream cannot be turned into a token.
 *
 * Carries the position separately from the message so that a caller can
 * render a source excerpt with a caret under the offending character.
 */
class LexerException(
    message: String,
    val line: Int,
    val column: Int
) : Exception(message)
