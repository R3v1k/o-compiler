package o

import java.util.IdentityHashMap

/** A resolved nominal type. Collection arguments are invariant. */
data class OType(val name: String, val arguments: List<OType> = emptyList()) {
    override fun toString(): String = name +
        if (arguments.isEmpty()) "" else arguments.joinToString(", ", "[", "]")

    companion object {
        val INTEGER = OType("Integer")
        val REAL = OType("Real")
        val BOOLEAN = OType("Boolean")
        val VOID = OType("<no value>")
        /** Recovery only: every introduction of this type has an accompanying error. */
        internal val ERROR = OType("<error>")
    }
}

data class SemanticDiagnostic(val message: String, val line: Int, val column: Int) {
    override fun toString(): String = "Semantic error at $line:$column: $message"
}

class SemanticException(val diagnostics: List<SemanticDiagnostic>) :
    Exception(diagnostics.joinToString("\n"))

data class SemanticResult(val diagnostics: List<SemanticDiagnostic>, val model: SemanticModel) {
    val isSuccess: Boolean get() = diagnostics.isEmpty()

    fun requireSuccess(): SemanticResult {
        if (!isSuccess) throw SemanticException(diagnostics)
        return this
    }
}

data class VariableSymbol(val name: String, val type: OType, val declaration: Node)

/** Each block owns its names; lookup walks outward, while declaration checks only this block. */
class Scope(val parent: Scope? = null) {
    private val variables = linkedMapOf<String, VariableSymbol>()

    fun declare(symbol: VariableSymbol): Boolean {
        if (symbol.name in variables) return false
        variables[symbol.name] = symbol
        return true
    }

    fun lookup(name: String): VariableSymbol? = variables[name] ?: parent?.lookup(name)
    fun lookupLocal(name: String): VariableSymbol? = variables[name]
    fun child(): Scope = Scope(this)
}

/** Read-only analysis information kept outside the original AST. Expressions use identity keys. */
class SemanticModel internal constructor(
    expressionTypes: Map<Expr, OType>,
    private val baseClasses: Map<String, String?>
) {
    private val types = IdentityHashMap<Expr, OType>().apply { putAll(expressionTypes) }

    fun typeOf(expression: Expr): OType? = types[expression]

    /** No implicit Integer/Real conversion; primitive conversions are library calls. */
    fun isAssignable(expected: OType, actual: OType): Boolean {
        if (expected == OType.ERROR || actual == OType.ERROR) return true
        if (expected == actual) return true
        if (expected == OType.VOID || actual == OType.VOID) return false
        if (expected.arguments.isNotEmpty()) return false
        val seen = mutableSetOf<String>()
        var current: String? = actual.name
        while (current != null && seen.add(current)) {
            if (current == expected.name) return true
            current = baseClasses[current]
        }
        return false
    }
}

internal data class MethodSymbol(
    val name: String,
    val parameters: List<OType>,
    val returnType: OType,
    val declaration: MethodDecl? = null
) {
    val signature: String get() = "$name(${parameters.joinToString()})"
}

internal data class ConstructorSymbol(
    val parameters: List<OType>,
    val declaration: ConstructorDecl? = null
)

internal class FieldSymbol(val owner: ClassSymbol, val declaration: VarDecl, val index: Int) {
    var type: OType? = null
    var resolving: Boolean = false
}

internal class ClassSymbol(
    val name: String,
    var baseName: String?,
    val declaration: ClassDecl? = null,
    val genericArity: Int = 0
) {
    val fields = linkedMapOf<String, FieldSymbol>()
    val methods = mutableListOf<MethodSymbol>()
    val constructors = mutableListOf<ConstructorSymbol>()
}
