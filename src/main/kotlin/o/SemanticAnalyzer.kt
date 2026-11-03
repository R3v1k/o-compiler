package o

import java.util.IdentityHashMap

/**
 * Non-mutating semantic analysis. Each invocation builds a fresh declaration graph,
 * resolves signatures before bodies, and records inferred types in a separate model.
 */
class SemanticAnalyzer {
    fun analyze(program: Program): SemanticResult = Analysis(program).run()

    private class Analysis(private val program: Program) {
        private val diagnostics = mutableListOf<SemanticDiagnostic>()
        private val classes = Builtins.classes()
        private val expressionTypes = IdentityHashMap<Expr, OType>()
        private val methodSymbols = IdentityHashMap<MethodDecl, MethodSymbol>()
        private val constructorSymbols = IdentityHashMap<ConstructorDecl, ConstructorSymbol>()
        private val typeRelations by lazy { SemanticModel(emptyMap(), classes.mapValues { it.value.baseName }) }

        private data class Context(
            val owner: ClassSymbol,
            val scope: Scope,
            val returnType: OType = OType.VOID,
            val constructor: Boolean = false,
            val initializingField: FieldSymbol? = null
        )

        fun run(): SemanticResult {
            collectClasses()
            validateInheritance()
            collectMembers()
            validateOverrides()
            for (owner in classes.values.filter { it.declaration != null }) {
                owner.fields.values.forEach { inferField(it) }
                for (member in owner.declaration!!.members) {
                    when (member) {
                        is MethodDecl -> analyzeMethod(owner, member)
                        is ConstructorDecl -> analyzeConstructor(owner, member)
                        is VarDecl -> Unit
                    }
                }
            }
            val model = model()
            return SemanticResult(diagnostics.distinct().sortedWith(
                compareBy<SemanticDiagnostic> { it.line }.thenBy { it.column }
            ), model)
        }

        private fun model() = SemanticModel(expressionTypes, classes.mapValues { it.value.baseName })

        private fun error(node: Node, message: String) {
            diagnostics += SemanticDiagnostic(message, node.line, node.column)
        }

        private fun collectClasses() {
            for (declaration in program.classes) {
                if (declaration.name in classes) {
                    val builtinNote = if (classes.getValue(declaration.name).declaration == null)
                        " (predefined classes cannot be redefined)" else ""
                    error(declaration, "Duplicate class declaration '${declaration.name}'$builtinNote")
                } else {
                    classes[declaration.name] = ClassSymbol(
                        declaration.name, declaration.baseName ?: "AnyRef", declaration
                    )
                }
            }
        }

        private fun validateInheritance() {
            for (symbol in classes.values.filter { it.declaration != null }) {
                val base = classes[symbol.baseName]
                when {
                    base == null -> {
                        error(symbol.declaration!!, "Unknown base class '${symbol.baseName}'")
                        symbol.baseName = null
                    }
                    base.genericArity != 0 -> {
                        error(symbol.declaration!!, "Base class '${base.name}' requires type arguments; generic inheritance is unsupported")
                        symbol.baseName = null
                    }
                }
            }
            val state = mutableMapOf<String, Int>()
            fun visit(symbol: ClassSymbol) {
                if (state[symbol.name] == 2) return
                if (state[symbol.name] == 1) {
                    symbol.declaration?.let { error(it, "Inheritance cycle involving class '${symbol.name}'") }
                    return
                }
                state[symbol.name] = 1
                symbol.baseName?.let { classes[it]?.let(::visit) }
                state[symbol.name] = 2
            }
            classes.values.forEach(::visit)
        }

        private fun resolveType(reference: TypeRef): OType {
            val arguments = reference.typeArgs.map(::resolveType)
            val symbol = classes[reference.name]
            if (symbol == null) {
                error(reference, "Unknown type '${reference.name}'")
                return OType.ERROR
            }
            if (symbol.genericArity != arguments.size) {
                error(reference, "Type '${reference.name}' expects ${symbol.genericArity} type argument(s), found ${arguments.size}")
                return OType.ERROR
            }
            if (OType.ERROR in arguments) return OType.ERROR
            return OType(reference.name, arguments)
        }

        private fun collectMembers() {
            for (owner in classes.values.filter { it.declaration != null }) {
                val methods = linkedMapOf<Pair<String, List<OType>>, MutableList<MethodSymbol>>()
                for ((index, member) in owner.declaration!!.members.withIndex()) {
                    when (member) {
                        is VarDecl -> {
                            if (member.name in owner.fields) error(member, "Duplicate variable declaration '${member.name}' in class '${owner.name}'")
                            else owner.fields[member.name] = FieldSymbol(owner, member, index)
                        }
                        is MethodDecl -> {
                            val symbol = MethodSymbol(
                                member.name, member.params.map { resolveType(it.type) },
                                member.returnType?.let(::resolveType) ?: OType.VOID, member
                            )
                            methodSymbols[member] = symbol
                            methods.getOrPut(symbol.name to symbol.parameters) { mutableListOf() } += symbol
                        }
                        is ConstructorDecl -> {
                            val symbol = ConstructorSymbol(member.params.map { resolveType(it.type) }, member)
                            constructorSymbols[member] = symbol
                            if (owner.constructors.any { it.parameters == symbol.parameters }) {
                                error(member, "Duplicate constructor signature '${owner.name}(${symbol.parameters.joinToString()})'")
                            } else owner.constructors += symbol
                        }
                    }
                }
                for (group in methods.values) {
                    val forwards = group.filter { it.declaration!!.body == null }
                    val implementations = group.filter { it.declaration!!.body != null }
                    forwards.drop(1).forEach { error(it.declaration!!, "Duplicate forward declaration '${it.signature}'") }
                    implementations.drop(1).forEach { error(it.declaration!!, "Duplicate method signature '${it.signature}'") }
                    if (implementations.isEmpty()) {
                        forwards.firstOrNull()?.let { error(it.declaration!!, "Unresolved forward declaration '${it.signature}'") }
                    }
                    val canonical = implementations.firstOrNull() ?: group.first()
                    for (symbol in group) {
                        if (symbol.returnType != canonical.returnType) {
                            error(symbol.declaration!!, "Conflicting return types for method '${symbol.signature}'")
                        }
                    }
                    owner.methods += canonical
                }
                if (owner.constructors.isEmpty()) owner.constructors += ConstructorSymbol(emptyList())
            }
        }

        private fun lineage(type: OType): List<ClassSymbol> {
            val result = mutableListOf<ClassSymbol>()
            val seen = mutableSetOf<String>()
            var symbol = classes[type.name]
            while (symbol != null && seen.add(symbol.name)) {
                result += symbol
                symbol = classes[symbol.baseName]
            }
            return result
        }

        private fun methods(type: OType, name: String): List<MethodSymbol> {
            val seen = mutableSetOf<List<OType>>()
            return lineage(type).flatMap { it.methods }.filter { it.name == name }.map {
                it.copy(parameters = it.parameters.map { parameter -> Builtins.specialize(parameter, type) },
                    returnType = Builtins.specialize(it.returnType, type))
            }.filter { seen.add(it.parameters) }
        }

        private fun field(type: OType, name: String): FieldSymbol? =
            lineage(type).firstNotNullOfOrNull { it.fields[name] }

        private fun validateOverrides() {
            for (owner in classes.values.filter { it.declaration != null }) {
                val base = owner.baseName?.let(::OType) ?: continue
                for (method in owner.methods) {
                    val overridden = methods(base, method.name).find { it.parameters == method.parameters } ?: continue
                    if (!typeRelations.isAssignable(overridden.returnType, method.returnType)) {
                        error(method.declaration!!, "Override '${method.signature}' returns ${method.returnType}, expected ${overridden.returnType} or a subtype")
                    }
                }
            }
        }

        private fun inferField(symbol: FieldSymbol): OType {
            symbol.type?.let { return it }
            if (symbol.resolving) {
                error(symbol.declaration, "Cyclic initializer for field '${symbol.declaration.name}'")
                return OType.ERROR
            }
            symbol.resolving = true
            val type = requireValue(symbol.declaration.init,
                Context(symbol.owner, Scope(), initializingField = symbol))
            symbol.resolving = false
            symbol.type = type
            return type
        }

        private fun fieldType(symbol: FieldSymbol, node: Node, context: Context): OType {
            val initializing = context.initializingField
            if (initializing != null && symbol.owner === initializing.owner && symbol.index >= initializing.index) {
                error(node, "Field '${symbol.declaration.name}' is used before its initializer")
                return OType.ERROR
            }
            return inferField(symbol)
        }

        private fun parameters(params: List<Param>, types: List<OType>, scope: Scope) {
            for ((parameter, type) in params.zip(types)) {
                if (!scope.declare(VariableSymbol(parameter.name, type, parameter))) {
                    error(parameter, "Duplicate parameter '${parameter.name}'")
                }
            }
        }

        private fun analyzeMethod(owner: ClassSymbol, declaration: MethodDecl) {
            val symbol = methodSymbols[declaration] ?: return
            val scope = Scope()
            parameters(declaration.params, symbol.parameters, scope)
            val context = Context(owner, scope, symbol.returnType)
            when (val body = declaration.body) {
                null -> Unit
                is MethodBody.Arrow -> {
                    val type = infer(body.expr, context)
                    checkReturn(type, symbol.returnType, body.expr, constructor = false)
                }
                is MethodBody.Block -> {
                    analyzeBody(body.statements, context)
                    if (symbol.returnType != OType.VOID && symbol.returnType != OType.ERROR && !alwaysReturns(body.statements)) {
                        error(declaration, "Method '${declaration.name}' may complete without returning ${symbol.returnType}")
                    }
                }
            }
        }

        private fun analyzeConstructor(owner: ClassSymbol, declaration: ConstructorDecl) {
            val symbol = constructorSymbols[declaration] ?: return
            val scope = Scope()
            parameters(declaration.params, symbol.parameters, scope)
            analyzeBody(declaration.body, Context(owner, scope, constructor = true))
        }

        private fun analyzeBody(body: List<Statement>, context: Context) {
            for (statement in body) {
                when (statement) {
                    is VarDecl -> {
                        val type = requireValue(statement.init, context)
                        if (!context.scope.declare(VariableSymbol(statement.name, type, statement))) {
                            error(statement, "Duplicate variable declaration '${statement.name}' in the same scope")
                        }
                    }
                    is Assignment -> {
                        val variable = context.scope.lookup(statement.name)
                        val member = if (variable == null) field(OType(context.owner.name), statement.name) else null
                        val expected = variable?.type ?: member?.let { fieldType(it, statement, context) }
                        val actual = requireValue(statement.value, context)
                        if (expected == null) error(statement, "Undefined variable '${statement.name}'")
                        else if (!typeRelations.isAssignable(expected, actual)) {
                            error(statement, "Cannot assign $actual to variable '${statement.name}' of type $expected")
                        }
                    }
                    is If -> {
                        condition(statement.condition, context)
                        analyzeBody(statement.thenBody, context.copy(scope = context.scope.child()))
                        statement.elseBody?.let { analyzeBody(it, context.copy(scope = context.scope.child())) }
                    }
                    is While -> {
                        condition(statement.condition, context)
                        analyzeBody(statement.body, context.copy(scope = context.scope.child()))
                    }
                    is Return -> {
                        val actual = statement.value?.let { infer(it, context) } ?: OType.VOID
                        if (statement.value != null && context.returnType == OType.VOID) {
                            error(statement, if (context.constructor) "Constructor cannot return a value" else "Method without a return type cannot return a value")
                        } else checkReturn(actual, context.returnType, statement, context.constructor)
                    }
                    is ExprStatement -> infer(statement.expr, context)
                }
            }
        }

        private fun condition(expression: Expr, context: Context) {
            val actual = infer(expression, context)
            if (!typeRelations.isAssignable(OType.BOOLEAN, actual)) {
                error(expression, "Expected Boolean condition, found $actual")
            }
        }

        private fun checkReturn(actual: OType, expected: OType, node: Node, constructor: Boolean) {
            if (!typeRelations.isAssignable(expected, actual)) {
                when {
                    constructor -> error(node, "Constructor cannot return a value")
                    expected == OType.VOID -> error(node, "Method without a return type cannot return a value of type $actual")
                    actual == OType.VOID -> error(node, "Return requires a value of type $expected")
                    else -> error(node, "Return type mismatch: expected $expected, found $actual")
                }
            }
        }

        /** Loops are deliberately excluded: termination is not inferred from syntax. */
        private fun alwaysReturns(statements: List<Statement>): Boolean = statements.any { statement ->
            when (statement) {
                is Return -> true
                is If -> when ((statement.condition as? BoolLit)?.value) {
                    true -> alwaysReturns(statement.thenBody)
                    false -> statement.elseBody?.let(::alwaysReturns) ?: false
                    null -> alwaysReturns(statement.thenBody) && statement.elseBody?.let(::alwaysReturns) == true
                }
                else -> false
            }
        }

        private fun requireValue(expression: Expr, context: Context): OType {
            val type = infer(expression, context)
            if (type == OType.VOID) {
                error(expression, "Expression must produce a value")
                return OType.ERROR
            }
            return type
        }

        private fun infer(expression: Expr, context: Context): OType {
            val type = when (expression) {
                is IntLit -> OType.INTEGER
                is RealLit -> OType.REAL
                is BoolLit -> OType.BOOLEAN
                is This -> OType(context.owner.name)
                is Name -> inferName(expression, context)
                is MemberAccess -> inferMember(expression, emptyList(), false, context)
                is Call -> inferCall(expression, context)
            }
            expressionTypes[expression] = type
            return type
        }

        private fun inferName(name: Name, context: Context): OType {
            val identifier = name.type.name
            if (name.type.typeArgs.isEmpty()) {
                context.scope.lookup(identifier)?.let { return it.type }
                field(OType(context.owner.name), identifier)?.let { return fieldType(it, name, context) }
                if (methods(OType(context.owner.name), identifier).isNotEmpty()) {
                    return resolveMethod(OType(context.owner.name), identifier, emptyList(), name)
                }
            }
            if (identifier in classes) {
                resolveType(name.type)
                error(name, "Class '$identifier' cannot be used as a value; use a constructor call '$identifier(...)'")
            } else error(name, "Undefined variable '$identifier'")
            return OType.ERROR
        }

        private fun inferCall(call: Call, context: Context): OType {
            val arguments = call.args.map { requireValue(it, context) }
            return when (val callee = call.callee) {
                is MemberAccess -> inferMember(callee, arguments, true, context)
                is Name -> {
                    val identifier = callee.type.name
                    if (callee.type.typeArgs.isEmpty() &&
                        (context.scope.lookup(identifier) != null || field(OType(context.owner.name), identifier) != null)) {
                        error(callee, "Variable '$identifier' is not callable")
                        OType.ERROR
                    } else if (callee.type.typeArgs.isEmpty() && methods(OType(context.owner.name), identifier).isNotEmpty()) {
                        resolveMethod(OType(context.owner.name), identifier, arguments, call)
                    } else if (identifier in classes) {
                        val type = resolveType(callee.type)
                        if (type == OType.ERROR || OType.ERROR in arguments) OType.ERROR
                        else {
                            val candidates = classes.getValue(identifier).constructors.map { constructor ->
                                constructor.parameters.map { Builtins.specialize(it, type) }
                            }
                            resolveOverload("Constructor '$type'", candidates, arguments, call)
                            type
                        }
                    } else {
                        error(callee, "Undefined method or class '$identifier'")
                        OType.ERROR
                    }
                }
                else -> {
                    infer(callee, context)
                    error(callee, "Expression is not callable")
                    OType.ERROR
                }
            }
        }

        private fun inferMember(access: MemberAccess, arguments: List<OType>, called: Boolean, context: Context): OType {
            val className = access.target as? Name
            if (className != null && className.type.typeArgs.isEmpty() &&
                context.scope.lookup(className.type.name) == null && field(OType(context.owner.name), className.type.name) == null &&
                methods(OType(context.owner.name), className.type.name).isEmpty()) {
                Builtins.constant(className.type.name, access.name)?.let { type ->
                    expressionTypes[className] = OType(className.type.name)
                    if (called) {
                        error(access, "Field '${access.name}' is not callable")
                        return OType.ERROR
                    }
                    return type
                }
            }
            val receiver = infer(access.target, context)
            if (receiver == OType.ERROR) return OType.ERROR
            if (receiver == OType.VOID) {
                error(access, "Cannot access member '${access.name}' on an expression without a value")
                return OType.ERROR
            }
            if (!called) {
                field(receiver, access.name)?.let { return fieldType(it, access, context) }
                lineage(receiver).firstNotNullOfOrNull { Builtins.constant(it.name, access.name) }?.let { return it }
            }
            return resolveMethod(receiver, access.name, arguments, access)
        }

        private fun resolveMethod(receiver: OType, name: String, arguments: List<OType>, node: Node): OType {
            if (OType.ERROR in arguments) return OType.ERROR
            val candidates = methods(receiver, name)
            if (candidates.isEmpty()) {
                error(node, "Undefined method '$name' on type $receiver")
                return OType.ERROR
            }
            val index = resolveOverload("Method '$name' on type $receiver", candidates.map { it.parameters }, arguments, node)
                ?: return OType.ERROR
            return candidates[index].returnType
        }

        /** Choose the unique most-specific applicable signature, never declaration order. */
        private fun resolveOverload(label: String, candidates: List<List<OType>>, arguments: List<OType>, node: Node): Int? {
            val model = typeRelations
            val applicable = candidates.indices.filter { index ->
                candidates[index].size == arguments.size &&
                    candidates[index].zip(arguments).all { (expected, actual) -> model.isAssignable(expected, actual) }
            }
            if (applicable.isEmpty()) {
                error(node, "$label has no matching overload for arguments (${arguments.joinToString()})")
                return null
            }
            val best = applicable.filter { candidate ->
                applicable.none { other -> other != candidate &&
                    candidates[other] != candidates[candidate] &&
                    candidates[candidate].zip(candidates[other]).all { (wider, narrower) -> model.isAssignable(wider, narrower) }
                }
            }
            if (best.size != 1) {
                error(node, "$label is ambiguous for arguments (${arguments.joinToString()})")
                return null
            }
            return best.single()
        }
    }
}
