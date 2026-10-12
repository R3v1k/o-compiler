package o

/**
 * Renders an AST as an indented tree in the style of the `tree` command,
 * with the `line:column` of every node in a column on the right.
 *
 * Printing happens in two steps. First the AST is converted into a small
 * generic [Item] tree, where every node is just a label and a list of
 * children; this is the only part that knows the node types. Then [render]
 * draws the `├─ └─ │` connectors for any [Item] tree, without knowing what
 * the labels mean. Keeping the two apart means a new node type needs one new
 * branch in a `when`, and the drawing code never changes.
 */
object AstPrinter {

    /**
     * One printed row and its children. [node] supplies the position; it is
     * `null` for grouping rows such as `then` and `else`, which exist in the
     * printout but not in the AST.
     */
    private class Item(
        val label: String,
        val node: Node?,
        val children: List<Item> = emptyList()
    )

    /** The tree as a list of lines, ready to be printed one by one. */
    fun render(program: Program): List<String> {
        val rows = mutableListOf<Pair<String, String>>()
        collect(programItem(program), "", "", rows)

        // Align the positions into one column, after the longest tree line.
        val width = rows.maxOf { it.first.length }
        return rows.map { (text, position) ->
            if (position.isEmpty()) text else text.padEnd(width) + "  " + position
        }
    }

    /**
     * Depth-first walk that draws the connectors. [prefix] goes in front of
     * this item's own row; [childPrefix] goes in front of every row below
     * it, and carries a `│` down for as long as the parent has more children.
     */
    private fun collect(
        item: Item,
        prefix: String,
        childPrefix: String,
        rows: MutableList<Pair<String, String>>
    ) {
        val position = if (item.node == null) "" else "${item.node.line}:${item.node.column}"
        rows.add(Pair(prefix + item.label, position))

        for ((index, child) in item.children.withIndex()) {
            val isLast = index == item.children.lastIndex
            collect(
                child,
                childPrefix + (if (isLast) "└─ " else "├─ "),
                childPrefix + (if (isLast) "   " else "│  "),
                rows
            )
        }
    }

    // ------------------------------------------------------- AST -> Item

    private fun programItem(program: Program): Item =
        Item("Program", program, program.classes.map { classItem(it) })

    private fun classItem(decl: ClassDecl): Item {
        val header = if (decl.baseName == null) {
            "Class ${decl.name}"
        } else {
            "Class ${decl.name} extends ${decl.baseName}"
        }
        return Item(header, decl, decl.members.map { memberItem(it) })
    }

    private fun memberItem(member: Member): Item = when (member) {
        is VarDecl -> statementItem(member)
        is MethodDecl -> methodItem(member)
        is ConstructorDecl -> Item(
            "Constructor" + params(member.params),
            member,
            member.body.map { statementItem(it) }
        )
    }

    /**
     * The signature goes into the label, written the way it is in the
     * source, so that a method takes one row rather than one per parameter.
     */
    private fun methodItem(decl: MethodDecl): Item {
        var header = "Method ${decl.name}" + params(decl.params)
        if (decl.returnType != null) {
            header += " : " + typeName(decl.returnType)
        }

        return when (val body = decl.body) {
            null -> Item("$header  (forward declaration)", decl)
            is MethodBody.Block -> Item(header, decl, body.statements.map { statementItem(it) })
            is MethodBody.Arrow -> Item(header, decl, listOf(exprItem(body.expr, "=> ")))
        }
    }

    private fun statementItem(statement: Statement): Item = when (statement) {
        is VarDecl -> Item("Var ${statement.name}", statement, listOf(exprItem(statement.init)))

        is Assignment -> Item(
            "Assign ${statement.name} :=",
            statement,
            listOf(exprItem(statement.value))
        )

        is While -> Item(
            "While",
            statement,
            listOf(exprItem(statement.condition, "cond: "), blockItem("loop", statement.body))
        )

        is If -> {
            val children = mutableListOf(
                exprItem(statement.condition, "cond: "),
                blockItem("then", statement.thenBody)
            )
            if (statement.elseBody != null) {
                children.add(blockItem("else", statement.elseBody))
            }
            Item("If", statement, children)
        }

        is Return -> Item(
            "Return",
            statement,
            if (statement.value == null) emptyList() else listOf(exprItem(statement.value))
        )

        is ExprStatement -> Item("ExprStatement", statement, listOf(exprItem(statement.expr)))
    }

    private fun blockItem(label: String, statements: List<Statement>): Item =
        Item(label, null, statements.map { statementItem(it) })

    /** [role] says what the expression is to its parent, e.g. `cond: ` or `arg: `. */
    private fun exprItem(expr: Expr, role: String = ""): Item = when (expr) {
        is IntLit -> Item(role + "IntLit ${expr.value}", expr)
        is RealLit -> Item(role + "RealLit ${expr.value}", expr)
        is BoolLit -> Item(role + "BoolLit ${expr.value}", expr)
        is This -> Item(role + "This", expr)
        is Name -> Item(role + "Name " + typeName(expr.type), expr)
        is MemberAccess -> Item(role + "MemberAccess .${expr.name}", expr, listOf(exprItem(expr.target)))
        is Call -> Item(
            role + "Call",
            expr,
            listOf(exprItem(expr.callee, "callee: ")) + expr.args.map { exprItem(it, "arg: ") }
        )
    }

    // ------------------------------------------------------------ helpers

    /** `(a: Integer, b: Array[Real])`, or nothing for an empty list. */
    private fun params(params: List<Param>): String {
        if (params.isEmpty()) {
            return ""
        }
        return params.joinToString(", ", "(", ")") { "${it.name}: ${typeName(it.type)}" }
    }

    /** `Integer`, `Array[Integer]`, `List[Array[Real]]`. */
    private fun typeName(type: TypeRef): String {
        if (type.typeArgs.isEmpty()) {
            return type.name
        }
        return type.name + type.typeArgs.joinToString(", ", "[", "]") { typeName(it) }
    }
}
