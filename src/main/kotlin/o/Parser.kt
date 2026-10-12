package o

import o.TokenType.*

/**
 * Raised when the token stream does not form a valid O program.
 *
 * Like [LexerException], the position is kept apart from the message so the
 * CLI can print the source line with a caret under the offending token.
 */
class ParseException(
    message: String,
    val line: Int,
    val column: Int
) : Exception(message)

/**
 * Hand-written recursive-descent parser for the O language.
 *
 * There is one function per grammar rule (`parseClass`, `parseWhile`, ...),
 * and each one decides what to do next by looking at the current token only.
 * The single place that needs a second token is the start of a statement:
 * `x := ...` and `x.foo()` both begin with an IDENTIFIER, and only the token
 * after it tells them apart.
 *
 * The expression rule of the specification is left-recursive
 * (`Expression : Expression { . Expression }`); it is implemented here in its
 * iterative form `Primary { . Identifier [ Arguments ] }`. The full grammar
 * that this class implements is in DOCUMENTATION.md, Phase 2.
 *
 * The parser stops at the first error by throwing [ParseException].
 *
 * Usage:
 * ```
 * val program = Parser(Lexer(source).tokenize()).parseProgram()
 * ```
 */
class Parser(private val tokens: List<Token>) {

    /** Index of the current (not yet consumed) token. */
    private var pos = 0

    init {
        // The lexer guarantees this; every peek() below relies on it.
        require(tokens.isNotEmpty() && tokens.last().type == EOF) {
            "the token list must end with EOF"
        }
    }

    // ------------------------------------------------------------- program

    /** `Program : { ClassDecl } EOF` */
    fun parseProgram(): Program {
        val first = peek()
        val classes = mutableListOf<ClassDecl>()

        while (!check(EOF)) {
            when {
                check(CLASS) -> classes.add(parseClass())
                check(METHOD) -> throw error(
                    peek(),
                    "a method must be declared inside a class; " +
                        "a program is a sequence of class declarations only"
                )
                else -> throw error(
                    peek(),
                    "expected 'class' (a program is a sequence of class declarations), " +
                        "found ${describe(peek())}"
                )
            }
        }

        return Program(classes, first.line, first.column)
    }

    /** `ClassDecl : class Identifier [ extends Identifier ] is { Member } end` */
    private fun parseClass(): ClassDecl {
        val classToken = advance()
        val name = expect(IDENTIFIER, "a class name after 'class'").lexeme

        // The specification writes `class C[T]` in an example, but generic
        // declarations are explicitly outside the course scope. Saying so is
        // more useful than a bare "expected 'is', found '['".
        if (check(LBRACKET)) {
            throw error(
                peek(),
                "generic class declarations such as 'class $name[T]' are out of scope; " +
                    "type arguments are only allowed where a class is used, e.g. Array[Integer]"
            )
        }

        var baseName: String? = null
        if (match(EXTENDS)) {
            baseName = expect(IDENTIFIER, "a base class name after 'extends'").lexeme
        }

        expect(IS, "'is' after the header of class '$name'")

        val members = mutableListOf<Member>()
        while (check(VAR) || check(METHOD) || check(THIS)) {
            members.add(parseMember())
        }

        expectEnd(classToken, "class '$name'", "a member ('var', 'method' or 'this') or 'end'")
        return ClassDecl(name, baseName, members, classToken.line, classToken.column)
    }

    // ------------------------------------------------------------- members

    /** `Member : VarDecl | MethodDecl | ConstructorDecl` */
    private fun parseMember(): Member = when {
        check(VAR) -> parseVarDecl()
        check(METHOD) -> parseMethod()
        else -> parseConstructor()
    }

    /** `VarDecl : var Identifier : Expression` */
    private fun parseVarDecl(): VarDecl {
        val varToken = advance()
        val name = expect(IDENTIFIER, "a variable name after 'var'").lexeme

        // Section 3 of the specification writes `var i is 1`, while the
        // grammar in section 1 says `var Identifier : Expression`. We follow
        // the grammar, and name the conflict instead of a generic error.
        if (check(IS)) {
            throw error(
                peek(),
                "variable declarations use ':', not 'is': write 'var $name : ...' " +
                    "(the grammar is 'var Identifier : Expression'; " +
                    "the 'is' form only appears in the examples of the specification)"
            )
        }

        expect(COLON, "':' after the variable name '$name'")
        val init = parseExpression()
        return VarDecl(name, init, varToken.line, varToken.column)
    }

    /** `MethodDecl : method Identifier [ Parameters ] [ : TypeName ] [ MethodBody ]` */
    private fun parseMethod(): MethodDecl {
        val methodToken = advance()
        val name = expect(IDENTIFIER, "a method name after 'method'").lexeme

        val params = if (check(LPAREN)) parseParameters() else emptyList()
        val returnType = if (match(COLON)) parseTypeName() else null

        // `MethodBody : is Body end | => Expression`. Neither 'is' nor '=>'
        // means there is no body: a forward declaration.
        val body: MethodBody? = when {
            match(IS) -> {
                val statements = parseBody()
                expectEnd(methodToken, "method '$name'")
                MethodBody.Block(statements)
            }
            match(ARROW) -> MethodBody.Arrow(parseExpression())
            else -> null
        }

        return MethodDecl(name, params, returnType, body, methodToken.line, methodToken.column)
    }

    /** `ConstructorDecl : this [ Parameters ] is Body end` */
    private fun parseConstructor(): ConstructorDecl {
        val thisToken = advance()
        val params = if (check(LPAREN)) parseParameters() else emptyList()
        expect(IS, "'is' after the constructor header")
        val body = parseBody()
        expectEnd(thisToken, "the constructor")
        return ConstructorDecl(params, body, thisToken.line, thisToken.column)
    }

    /**
     * `Parameters : ( [ Param { , Param } ] )`
     *
     * The specification's rule requires at least one parameter, but its own
     * library declares `Boolean.toInteger()`, so an empty list is accepted.
     */
    private fun parseParameters(): List<Param> {
        val open = expect(LPAREN, "'('")
        val params = mutableListOf<Param>()
        if (!check(RPAREN)) {
            do {
                params.add(parseParam())
            } while (match(COMMA))
        }
        expectClosing(RPAREN, open, "the parameter list")
        return params
    }

    /** `Param : Identifier : TypeName` */
    private fun parseParam(): Param {
        val name = expect(IDENTIFIER, "a parameter name")
        expect(COLON, "':' after the parameter name '${name.lexeme}'")
        val type = parseTypeName()
        return Param(name.lexeme, type, name.line, name.column)
    }

    /** `TypeName : Identifier [ '[' TypeName { , TypeName } ']' ]` */
    private fun parseTypeName(): TypeRef {
        val name = expect(IDENTIFIER, "a class name")
        val typeArgs = mutableListOf<TypeRef>()
        if (check(LBRACKET)) {
            val open = advance()
            do {
                typeArgs.add(parseTypeName())
            } while (match(COMMA))
            expectClosing(RBRACKET, open, "the type argument list")
        }
        return TypeRef(name.lexeme, typeArgs, name.line, name.column)
    }

    // ---------------------------------------------------------- statements

    /**
     * `Body : { VarDecl | Statement }`
     *
     * O has no statement separator and no braces, so a body simply runs for
     * as long as the next token can begin a body item. Whatever stops it
     * (`end`, `else`, or a stray token) is checked by the caller, which knows
     * which block it is closing and can name it in the error message.
     */
    private fun parseBody(): List<Statement> {
        val items = mutableListOf<Statement>()
        while (check(VAR) || startsStatement()) {
            items.add(if (check(VAR)) parseVarDecl() else parseStatement())
        }
        return items
    }

    /** `Statement : Assignment | WhileLoop | IfStatement | Return | ExprStatement` */
    private fun parseStatement(): Statement = when {
        check(WHILE) -> parseWhile()
        check(IF) -> parseIf()
        check(RETURN) -> parseReturn()
        // The one place where one token of lookahead is not enough: both
        // `x := 1` and `x.foo()` start with IDENTIFIER.
        check(IDENTIFIER) && peekNext().type == ASSIGN -> parseAssignment()
        else -> parseExpressionStatement()
    }

    /** `Assignment : Identifier := Expression` */
    private fun parseAssignment(): Assignment {
        val name = advance()
        advance() // ':='
        val value = parseExpression()
        return Assignment(name.lexeme, value, name.line, name.column)
    }

    /** `WhileLoop : while Expression loop Body end` */
    private fun parseWhile(): While {
        val whileToken = advance()
        val condition = parseExpression()
        expect(LOOP, "'loop' after the condition of 'while' at ${at(whileToken)}")
        val body = parseBody()
        expectEnd(whileToken, "'while'")
        return While(condition, body, whileToken.line, whileToken.column)
    }

    /** `IfStatement : if Expression then Body [ else Body ] end` */
    private fun parseIf(): If {
        val ifToken = advance()
        val condition = parseExpression()
        expect(THEN, "'then' after the condition of 'if' at ${at(ifToken)}")
        val thenBody = parseBody()

        var elseBody: List<Statement>? = null
        if (match(ELSE)) {
            elseBody = parseBody()
            expectEnd(ifToken, "'if'")
        } else {
            expectEnd(ifToken, "'if'", "'else' or 'end'")
        }

        return If(condition, thenBody, elseBody, ifToken.line, ifToken.column)
    }

    /**
     * `Return : return [ Expression ]`
     *
     * The expression is optional and nothing marks where a statement ends, so
     * the only way to decide is: if the next token can start an expression,
     * it belongs to the return. In practice `return` is followed by `end` or
     * `else` when it has no value, which cannot start an expression.
     */
    private fun parseReturn(): Return {
        val returnToken = advance()
        val value = if (startsExpression()) parseExpression() else null
        return Return(value, returnToken.line, returnToken.column)
    }

    /**
     * `ExprStatement : Expression` — an EXTENSION of the specification,
     * needed for calls such as `a.set(i, v)` whose result is not used.
     */
    private fun parseExpressionStatement(): ExprStatement {
        val start = peek()
        val expr = parseExpression()

        // `a.set(i) := 55` from section 3 of the specification lands here:
        // the left side was parsed as an expression and is followed by ':='.
        if (check(ASSIGN)) {
            throw error(
                start,
                "cannot assign to this expression: the left side of ':=' must be " +
                    "a plain variable name (the grammar is 'Identifier := Expression')"
            )
        }

        return ExprStatement(expr, start.line, start.column)
    }

    private fun startsStatement(): Boolean =
        check(WHILE) || check(IF) || check(RETURN) || startsExpression()

    /** FIRST(Expression): the tokens that can begin an expression. */
    private fun startsExpression(): Boolean =
        check(INT_LITERAL) || check(REAL_LITERAL) || check(BOOL_LITERAL) ||
            check(THIS) || check(IDENTIFIER)

    // --------------------------------------------------------- expressions

    /**
     * `Expression : Primary { . Identifier [ Arguments ] }`
     *
     * The specification's `Expression { . Expression }` is left-recursive: a
     * function for it would call itself before consuming a token and never
     * stop. The loop below accepts the same chains, and building the tree
     * as it goes makes the call chain left-associative, so
     * `a.get(i).Greater(max)` is `(a.get(i)).Greater(max)`.
     */
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

    /** `Primary : IntLit | RealLit | BoolLit | this | TypeName [ Arguments ]` */
    private fun parsePrimary(): Expr {
        val token = peek()
        return when (token.type) {
            INT_LITERAL -> {
                advance()
                IntLit(intValue(token), token.line, token.column)
            }
            REAL_LITERAL -> {
                advance()
                RealLit(token.lexeme.toDouble(), token.line, token.column)
            }
            BOOL_LITERAL -> {
                advance()
                BoolLit(token.lexeme == "true", token.line, token.column)
            }
            THIS -> {
                advance()
                This(token.line, token.column)
            }
            IDENTIFIER -> {
                // `x`, `Integer(0)`, `Array[Integer](10)` and `get(i)` all
                // start here. Which of them name a class and which a variable
                // or method is not visible in the syntax; see [Name].
                val name = Name(parseTypeName(), token.line, token.column)
                if (check(LPAREN)) {
                    Call(name, parseArguments(), token.line, token.column)
                } else {
                    name
                }
            }
            else -> throw error(token, "expected an expression, found ${describe(token)}")
        }
    }

    /** `Arguments : ( [ Expression { , Expression } ] )` */
    private fun parseArguments(): List<Expr> {
        val open = expect(LPAREN, "'('")
        val args = mutableListOf<Expr>()
        if (!check(RPAREN)) {
            do {
                args.add(parseExpression())
            } while (match(COMMA))
        }
        expectClosing(RPAREN, open, "the argument list")
        return args
    }

    /**
     * Converts an INT_LITERAL to its value. The lexer accepts any run of
     * digits, so a literal that does not fit an Integer is reported here,
     * with its position, rather than crashing a later phase.
     */
    private fun intValue(token: Token): Int =
        token.lexeme.toIntOrNull() ?: throw error(
            token,
            "integer literal ${token.lexeme} is too large (the largest Integer is ${Int.MAX_VALUE})"
        )

    // ------------------------------------------------------------- helpers

    private fun peek(): Token = tokens[pos]

    /** The token after the current one; EOF if there is none. */
    private fun peekNext(): Token = if (pos + 1 < tokens.size) tokens[pos + 1] else tokens.last()

    private fun check(type: TokenType): Boolean = peek().type == type

    /**
     * Consumes the current token and returns it. EOF is never consumed, so
     * [peek] stays valid however many times the parser asks past the end.
     */
    private fun advance(): Token {
        val token = tokens[pos]
        if (token.type != EOF) {
            pos++
        }
        return token
    }

    /** Consumes the current token only if it has the given [type]. */
    private fun match(type: TokenType): Boolean {
        if (check(type)) {
            advance()
            return true
        }
        return false
    }

    /** Consumes a token of the given [type], or fails saying [what] was expected. */
    private fun expect(type: TokenType, what: String): Token {
        if (check(type)) {
            return advance()
        }
        throw error(peek(), "expected $what, found ${describe(peek())}")
    }

    /**
     * Consumes the `end` that closes a block, or fails pointing back at the
     * token that [opener]ed it. Without that back-reference "expected 'end'"
     * at the bottom of a long method says nothing about *which* block is open.
     */
    private fun expectEnd(opener: Token, block: String, expected: String = "'end'") {
        if (!match(END)) {
            throw error(
                peek(),
                "expected $expected to close $block opened at ${at(opener)}, found ${describe(peek())}"
            )
        }
    }

    /** Same idea as [expectEnd], for `)` and `]`. */
    private fun expectClosing(type: TokenType, open: Token, list: String) {
        if (!match(type)) {
            val closer = if (type == RPAREN) ")" else "]"
            throw error(
                peek(),
                "expected ',' or '$closer' to close $list opened at ${at(open)}, found ${describe(peek())}"
            )
        }
    }

    private fun error(token: Token, message: String) = ParseException(message, token.line, token.column)

    private fun at(token: Token): String = "${token.line}:${token.column}"

    private fun describe(token: Token): String =
        if (token.type == EOF) "end of file" else "${token.type} '${token.lexeme}'"
}
