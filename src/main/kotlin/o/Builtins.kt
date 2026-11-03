package o

/** Signatures transcribed from specification section 2; there is no arbitrary method fallback. */
internal object Builtins {
    private val element = OType("<element>")

    fun classes(): LinkedHashMap<String, ClassSymbol> {
        val classes = linkedMapOf<String, ClassSymbol>()
        fun klass(name: String, base: String?, arity: Int = 0): ClassSymbol =
            ClassSymbol(name, base, genericArity = arity).also { classes[name] = it }

        klass("Class", null)
        klass("AnyValue", "Class")
        klass("AnyRef", "Class")
        for (name in listOf("Integer", "Real")) {
            val symbol = klass(name, "AnyValue")
            val ownType = OType(name)
            // Retain the Phase 2 Integer() fixture; zero means the conventional default value.
            symbol.constructors += ConstructorSymbol(emptyList())
            symbol.constructors += ConstructorSymbol(listOf(OType.INTEGER))
            symbol.constructors += ConstructorSymbol(listOf(OType.REAL))
            symbol.methods += MethodSymbol("UnaryMinus", emptyList(), ownType)
            for (operation in listOf("Plus", "Minus", "Mult", "Div")) {
                for (argument in listOf(OType.INTEGER, OType.REAL)) {
                    val result = if (ownType == OType.REAL || argument == OType.REAL) OType.REAL else OType.INTEGER
                    symbol.methods += MethodSymbol(operation, listOf(argument), result)
                }
            }
            symbol.methods += MethodSymbol("Rem", listOf(OType.INTEGER), ownType)
            for (relation in listOf("Less", "LessEqual", "Greater", "GreaterEqual", "Equal")) {
                for (argument in listOf(OType.INTEGER, OType.REAL)) {
                    symbol.methods += MethodSymbol(relation, listOf(argument), OType.BOOLEAN)
                }
            }
        }
        classes.getValue("Integer").methods += listOf(
            MethodSymbol("toReal", emptyList(), OType.REAL),
            MethodSymbol("toBoolean", emptyList(), OType.BOOLEAN)
        )
        classes.getValue("Real").methods += MethodSymbol("toInteger", emptyList(), OType.INTEGER)
        klass("Boolean", "AnyValue").apply {
            constructors += ConstructorSymbol(emptyList())
            constructors += ConstructorSymbol(listOf(OType.BOOLEAN))
            methods += MethodSymbol("toInteger", emptyList(), OType.INTEGER)
            methods += MethodSymbol("Not", emptyList(), OType.BOOLEAN)
            for (operation in listOf("And", "Or", "Xor")) {
                methods += MethodSymbol(operation, listOf(OType.BOOLEAN), OType.BOOLEAN)
            }
        }
        klass("Array", "AnyRef", 1).apply {
            constructors += ConstructorSymbol(listOf(OType.INTEGER))
            methods += MethodSymbol("toList", emptyList(), OType("List", listOf(element)))
            methods += MethodSymbol("Length", emptyList(), OType.INTEGER)
            methods += MethodSymbol("get", listOf(OType.INTEGER), element)
            methods += MethodSymbol("set", listOf(OType.INTEGER, element), OType.VOID)
        }
        klass("List", "AnyRef", 1).apply {
            constructors += ConstructorSymbol(emptyList())
            constructors += ConstructorSymbol(listOf(element))
            constructors += ConstructorSymbol(listOf(element, OType.INTEGER))
            methods += MethodSymbol("append", listOf(element), OType("List", listOf(element)))
            methods += MethodSymbol("head", emptyList(), element)
            methods += MethodSymbol("tail", emptyList(), OType("List", listOf(element)))
        }
        for (name in listOf("Class", "AnyValue", "AnyRef")) {
            classes.getValue(name).constructors += ConstructorSymbol(emptyList())
        }
        return classes
    }

    fun specialize(type: OType, receiver: OType): OType = when {
        type == element -> receiver.arguments.singleOrNull() ?: OType.ERROR
        type.arguments.isEmpty() -> type
        else -> type.copy(arguments = type.arguments.map { specialize(it, receiver) })
    }

    fun constant(receiver: String, name: String): OType? = when {
        receiver == "Integer" && name in setOf("Min", "Max") -> OType.INTEGER
        receiver == "Real" && name in setOf("Min", "Max", "Epsilon") -> OType.REAL
        else -> null
    }
}
