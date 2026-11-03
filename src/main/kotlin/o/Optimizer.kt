package o

import java.math.BigDecimal
import java.util.Collections
import java.util.IdentityHashMap

/** Independently selectable passes, in their normal pipeline order. */
enum class OptimizationPass(val cliName: String) {
    CONSTANT_FOLDING("fold"),
    CONDITIONAL_SIMPLIFICATION("conditional"),
    UNREACHABLE_CODE("unreachable"),
    UNUSED_VARIABLES("unused")
}

/**
 * Immutable, conservative AST transformations. Every public entry point validates
 * the whole original program first, including unreachable and discarded branches.
 * An optimizer must never hide a semantic error by deleting its source.
 */
class Optimizer {
    fun optimize(
        program: Program,
        passes: Set<OptimizationPass> = OptimizationPass.entries.toSet()
    ): Program {
        validate(program)
        var current = program
        do {
            val previous = current
            for (pass in OptimizationPass.entries) {
                if (pass !in passes) continue
                current = when (pass) {
                    OptimizationPass.CONSTANT_FOLDING -> foldUnchecked(current)
                    OptimizationPass.CONDITIONAL_SIMPLIFICATION -> conditionalUnchecked(current)
                    OptimizationPass.UNREACHABLE_CODE -> unreachableUnchecked(current)
                    OptimizationPass.UNUSED_VARIABLES -> unusedUnchecked(current)
                }
            }
            // All rewrites remove expressions/statements/branches, except the
            // one-time normalization of a selected else branch to `if true`.
            // Repeating exposes opportunities between passes and is idempotent.
        } while (current != previous)
        return current
    }

    fun foldConstants(program: Program): Program = validated(program, ::foldUnchecked)

    fun simplifyConditionals(program: Program): Program = validated(program, ::conditionalUnchecked)

    fun eliminateUnreachable(program: Program): Program = validated(program, ::unreachableUnchecked)

    fun eliminateUnusedVariables(program: Program): Program = validated(program, ::unusedUnchecked)

    private fun validate(program: Program) {
        SemanticAnalyzer().analyze(program).requireSuccess()
    }

    private fun validated(program: Program, transform: (Program) -> Program): Program {
        validate(program)
        return transform(program)
    }

    // ------------------------------------------------------- constant folding

    private fun foldUnchecked(program: Program): Program = mapProgram(program, ::foldExpression)

    private fun foldExpression(expr: Expr): Expr = when (expr) {
        is Call -> {
            val args = expr.args.map(::foldExpression)
            val member = expr.callee as? MemberAccess
            if (member != null) {
                // A callee member must not first be folded as a *bare* zero-arg
                // invocation: true.Not() would otherwise become Call(false, []).
                val target = foldExpression(member.target)
                evaluate(target, member.name, args, expr)
                    ?: expr.copy(callee = member.copy(target = target), args = args)
            } else {
                expr.copy(callee = foldExpression(expr.callee), args = args)
            }
        }
        is MemberAccess -> {
            val target = foldExpression(expr.target)
            evaluate(target, expr.name, emptyList(), expr) ?: expr.copy(target = target)
        }
        is IntLit, is RealLit, is BoolLit, is Name, is This -> expr
    }

    /** Only exact primitive literal receivers have statically known dispatch. */
    private fun evaluate(target: Expr, name: String, args: List<Expr>, at: Node): Expr? = when (target) {
        is IntLit -> evaluateInteger(target.value, name, args, at)
        is RealLit -> evaluateReal(target.value, name, args, at)
        is BoolLit -> evaluateBoolean(target.value, name, args, at)
        else -> null
    }

    private fun evaluateInteger(value: Int, name: String, args: List<Expr>, at: Node): Expr? {
        if (args.isEmpty()) {
            return when (name) {
                "UnaryMinus" -> integer(-value.toLong(), at)
                "toReal" -> RealLit(value.toDouble(), at.line, at.column)
                // Integer.toBoolean and Real.toInteger have unspecified
                // conversion details in the hand-out, so are left to runtime.
                else -> null
            }
        }
        val argument = args.singleOrNull() ?: return null
        if (argument is RealLit) return evaluateNumeric(value.toDouble(), name, argument.value, at)
        if (argument !is IntLit) return null
        val right = argument.value
        return when (name) {
            "Plus" -> integer(value.toLong() + right, at)
            "Minus" -> integer(value.toLong() - right, at)
            "Mult" -> integer(value.toLong() * right, at)
            "Div" -> if (right == 0) null else integer(value.toLong() / right, at)
            "Rem" -> if (right == 0) null else IntLit(value % right, at.line, at.column)
            "Less" -> boolean(value < right, at)
            "LessEqual" -> boolean(value <= right, at)
            "Greater" -> boolean(value > right, at)
            "GreaterEqual" -> boolean(value >= right, at)
            "Equal" -> boolean(value == right, at)
            else -> null
        }
    }

    private fun evaluateReal(value: Double, name: String, args: List<Expr>, at: Node): Expr? {
        if (!value.isFinite()) return null
        if (args.isEmpty()) {
            return if (name == "UnaryMinus") RealLit(-value, at.line, at.column) else null
        }
        val argument = args.singleOrNull() ?: return null
        val right = when (argument) {
            is RealLit -> argument.value
            is IntLit -> argument.value.toDouble()
            else -> return null
        }
        return evaluateNumeric(value, name, right, at)
    }

    /**
     * The existing AST stores Reals as Double, but the specification does not
     * choose a rounding policy. Fold only finite operations whose mathematical
     * result is exactly representable in that storage; retain rounded operations,
     * non-finite values, zero divisors, and signed-zero arithmetic.
     */
    private fun evaluateNumeric(left: Double, name: String, right: Double, at: Node): Expr? {
        if (!left.isFinite() || !right.isFinite()) return null
        when (name) {
            "Less" -> return boolean(left < right, at)
            "LessEqual" -> return boolean(left <= right, at)
            "Greater" -> return boolean(left > right, at)
            "GreaterEqual" -> return boolean(left >= right, at)
            "Equal" -> return boolean(left == right, at)
        }
        if (left.toRawBits() == Long.MIN_VALUE || right.toRawBits() == Long.MIN_VALUE) return null
        val exactLeft = BigDecimal(left)
        val exactRight = BigDecimal(right)
        val exact = try {
            when (name) {
                "Plus" -> exactLeft.add(exactRight)
                "Minus" -> exactLeft.subtract(exactRight)
                "Mult" -> exactLeft.multiply(exactRight)
                "Div" -> if (right == 0.0) return null else exactLeft.divide(exactRight)
                "Rem" -> if (right == 0.0) return null else exactLeft.remainder(exactRight)
                else -> return null
            }
        } catch (_: ArithmeticException) {
            // An exact division with a non-terminating decimal expansion.
            return null
        }
        val result = when (name) {
            "Plus" -> left + right
            "Minus" -> left - right
            "Mult" -> left * right
            "Div" -> left / right
            "Rem" -> left % right
            else -> return null
        }
        if (!result.isFinite() || result.toRawBits() == Long.MIN_VALUE) return null
        return if (BigDecimal(result).compareTo(exact) == 0) RealLit(result, at.line, at.column) else null
    }

    private fun evaluateBoolean(value: Boolean, name: String, args: List<Expr>, at: Node): Expr? {
        if (args.isEmpty()) return if (name == "Not") boolean(!value, at) else null
        val right = (args.singleOrNull() as? BoolLit)?.value ?: return null
        return when (name) {
            "And" -> boolean(value && right, at)
            "Or" -> boolean(value || right, at)
            "Xor" -> boolean(value xor right, at)
            else -> null
        }
    }

    private fun integer(value: Long, at: Node): IntLit? =
        if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) IntLit(value.toInt(), at.line, at.column)
        else null // Preserve runtime overflow behavior, whatever backend policy is chosen.

    private fun boolean(value: Boolean, at: Node) = BoolLit(value, at.line, at.column)

    // -------------------------------------------------- conditional selection

    private fun conditionalUnchecked(program: Program): Program = mapProgram(program, block = { body ->
        body.flatMap { statement ->
            val condition = (statement as? If)?.condition as? BoolLit
            if (statement !is If || condition == null) {
                listOf(statement)
            } else {
                val selected = if (condition.value) statement.thenBody else statement.elseBody.orEmpty()
                if (selected.none { it is VarDecl }) {
                    selected
                } else {
                    // The AST has no standalone scoped block. Keep a canonical
                    // true wrapper rather than changing a local's binding or
                    // leaking it into the enclosing scope by flattening.
                    listOf(statement.copy(
                        condition = condition.copy(value = true), thenBody = selected, elseBody = null
                    ))
                }
            }
        }
    })

    // ----------------------------------------------------- unreachable suffix

    private fun unreachableUnchecked(program: Program): Program = mapProgram(program, block = { body ->
        val result = mutableListOf<Statement>()
        for (statement in body) {
            result.add(statement)
            if (definitelyReturns(statement)) break
        }
        result
    })

    private fun definitelyReturns(statement: Statement): Boolean = when (statement) {
        is Return -> true
        is If -> when (val condition = statement.condition) {
            is BoolLit -> bodyReturns(if (condition.value) statement.thenBody else statement.elseBody.orEmpty())
            else -> statement.elseBody != null && bodyReturns(statement.thenBody) && bodyReturns(statement.elseBody)
        }
        // Even a literal-true loop is deliberately not used as a proof here.
        is While, is VarDecl, is Assignment, is ExprStatement -> false
    }

    private fun bodyReturns(body: List<Statement>): Boolean = body.any(::definitelyReturns)

    // ---------------------------------------------- unused literal local only

    private fun unusedUnchecked(program: Program): Program = program.copy(classes = program.classes.map { cls ->
        cls.copy(members = cls.members.map { member ->
            when (member) {
                is MethodDecl -> {
                    val body = member.body
                    if (body is MethodBody.Block) {
                        member.copy(body = MethodBody.Block(removeUnusedLocals(body.statements)))
                    } else member
                }
                is ConstructorDecl -> member.copy(body = removeUnusedLocals(member.body))
                is VarDecl -> member // Fields are externally observable and never removed.
            }
        })
    })

    private fun removeUnusedLocals(body: List<Statement>): List<Statement> {
        // Identity distinguishes declarations even if a caller builds structurally
        // equal nodes. Resolving lexical bindings also distinguishes shadowed names.
        val used = Collections.newSetFromMap(IdentityHashMap<VarDecl, Boolean>())
        val scopes = mutableListOf<MutableMap<String, VarDecl>>()

        fun reference(name: String) {
            for (scope in scopes.asReversed()) {
                val declaration = scope[name]
                if (declaration != null) {
                    used.add(declaration)
                    return
                }
            }
        }

        fun expression(expr: Expr) {
            when (expr) {
                is Name -> reference(expr.type.name)
                is MemberAccess -> expression(expr.target)
                is Call -> {
                    expression(expr.callee)
                    expr.args.forEach(::expression)
                }
                is IntLit, is RealLit, is BoolLit, is This -> Unit
            }
        }

        fun collect(statements: List<Statement>) {
            scopes.add(linkedMapOf())
            for (statement in statements) {
                when (statement) {
                    is VarDecl -> {
                        expression(statement.init) // The new name is not in scope in its initializer.
                        scopes.last()[statement.name] = statement
                    }
                    is Assignment -> {
                        reference(statement.name) // Write-only variables are conservatively retained.
                        expression(statement.value)
                    }
                    is Return -> statement.value?.let(::expression)
                    is ExprStatement -> expression(statement.expr)
                    is If -> {
                        expression(statement.condition)
                        collect(statement.thenBody)
                        statement.elseBody?.let(::collect)
                    }
                    is While -> {
                        expression(statement.condition)
                        collect(statement.body)
                    }
                }
            }
            scopes.removeAt(scopes.lastIndex)
        }

        collect(body)

        fun remove(statements: List<Statement>): List<Statement> = statements.mapNotNull { statement ->
            when (statement) {
                is VarDecl -> if (statement !in used && literal(statement.init)) null else statement
                is If -> statement.copy(thenBody = remove(statement.thenBody), elseBody = statement.elseBody?.let(::remove))
                is While -> statement.copy(body = remove(statement.body))
                is Assignment, is Return, is ExprStatement -> statement
            }
        }

        return remove(body)
    }

    private fun literal(expr: Expr): Boolean = expr is IntLit || expr is RealLit || expr is BoolLit

    // -------------------------------------------------- common reconstruction

    private fun mapProgram(
        program: Program,
        expression: (Expr) -> Expr = { it },
        block: (List<Statement>) -> List<Statement> = { it }
    ): Program {
        fun body(statements: List<Statement>): List<Statement> = block(statements.map { statement ->
            when (statement) {
                is VarDecl -> statement.copy(init = expression(statement.init))
                is Assignment -> statement.copy(value = expression(statement.value))
                is Return -> statement.copy(value = statement.value?.let(expression))
                is ExprStatement -> statement.copy(expr = expression(statement.expr))
                is While -> statement.copy(condition = expression(statement.condition), body = body(statement.body))
                is If -> statement.copy(
                    condition = expression(statement.condition), thenBody = body(statement.thenBody),
                    elseBody = statement.elseBody?.let(::body)
                )
            }
        })

        return program.copy(classes = program.classes.map { cls ->
            cls.copy(members = cls.members.map { member ->
                when (member) {
                    is VarDecl -> member.copy(init = expression(member.init))
                    is ConstructorDecl -> member.copy(body = body(member.body))
                    is MethodDecl -> member.copy(body = when (val methodBody = member.body) {
                        null -> null
                        is MethodBody.Arrow -> MethodBody.Arrow(expression(methodBody.expr))
                        is MethodBody.Block -> MethodBody.Block(body(methodBody.statements))
                    })
                }
            })
        })
    }
}
