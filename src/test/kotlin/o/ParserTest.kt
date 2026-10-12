package o

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {

    private fun parse(source: String): Program = Parser(Lexer(source).tokenize()).parseProgram()

    private fun parseError(source: String): ParseException =
        assertFailsWith<ParseException> { parse(source) }

    /** The statements of a constructor wrapped around [statements]. */
    private fun body(statements: String): List<Statement> {
        val program = parse("class A is\nthis is\n$statements\nend\nend")
        val constructor = program.classes[0].members[0] as ConstructorDecl
        return constructor.body
    }

    /** The initializer of a field declared as `var x : <expression>`. */
    private fun expr(expression: String): Expr {
        val program = parse("class A is var x : $expression end")
        return (program.classes[0].members[0] as VarDecl).init
    }

    /** Shorthand for a plain name without type arguments, ignoring positions. */
    private fun assertName(expected: String, actual: Expr) {
        val name = assertIs<Name>(actual)
        assertEquals(expected, name.type.name)
        assertEquals(emptyList(), name.type.typeArgs)
    }

    // -------------------------------------------------------------- program

    @Test
    fun emptyProgramHasNoClasses() {
        assertEquals(emptyList(), parse("").classes)
        assertEquals(emptyList(), parse("// only a comment\n").classes)
    }

    @Test
    fun severalClassesKeepTheirOrder() {
        val program = parse("class A is end class B is end class C is end")
        assertEquals(listOf("A", "B", "C"), program.classes.map { it.name })
    }

    @Test
    fun classWithoutExtendsHasNoBase() {
        val decl = parse("class A is end").classes[0]
        assertEquals("A", decl.name)
        assertNull(decl.baseName)
        assertEquals(emptyList(), decl.members)
    }

    @Test
    fun classWithExtends() {
        val decl = parse("class Square extends Rectangle is end").classes[0]
        assertEquals("Square", decl.name)
        assertEquals("Rectangle", decl.baseName)
    }

    // -------------------------------------------------------------- members

    @Test
    fun allThreeMemberKinds() {
        val members = parse(
            """
            class A is
                var x : 1
                method f : Integer => x
                this is
                end
            end
            """.trimIndent()
        ).classes[0].members

        assertEquals(3, members.size)
        assertIs<VarDecl>(members[0])
        assertIs<MethodDecl>(members[1])
        assertIs<ConstructorDecl>(members[2])
    }

    @Test
    fun forwardDeclarationHasNoBody() {
        val members = parse(
            """
            class A is
                method isOdd(n: Integer) : Boolean
                method isEven(n: Integer) : Boolean => true
            end
            """.trimIndent()
        ).classes[0].members

        val forward = assertIs<MethodDecl>(members[0])
        assertEquals("isOdd", forward.name)
        assertEquals("Boolean", forward.returnType?.name)
        assertNull(forward.body)
        // The next member is still parsed as a member of its own.
        assertEquals("isEven", (members[1] as MethodDecl).name)
    }

    @Test
    fun forwardDeclarationAsLastMember() {
        val method = parse("class A is method f end").classes[0].members[0] as MethodDecl
        assertNull(method.body)
        assertNull(method.returnType)
    }

    @Test
    fun arrowBodyHoldsOneExpression() {
        val method = parse("class A is method add(a: Integer, b: Integer) : Integer => a.Plus(b) end")
            .classes[0].members[0] as MethodDecl

        assertEquals(listOf("a", "b"), method.params.map { it.name })
        assertEquals(listOf("Integer", "Integer"), method.params.map { it.type.name })
        val body = assertIs<MethodBody.Arrow>(method.body)
        val call = assertIs<Call>(body.expr)
        assertEquals("Plus", (call.callee as MemberAccess).name)
    }

    @Test
    fun blockBodyHoldsStatements() {
        val method = parse("class A is method f : Integer is var y : 1 return y end end")
            .classes[0].members[0] as MethodDecl

        val body = assertIs<MethodBody.Block>(method.body)
        assertEquals(2, body.statements.size)
        assertIs<VarDecl>(body.statements[0])
        assertIs<Return>(body.statements[1])
    }

    @Test
    fun methodWithParametersButNoReturnType() {
        val method = parse("class A is method fill(a: Array[Integer], v: Integer) is end end")
            .classes[0].members[0] as MethodDecl
        assertEquals(2, method.params.size)
        assertNull(method.returnType)
        assertEquals(MethodBody.Block(emptyList()), method.body)
    }

    @Test
    fun emptyParenthesesInParametersAndArguments() {
        val method = parse("class A is method toInteger() : Integer => Integer() end")
            .classes[0].members[0] as MethodDecl

        assertEquals(emptyList(), method.params)
        val call = assertIs<Call>((method.body as MethodBody.Arrow).expr)
        assertName("Integer", call.callee)
        assertEquals(emptyList(), call.args)
    }

    @Test
    fun constructorWithAndWithoutParameters() {
        val members = parse("class A is this is end this(w: Real, h: Real) is end end").classes[0].members
        assertEquals(emptyList(), (members[0] as ConstructorDecl).params)
        assertEquals(listOf("w", "h"), (members[1] as ConstructorDecl).params.map { it.name })
    }

    // ---------------------------------------------------------------- types

    @Test
    fun genericTypeInAParameter() {
        val method = parse("class A is method f(a: Array[Integer]) is end end")
            .classes[0].members[0] as MethodDecl
        val type = method.params[0].type
        assertEquals("Array", type.name)
        assertEquals(listOf("Integer"), type.typeArgs.map { it.name })
    }

    @Test
    fun genericConstructorCall() {
        // Array[Integer](10) = Call(Name Array[Integer], [IntLit 10])
        val call = assertIs<Call>(expr("Array[Integer](10)"))
        val callee = assertIs<Name>(call.callee)
        assertEquals("Array", callee.type.name)
        assertEquals(listOf("Integer"), callee.type.typeArgs.map { it.name })
        assertEquals(10, (call.args.single() as IntLit).value)
    }

    @Test
    fun nestedGenericType() {
        val name = assertIs<Name>(expr("List[Array[Real]]"))
        val inner = name.type.typeArgs.single()
        assertEquals("Array", inner.name)
        assertEquals("Real", inner.typeArgs.single().name)
    }

    // ---------------------------------------------------------- expressions

    @Test
    fun literalsAndThis() {
        assertEquals(42, (expr("42") as IntLit).value)
        assertEquals(3.14, (expr("3.14") as RealLit).value)
        assertEquals(true, (expr("true") as BoolLit).value)
        assertEquals(false, (expr("false") as BoolLit).value)
        assertIs<This>(expr("this"))
    }

    @Test
    fun bareNameIsANameNotACall() {
        assertName("counter", expr("counter"))
    }

    @Test
    fun memberWithoutArgumentsIsAMemberAccess() {
        val access = assertIs<MemberAccess>(expr("a.Length"))
        assertEquals("Length", access.name)
        assertName("a", access.target)
    }

    @Test
    fun methodCallIsACallOfAMemberAccess() {
        // a.get(1) = Call(MemberAccess(Name a, "get"), [IntLit 1])
        val call = assertIs<Call>(expr("a.get(1)"))
        val callee = assertIs<MemberAccess>(call.callee)
        assertEquals("get", callee.name)
        assertName("a", callee.target)
        assertEquals(1, (call.args.single() as IntLit).value)
    }

    @Test
    fun chainedCallsAreLeftAssociative() {
        // a.get(i).Greater(max) = (a.get(i)).Greater(max)
        val outer = assertIs<Call>(expr("a.get(i).Greater(max)"))
        val greater = assertIs<MemberAccess>(outer.callee)
        assertEquals("Greater", greater.name)
        assertName("max", outer.args.single())

        val inner = assertIs<Call>(greater.target)
        val get = assertIs<MemberAccess>(inner.callee)
        assertEquals("get", get.name)
        assertName("a", get.target)
        assertName("i", inner.args.single())
    }

    @Test
    fun methodCallOnAnIntegerLiteral() {
        val call = assertIs<Call>(expr("1.Plus(2)"))
        val plus = assertIs<MemberAccess>(call.callee)
        assertEquals(1, (plus.target as IntLit).value)
        assertEquals(2, (call.args.single() as IntLit).value)
    }

    @Test
    fun methodCallOnARealLiteral() {
        val call = assertIs<Call>(expr("1.5.Plus(2.5)"))
        val plus = assertIs<MemberAccess>(call.callee)
        assertEquals(1.5, (plus.target as RealLit).value)
        assertEquals(2.5, (call.args.single() as RealLit).value)
    }

    @Test
    fun argumentsMayBeExpressions() {
        val call = assertIs<Call>(expr("this.show(i.Plus(1), r, Integer(3))"))
        assertIs<This>((call.callee as MemberAccess).target)
        assertEquals(3, call.args.size)
        assertIs<Call>(call.args[0])
        assertIs<Name>(call.args[1])
        assertIs<Call>(call.args[2])
    }

    // ----------------------------------------------------------- statements

    @Test
    fun assignmentVersusExpressionStatement() {
        val statements = body("x := 5\nx.Plus(1)\nget(i)")

        val assignment = assertIs<Assignment>(statements[0])
        assertEquals("x", assignment.name)
        assertEquals(5, (assignment.value as IntLit).value)

        val call = assertIs<ExprStatement>(statements[1]).expr
        assertEquals("Plus", ((call as Call).callee as MemberAccess).name)

        // An unqualified call is still a plain Call of a Name: the parser
        // does not decide whether 'get' is a method or a class.
        val bare = assertIs<Call>(assertIs<ExprStatement>(statements[2]).expr)
        assertName("get", bare.callee)
    }

    @Test
    fun voidCallOnAnArrayIsAnExpressionStatement() {
        val statement = assertIs<ExprStatement>(body("a.set(i, v)").single())
        val call = assertIs<Call>(statement.expr)
        assertEquals(2, call.args.size)
    }

    @Test
    fun declarationsAndStatementsKeepSourceOrder() {
        val statements = body("var a : 1\na := 2\nvar b : a")
        assertIs<VarDecl>(statements[0])
        assertIs<Assignment>(statements[1])
        assertIs<VarDecl>(statements[2])
    }

    @Test
    fun ifWithoutElse() {
        val ifStatement = assertIs<If>(body("if a then x := 1 end").single())
        assertName("a", ifStatement.condition)
        assertEquals(1, ifStatement.thenBody.size)
        assertNull(ifStatement.elseBody)
    }

    @Test
    fun ifWithElseAndEmptyElse() {
        val full = assertIs<If>(body("if a then x := 1 else x := 2 end").single())
        assertEquals(1, full.elseBody?.size)

        val empty = assertIs<If>(body("if a then x := 1 else end").single())
        assertEquals(emptyList(), empty.elseBody)
    }

    @Test
    fun nestedIfAndWhile() {
        val outer = assertIs<While>(
            body(
                """
                while i.Less(n) loop
                    if i.Equal(0) then
                        while j.Less(i) loop
                            j := j.Plus(1)
                        end
                    else
                        if b then
                            return
                        end
                    end
                    i := i.Plus(1)
                end
                """.trimIndent()
            ).single()
        )

        assertEquals(2, outer.body.size)
        val ifStatement = assertIs<If>(outer.body[0])
        val innerWhile = assertIs<While>(ifStatement.thenBody.single())
        assertIs<Assignment>(innerWhile.body.single())
        val innerIf = assertIs<If>(ifStatement.elseBody!!.single())
        assertIs<Return>(innerIf.thenBody.single())
        assertIs<Assignment>(outer.body[1])
    }

    @Test
    fun returnWithAnExpression() {
        val statement = assertIs<Return>(body("return x.Plus(1)").single())
        assertIs<Call>(statement.value)
    }

    @Test
    fun returnWithoutAnExpression() {
        // Followed by 'end': nothing that can start an expression.
        assertNull(assertIs<Return>(body("return").single()).value)

        // Followed by 'else': the same.
        val ifStatement = assertIs<If>(body("if a then return else return 1 end").single())
        assertNull((ifStatement.thenBody.single() as Return).value)
        assertEquals(1, ((ifStatement.elseBody!!.single() as Return).value as IntLit).value)
    }

    @Test
    fun returnTakesAnExpressionOnTheNextLine() {
        // Without statement separators, an expression that follows 'return'
        // belongs to it, even on the next line. Documented in DOCUMENTATION.md.
        val statement = assertIs<Return>(body("return\nx").single())
        assertName("x", statement.value!!)
    }

    // ------------------------------------------------------------ positions

    @Test
    fun nodesCarryTheirSourcePositions() {
        val program = parse(
            """
            class A extends B is
                var x : Integer(0)
                method f(n: Integer) : Integer is
                    x := a.get(n)
                    return x
                end
            end
            """.trimIndent()
        )

        val decl = program.classes[0]
        assertEquals(1 to 1, decl.line to decl.column)

        val field = decl.members[0] as VarDecl
        assertEquals(2 to 5, field.line to field.column)
        assertEquals(2 to 13, field.init.line to field.init.column)       // Integer

        val method = decl.members[1] as MethodDecl
        assertEquals(3 to 5, method.line to method.column)
        val param = method.params[0]
        assertEquals(3 to 14, param.line to param.column)                 // n
        assertEquals(3 to 17, param.type.line to param.type.column)       // Integer

        val statements = (method.body as MethodBody.Block).statements
        val assignment = statements[0] as Assignment
        assertEquals(4 to 9, assignment.line to assignment.column)

        // A call sits at its callee; a member access at its member name.
        val call = assignment.value as Call
        assertEquals(4 to 16, call.line to call.column)                   // get
        val access = call.callee as MemberAccess
        assertEquals(4 to 16, access.line to access.column)               // get
        assertEquals(4 to 14, access.target.line to access.target.column) // a
        assertEquals(4 to 20, call.args[0].line to call.args[0].column)   // n

        val returnStatement = statements[1] as Return
        assertEquals(5 to 9, returnStatement.line to returnStatement.column)
    }

    @Test
    fun programStartsAtItsFirstToken() {
        val program = parse("\n\n  class A is end")
        assertEquals(3 to 3, program.line to program.column)
    }

    // ------------------------------------------------------- syntax errors

    @Test
    fun missingEndOfWhilePointsBackAtTheOpener() {
        val e = parseError(
            """
            class A is
                this is
                    while a loop
                        x := 1
                    else
                end
            end
            """.trimIndent()
        )
        assertEquals(5 to 9, e.line to e.column)                          // the 'else'
        assertEquals("expected 'end' to close 'while' opened at 3:9, found ELSE 'else'", e.message)
    }

    @Test
    fun missingEndAtEndOfFile() {
        val e = parseError("class A is\n    this is\n    end\n")
        assertEquals(4 to 1, e.line to e.column)
        assertTrue("to close class 'A' opened at 1:1" in e.message!!, e.message)
        assertTrue("found end of file" in e.message!!, e.message)
    }

    @Test
    fun missingEndOfIfIsAbsorbedByTheEnclosingBlocks() {
        // Without layout rules, the 'end' meant for the method closes the
        // 'if' instead, and so on outwards: the error surfaces at the class.
        val e = parseError(
            """
            class A is
                method f(x: Integer) : Integer is
                    if x.Less(0) then
                        return 0
                    return x
                end
            end
            """.trimIndent()
        )
        assertEquals(7 to 4, e.line to e.column)
        assertTrue("to close class 'A' opened at 1:1" in e.message!!, e.message)
    }

    @Test
    fun varWithIsGivesASpecificMessage() {
        val e = parseError("class A is\n    var i is 1\nend")
        assertEquals(2 to 11, e.line to e.column)                         // the 'is'
        assertTrue("use ':', not 'is'" in e.message!!, e.message)
    }

    @Test
    fun genericClassDeclarationIsRejected() {
        val e = parseError("class C[T] is\nend")
        assertEquals(1 to 8, e.line to e.column)                          // the '['
        assertTrue("out of scope" in e.message!!, e.message)
    }

    @Test
    fun topLevelMethodIsRejected() {
        val e = parseError("method MaxInt(a: Array[Integer]) : Integer is\nend")
        assertEquals(1 to 1, e.line to e.column)
        assertTrue("inside a class" in e.message!!, e.message)
    }

    @Test
    fun topLevelVariableIsRejected() {
        val e = parseError("var i : 1")
        assertEquals(1 to 1, e.line to e.column)
        assertTrue("expected 'class'" in e.message!!, e.message)
    }

    @Test
    fun assignmentToACallIsRejected() {
        // From section 3 of the specification.
        val e = parseError("class A is\n    this is\n        a.set(i) := 55\n    end\nend")
        assertEquals(3 to 9, e.line to e.column)                          // the 'a'
        assertTrue("left side of ':='" in e.message!!, e.message)
    }

    @Test
    fun missingClosingParenthesisInArguments() {
        val e = parseError("class A is\n    var x : Integer(1\nend")
        assertEquals(3 to 1, e.line to e.column)                          // the 'end'
        assertEquals("expected ',' or ')' to close the argument list opened at 2:20, found END 'end'", e.message)
    }

    @Test
    fun missingClosingParenthesisInParameters() {
        val e = parseError("class A is\n    method f(a: Integer b: Integer) is end\nend")
        assertEquals(2 to 25, e.line to e.column)                         // the 'b'
        assertTrue("to close the parameter list opened at 2:13" in e.message!!, e.message)
    }

    @Test
    fun missingThenNamesTheIf() {
        val e = parseError("class A is\n    this is\n        if a x := 1 end\n    end\nend")
        assertEquals(3 to 14, e.line to e.column)                         // the 'x'
        assertEquals("expected 'then' after the condition of 'if' at 3:9, found IDENTIFIER 'x'", e.message)
    }

    @Test
    fun missingLoopNamesTheWhile() {
        val e = parseError("class A is\n    this is\n        while a x := 1 end\n    end\nend")
        assertEquals(3 to 17, e.line to e.column)
        assertTrue("expected 'loop' after the condition of 'while' at 3:9" in e.message!!, e.message)
    }

    @Test
    fun missingExpression() {
        val e = parseError("class A is\n    var x :\nend")
        assertEquals(3 to 1, e.line to e.column)
        assertEquals("expected an expression, found END 'end'", e.message)
    }

    @Test
    fun statementDirectlyInAClassBody() {
        val e = parseError("class A is\n    x := 1\nend")
        assertEquals(2 to 5, e.line to e.column)
        assertTrue("a member ('var', 'method' or 'this') or 'end'" in e.message!!, e.message)
    }

    @Test
    fun emptyTypeArgumentListIsRejected() {
        val e = parseError("class A is\n    var a : Array[](1)\nend")
        assertEquals(2 to 19, e.line to e.column)
        assertEquals("expected a class name, found RBRACKET ']'", e.message)
    }

    @Test
    fun integerLiteralTooLargeForAnInteger() {
        val e = parseError("class A is var x : 99999999999 end")
        assertEquals(1 to 20, e.line to e.column)
        assertTrue("too large" in e.message!!, e.message)
    }

    @Test
    fun theParserStopsAtTheFirstError() {
        // Two errors; only the first one is reported.
        val e = parseError("class A is\n    var i is 1\n    var j is 2\nend")
        assertEquals(2, e.line)
    }

    // -------------------------------------------------------- file fixtures

    @Test
    fun allParserFixturesBehaveAsTheirNameSays() {
        val dir = File("tests")
        if (!dir.isDirectory) return
        val fixtures = dir.listFiles { f -> f.extension == "o" }?.sortedBy { it.name } ?: return

        val mustParse = fixtures.filter { it.name.startsWith("parser_") }
        val mustFail = fixtures.filter { it.name.startsWith("parse_error_") }
        assertTrue(mustParse.isNotEmpty(), "no tests/parser_*.o fixtures found")
        assertTrue(mustFail.isNotEmpty(), "no tests/parse_error_*.o fixtures found")

        for (file in mustParse) {
            val program = parse(file.readText())
            assertTrue(program.classes.isNotEmpty(), "${file.name} parsed to no classes")
        }
        for (file in mustFail) {
            assertFailsWith<ParseException>("${file.name} should not parse") { parse(file.readText()) }
        }
    }

    @Test
    fun allShippedExamplesParseExceptTheErrorDemos() {
        val dir = File("examples")
        if (!dir.isDirectory) return
        val examples = dir.listFiles { f -> f.extension == "o" }?.sortedBy { it.name } ?: return

        for (file in examples) {
            val source = file.readText()
            when (file.name) {
                // Lexical error demo: never reaches the parser.
                "04_error.o" -> assertFailsWith<LexerException> { parse(source) }
                // The specification's own examples, and the syntax error demo.
                "11_probe_spec_examples.o", "13_parse_error.o" ->
                    assertFailsWith<ParseException>("${file.name} should not parse") { parse(source) }
                else -> {
                    val program = parse(source)
                    assertTrue(program.classes.isNotEmpty(), "${file.name} parsed to no classes")
                }
            }
        }
    }
}
