package o

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptimizerTest {
    private val optimizer = Optimizer()

    private fun parse(source: String): Program = Parser(Lexer(source).tokenize()).parseProgram()

    private fun field(program: Program): Expr = (program.classes.first().members.first() as VarDecl).init

    private fun fold(expression: String): Expr = field(optimizer.foldConstants(parse("class A is var value : $expression end")))

    private fun statements(program: Program, name: String = "answer"): List<Statement> {
        val method = program.classes.first().members.filterIsInstance<MethodDecl>().first { it.name == name }
        return (method.body as MethodBody.Block).statements
    }

    private fun method(body: String, params: String = ""): Program =
        parse("class A is method answer($params) : Integer is $body end end")

    @Test
    fun integerArithmeticFoldsAllSupportedOperations() {
        val cases = mapOf("5.Plus(3)" to 8, "10.Minus(4)" to 6, "3.Mult(7)" to 21,
            "9.Div(2)" to 4, "9.Rem(2)" to 1, "5.UnaryMinus" to -5, "5.UnaryMinus()" to -5)
        for ((source, expected) in cases) assertEquals(expected, assertIs<IntLit>(fold(source)).value, source)
    }

    @Test
    fun numericComparisonsFold() {
        val cases = mapOf("3.Less(5)" to true, "3.LessEqual(3)" to true, "5.Greater(3)" to true,
            "3.GreaterEqual(5)" to false, "3.Equal(3)" to true, "3.Equal(4)" to false,
            "3.Less(5.0)" to true, "3.5.Greater(3)" to true, "3.5.Equal(3.5)" to true)
        for ((source, expected) in cases) assertEquals(expected, assertIs<BoolLit>(fold(source)).value, source)
    }

    @Test
    fun booleanMethodsFoldIncludingBareAndExplicitZeroArgumentCalls() {
        val cases = mapOf("true.Not" to false, "true.Not()" to false, "true.And(false)" to false,
            "false.Or(true)" to true, "true.Xor(true)" to false, "true.Xor(false)" to true)
        for ((source, expected) in cases) assertEquals(expected, assertIs<BoolLit>(fold(source)).value, source)
    }

    @Test
    fun nestedCallsAndCallChainsFoldFromInsideOut() {
        assertEquals(14, assertIs<IntLit>(fold("2.Plus(3.Mult(4))")).value)
        assertEquals(18, assertIs<IntLit>(fold("2.Plus(4).Mult(3)")).value)
        assertFalse(assertIs<BoolLit>(fold("2.Plus(3).Equal(5).Not()")).value)
    }

    @Test
    fun exactRealAndMixedArithmeticKeepTheirRealResultType() {
        val cases = mapOf("1.5.Plus(2.5)" to 4.0, "5.5.Minus(2)" to 3.5,
            "1.5.Mult(2)" to 3.0, "3.Div(2.0)" to 1.5, "5.5.Rem(2)" to 1.5,
            "1.5.UnaryMinus()" to -1.5, "3.toReal" to 3.0, "3.toReal()" to 3.0)
        for ((source, expected) in cases) assertEquals(expected, assertIs<RealLit>(fold(source)).value, source)
    }

    @Test
    fun roundedRealResultsAndUnspecifiedConversionsArePreserved() {
        assertIs<Call>(fold("0.1.Plus(0.2)"))
        assertIs<Call>(fold("1.0.Div(3)"))
        assertIs<MemberAccess>(fold("1.toBoolean"))
        assertIs<MemberAccess>(fold("1.5.toInteger"))
        assertIs<Call>(fold("true.toInteger()"))
    }

    @Test
    fun divisionAndRemainderByZeroAreNeverEvaluatedOrDiscarded() {
        for (source in listOf("5.Div(0)", "5.Rem(0)", "5.0.Div(0)", "5.0.Rem(0)", "5.Div(0.0)")) {
            assertIs<Call>(fold(source), source)
        }
        val program = method("var error : 5.Div(0) return 1")
        assertIs<VarDecl>(statements(optimizer.optimize(program)).first())
    }

    @Test
    fun integerOverflowIsPreservedRatherThanWrappedByTheOptimizer() {
        assertIs<Call>(fold("2147483647.Plus(1)"))
        assertIs<Call>(fold("2147483647.Mult(2)"))
        assertIs<Call>(fold("0.Minus(2147483647).Minus(2)"))
        assertIs<Call>(fold("0.Minus(2147483647).Minus(1).UnaryMinus()"))
        assertIs<Call>(fold("0.Minus(2147483647).Minus(1).Div(0.Minus(1))"))
        assertEquals(Int.MIN_VALUE, assertIs<IntLit>(fold("0.Minus(2147483647).Minus(1)")).value)
    }

    @Test
    fun signedZeroArithmeticIsPreserved() {
        val result = fold("0.0.UnaryMinus().Mult(2)")
        val receiver = assertIs<RealLit>(assertIs<MemberAccess>(assertIs<Call>(result).callee).target)
        assertEquals(Long.MIN_VALUE, receiver.value.toRawBits())
    }

    @Test
    fun constructorsAndUserDefinedMethodsAreNotFolded() {
        assertIs<Call>(fold("Integer(5)"))
        val program = parse("""
            class A is
                method Plus(n: Integer) : Integer => 77
                method answer : Integer => this.Plus(2.Plus(3))
            end
        """.trimIndent())
        val optimized = optimizer.foldConstants(program)
        val call = assertIs<Call>(((optimized.classes.single().members.last() as MethodDecl).body as MethodBody.Arrow).expr)
        assertIs<This>(assertIs<MemberAccess>(call.callee).target)
        assertEquals(5, assertIs<IntLit>(call.args.single()).value)
    }

    @Test
    fun effectfulBooleanArgumentsAndReceiversArePreserved() {
        val program = parse("""
            class A is
                var calls : 0
                method touch : Boolean is calls := calls.Plus(1) return true end
                method answer : Boolean => false.And(this.touch())
            end
        """.trimIndent())
        val output = optimizer.foldConstants(program)
        val call = assertIs<Call>(((output.classes.single().members.last() as MethodDecl).body as MethodBody.Arrow).expr)
        assertIs<Call>(call.args.single())
        assertEquals(1, Evaluator(output).invoke("answer").second)
    }

    @Test
    fun foldingVisitsFieldsConstructorsNestedBlocksAndArrowMethods() {
        val program = parse("""
            class A is
                var field : 1.Plus(2)
                method value : Integer => 2.Mult(3)
                this(flag: Boolean) is
                    var local : 3.Plus(4)
                    if flag then local := 4.Plus(5) end
                    while flag loop return end
                end
            end
        """.trimIndent())
        val members = optimizer.foldConstants(program).classes.single().members
        assertEquals(3, assertIs<IntLit>((members[0] as VarDecl).init).value)
        assertEquals(6, assertIs<IntLit>(((members[1] as MethodDecl).body as MethodBody.Arrow).expr).value)
        val body = (members[2] as ConstructorDecl).body
        assertEquals(7, assertIs<IntLit>((body[0] as VarDecl).init).value)
        assertEquals(9, assertIs<IntLit>(((body[1] as If).thenBody.single() as Assignment).value).value)
    }

    @Test
    fun replacementLiteralKeepsTheOuterOperationsSourceLocation() {
        val program = parse("class A is\n    var value : 2.Plus(3.Mult(4))\nend")
        val before = field(program)
        val after = field(optimizer.foldConstants(program))
        assertEquals(before.line to before.column, after.line to after.column)
        assertEquals(2 to 19, after.line to after.column)
    }

    @Test
    fun constantTrueAndFalseBranchesAreSelected() {
        val input = method("var x : 0 if true then x := 10 else x := 20 end return x")
        val selected = statements(optimizer.simplifyConditionals(input))
        assertEquals(3, selected.size)
        assertEquals(10, assertIs<IntLit>(assertIs<Assignment>(selected[1]).value).value)
        val otherwise = method("var x : 0 if false then x := 10 else x := 20 end return x")
        assertEquals(20, assertIs<IntLit>(assertIs<Assignment>(statements(optimizer.simplifyConditionals(otherwise))[1]).value).value)
    }

    @Test
    fun falseConditionWithoutElseIsRemoved() {
        val output = optimizer.simplifyConditionals(method("if false then var ignored : 1 end return 4"))
        assertIs<Return>(statements(output).single())
    }

    @Test
    fun nonConstantConditionIsRetainedAndNestedConditionsAreSimplified() {
        val output = optimizer.simplifyConditionals(method("if flag then if true then return 1 else return 2 end else return 3 end", "flag: Boolean"))
        val outer = assertIs<If>(statements(output).single())
        assertIs<Name>(outer.condition)
        assertEquals(1, assertIs<IntLit>(assertIs<Return>(outer.thenBody.single()).value).value)
    }

    @Test
    fun conditionalPassIsIndependentOfFolding() {
        val program = method("if 3.Less(5) then return 1 else return 2 end")
        assertIs<Call>(assertIs<If>(statements(optimizer.simplifyConditionals(program)).single()).condition)
        val result = optimizer.optimize(program, setOf(OptimizationPass.CONSTANT_FOLDING, OptimizationPass.CONDITIONAL_SIMPLIFICATION))
        assertEquals(1, assertIs<IntLit>(assertIs<Return>(statements(result).single()).value).value)
    }

    @Test
    fun selectedBranchesWithDeclarationsKeepTheirScope() {
        val input = method("var x : 1 if true then var x : 2 x := 3 end return x")
        val output = optimizer.optimize(input)
        assertIs<If>(statements(output)[1])
        assertEquals(1, Evaluator(input).invoke("answer").first)
        assertEquals(Evaluator(input).invoke("answer"), Evaluator(output).invoke("answer"))
        assertTrue(SemanticAnalyzer().analyze(output).isSuccess)
    }

    @Test
    fun selectedElseScopeIsNormalizedWithoutLeakingItsDeclaration() {
        val input = method("var x : 1 if false then x := 2 else var x : 3 x := 4 end return x")
        val output = optimizer.simplifyConditionals(input)
        val condition = assertIs<If>(statements(output)[1])
        assertTrue(assertIs<BoolLit>(condition.condition).value)
        assertNull(condition.elseBody)
        assertIs<VarDecl>(condition.thenBody.first())
        assertEquals(output, optimizer.simplifyConditionals(output))
        assertEquals(Evaluator(input).invoke("answer"), Evaluator(output).invoke("answer"))
    }

    @Test
    fun scopeWrapperPreventsSameScopeDuplicateAfterSelection() {
        val input = method("if true then var x : 1 x := 2 end var x : 3 return x")
        val output = optimizer.optimize(input)
        assertTrue(SemanticAnalyzer().analyze(output).isSuccess)
        assertIs<If>(statements(output).first())
        assertEquals(3, Evaluator(output).invoke("answer").first)
    }

    @Test
    fun directReturnRemovesOnlyTheUnreachableSuffix() {
        val input = method("var used : 1 return used var dead : Integer(5)")
        val output = statements(optimizer.eliminateUnreachable(input))
        assertEquals(2, output.size)
        assertIs<VarDecl>(output.first())
        assertIs<Return>(output.last())
    }

    @Test
    fun twoReturningBranchesMakeFollowingStatementsUnreachable() {
        val input = method("if flag then return 1 else return 2 end var dead : 0 return 3", "flag: Boolean")
        val result = statements(optimizer.eliminateUnreachable(input))
        assertIs<If>(result.single())
    }

    @Test
    fun partialReturnAndLoopDoNotProveFollowingStatementsUnreachable() {
        val input = method("if flag then return 1 end while flag loop return 2 end return 3", "flag: Boolean")
        assertEquals(3, statements(optimizer.eliminateUnreachable(input)).size)
    }

    @Test
    fun unreachablePassRecursesIntoBranchesAndLoops() {
        val input = method("if flag then return 1 var dead : 3 else while flag loop return 2 var dead : 4 end return 5 end", "flag: Boolean")
        val result = assertIs<If>(statements(optimizer.eliminateUnreachable(input)).single())
        assertEquals(1, result.thenBody.size)
        assertEquals(1, assertIs<While>(result.elseBody!!.first()).body.size)
        assertEquals(2, result.elseBody!!.size)
    }

    @Test
    fun unreachablePassUnderstandsAConstantSelectedBranchWithScope() {
        val input = method("if true then var local : 1 return local end return 2")
        assertIs<If>(statements(optimizer.eliminateUnreachable(input)).single())
    }

    @Test
    fun unusedLiteralLocalsAreRemovedWhileFieldsAndUsedLocalsRemain() {
        val input = parse("class A is var field : 9 method answer : Integer is var dead : 1 var flag : true var real : 1.5 var used : 2 return used end end")
        val output = optimizer.eliminateUnusedVariables(input)
        assertIs<VarDecl>(output.classes.single().members.first())
        assertEquals(listOf("used"), statements(output).filterIsInstance<VarDecl>().map { it.name })
    }

    @Test
    fun unusedConstructorsCallsAndPossibleErrorsAreRetained() {
        val input = parse("""
            class A is
                method touch : Integer => 1
                method answer : Integer is
                    var constructed : Integer(1)
                    var called : this.touch()
                    var error : 5.Div(0)
                    return 2
                end
            end
        """.trimIndent())
        val output = optimizer.eliminateUnusedVariables(input)
        assertEquals(3, statements(output).filterIsInstance<VarDecl>().size)
    }

    @Test
    fun assignmentTargetsCountAsUsesEvenWhenNeverRead() {
        val output = optimizer.eliminateUnusedVariables(method("var written : 1 written := 2 return 3"))
        assertIs<VarDecl>(statements(output).first())
    }

    @Test
    fun bindingResolutionDistinguishesShadowedLocals() {
        val input = method("var x : 1 if flag then var x : 2 return x end return 3", "flag: Boolean")
        val output = optimizer.eliminateUnusedVariables(input)
        val branch = assertIs<If>(statements(output).first())
        assertIs<VarDecl>(branch.thenBody.first())
        assertEquals(2, statements(output).size)
    }

    @Test
    fun initializerOfShadowingDeclarationStillReadsOuterVariable() {
        val input = method("var x : 1 if flag then var x : x return x end return x", "flag: Boolean")
        val output = optimizer.eliminateUnusedVariables(input)
        assertIs<VarDecl>(statements(output).first())
        assertIs<VarDecl>(assertIs<If>(statements(output)[1]).thenBody.first())
    }

    @Test
    fun nestedUsesKeepOuterDeclarationsButMemberNamesDoNotCountAsUses() {
        val input = parse("""
            class A is
                var same : 7
                method answer(flag: Boolean) : Integer is
                    var same : 1
                    var outer : 2
                    if flag then return outer end
                    return this.same
                end
            end
        """.trimIndent())
        val output = optimizer.eliminateUnusedVariables(input)
        assertEquals(listOf("outer"), statements(output).filterIsInstance<VarDecl>().map { it.name })
    }

    @Test
    fun unusedLiteralLocalsAreRemovedInsideConstructorsAndLoops() {
        val input = parse("class A is this(flag: Boolean) is var dead : 1 while flag loop var alsoDead : false end end end")
        val output = optimizer.eliminateUnusedVariables(input)
        val loop = assertIs<While>((output.classes.single().members.single() as ConstructorDecl).body.single())
        assertTrue(loop.body.isEmpty())
    }

    @Test
    fun standaloneUnusedPassDoesNotFoldItsInitializers() {
        val input = method("var foldedOnlyLater : 1.Plus(2) return 4")
        assertIs<VarDecl>(statements(optimizer.eliminateUnusedVariables(input)).first())
        assertIs<Return>(statements(optimizer.optimize(input)).single())
    }

    @Test
    fun publicPassesAndPipelineRejectInvalidInputBeforeTransformation() {
        val invalid = method("if true then return 1 else return missing end")
        val before = AstPrinter.render(invalid)
        val APIs = listOf<(Program) -> Program>(optimizer::foldConstants, optimizer::simplifyConditionals,
            optimizer::eliminateUnreachable, optimizer::eliminateUnusedVariables, { optimizer.optimize(it) })
        for (api in APIs) assertFailsWith<SemanticException> { api(invalid) }
        assertEquals(before, AstPrinter.render(invalid))
    }

    @Test
    fun invalidUnreachableStatementsCannotBeHidden() {
        val input = method("return 1 var invalid : unknown")
        assertFailsWith<SemanticException> { optimizer.eliminateUnreachable(input) }
    }

    @Test
    fun emptySelectionStillValidatesAndReturnsTheOriginalTree() {
        val input = method("return 1.Plus(2)")
        assertEquals(input, optimizer.optimize(input, emptySet()))
        assertFailsWith<SemanticException> { optimizer.optimize(method("return missing"), emptySet()) }
    }

    @Test
    fun optimizerNeverMutatesTheInputAst() {
        val input = method("var disposable : 7 if 3.Less(5) then return 2.Plus(3) else return 9 end var dead : 0")
        val snapshot = parse("class A is method answer() : Integer is var disposable : 7 if 3.Less(5) then return 2.Plus(3) else return 9 end var dead : 0 end end")
        val before = AstPrinter.render(input)
        val output = optimizer.optimize(input)
        assertEquals(snapshot, input)
        assertEquals(before, AstPrinter.render(input))
        assertNotEquals(input, output)
        assertEquals(5, assertIs<IntLit>(assertIs<Return>(statements(output).single()).value).value)
    }

    @Test
    fun pipelineAndEachPassAreIdempotent() {
        val input = method("var disposable : 7 if false then return 3 else var x : 1 x := 2 return x end var dead : 0")
        val APIs = listOf<(Program) -> Program>(optimizer::foldConstants, optimizer::simplifyConditionals,
            optimizer::eliminateUnreachable, optimizer::eliminateUnusedVariables, { optimizer.optimize(it) })
        for (api in APIs) {
            val once = api(input)
            assertEquals(once, api(once))
            assertTrue(SemanticAnalyzer().analyze(once).isSuccess)
        }
    }

    @Test
    fun fixedPointRemovesAnEmptyScopeWrapperExposedByUnusedElimination() {
        val input = method("if true then var unused : 7 end return 3")
        assertIs<Return>(statements(optimizer.optimize(input)).single())
    }

    @Test
    fun scalarExecutionAndSideEffectsAgreeBeforeAndAfterAllPasses() {
        val input = parse("""
            class A is
                var calls : 0
                method touch : Integer is calls := calls.Plus(1) return 8 end
                method answer(flag: Boolean) : Integer is
                    var dead : 7
                    var effect : this.touch()
                    var x : 2.Plus(3.Mult(4))
                    if true then var x : 1 x := 99 end
                    if 3.Less(5) then x := x.Plus(1) else x := 50 end
                    if flag then return x else return effect end
                    var unreachable : this.touch()
                end
            end
        """.trimIndent())
        val output = optimizer.optimize(input)
        assertNotEquals(input, output)
        for (flag in listOf(true, false)) {
            val before = Evaluator(input).invoke("answer", listOf(flag))
            val after = Evaluator(output).invoke("answer", listOf(flag))
            assertEquals(if (flag) 15 else 8, before.first)
            assertEquals(1, before.second)
            assertEquals(before, after)
        }
    }

    /** A small test oracle for scalar examples, deliberately independent of optimizer code. */
    private class Evaluator(program: Program) {
        private val methods = program.classes.single().members.filterIsInstance<MethodDecl>().associateBy { it.name }
        private val fields = linkedMapOf<String, Any>()
        private val self = Any()

        init {
            program.classes.single().members.filterIsInstance<VarDecl>().forEach { fields[it.name] = evaluate(it.init, emptyList()) }
        }

        fun invoke(name: String, args: List<Any> = emptyList()): Pair<Any?, Int> =
            call(name, args) to (fields["calls"] as? Int ?: 0)

        private class Returned(val value: Any?) : RuntimeException()

        private fun call(name: String, args: List<Any>): Any? {
            val method = methods.getValue(name)
            val scope = method.params.mapIndexed { index, param -> param.name to args[index] }.toMap().toMutableMap()
            return when (val body = method.body) {
                is MethodBody.Arrow -> evaluate(body.expr, listOf(scope))
                is MethodBody.Block -> try {
                    execute(body.statements, listOf(scope))
                    null
                } catch (returned: Returned) { returned.value }
                null -> error("Test interpreter cannot execute a forward declaration")
            }
        }

        private fun execute(body: List<Statement>, parents: List<MutableMap<String, Any>>) {
            val scopes = parents + linkedMapOf<String, Any>()
            for (statement in body) when (statement) {
                is VarDecl -> scopes.last()[statement.name] = evaluate(statement.init, scopes)
                is Assignment -> {
                    val scope = scopes.asReversed().firstOrNull { statement.name in it } ?: fields
                    scope[statement.name] = evaluate(statement.value, scopes)
                }
                is Return -> throw Returned(statement.value?.let { evaluate(it, scopes) })
                is ExprStatement -> evaluate(statement.expr, scopes)
                is If -> execute(if (evaluate(statement.condition, scopes) as Boolean) statement.thenBody else statement.elseBody.orEmpty(), scopes)
                is While -> {
                    var iterations = 0
                    while (evaluate(statement.condition, scopes) as Boolean) {
                        check(iterations++ < 100) { "Test interpreter loop guard" }
                        execute(statement.body, scopes)
                    }
                }
            }
        }

        private fun evaluate(expr: Expr, scopes: List<MutableMap<String, Any>>): Any = when (expr) {
            is IntLit -> expr.value
            is RealLit -> expr.value
            is BoolLit -> expr.value
            is This -> self
            is Name -> scopes.asReversed().firstOrNull { expr.type.name in it }?.getValue(expr.type.name)
                ?: fields.getValue(expr.type.name)
            is MemberAccess -> {
                val target = evaluate(expr.target, scopes)
                if (target === self && expr.name in fields) fields.getValue(expr.name)
                else dispatch(target, expr.name, emptyList())
            }
            is Call -> {
                val member = expr.callee as? MemberAccess
                val target = member?.let { evaluate(it.target, scopes) } ?: self
                val name = member?.name ?: (expr.callee as Name).type.name
                val args = expr.args.map { evaluate(it, scopes) } // O's method arguments are eager.
                dispatch(target, name, args)
            }
        }

        private fun dispatch(target: Any, name: String, args: List<Any>): Any {
            if (target === self) return call(name, args) ?: Unit
            return when (target) {
                is Int -> when (name) {
                    "Plus" -> target + (args.single() as Int)
                    "Minus" -> target - (args.single() as Int)
                    "Mult" -> target * (args.single() as Int)
                    "Div" -> target / (args.single() as Int)
                    "Rem" -> target % (args.single() as Int)
                    "Less" -> target < (args.single() as Int)
                    "Equal" -> target == args.single()
                    else -> error("Unsupported test integer method $name")
                }
                is Boolean -> when (name) {
                    "Not" -> !target
                    "And" -> target && (args.single() as Boolean)
                    "Or" -> target || (args.single() as Boolean)
                    else -> error("Unsupported test boolean method $name")
                }
                else -> error("Unsupported test receiver $target")
            }
        }
    }
}
