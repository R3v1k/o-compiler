package o

/**
 * Abstract syntax tree of an O program, as built by [Parser].
 *
 * The tree records *structure only*. It does not know what a name refers to:
 * `x` may be a variable, a parameter or a class, and `Foo(1)` may be a
 * constructor call or an unqualified method call. Those questions need the
 * declarations of the whole program and are answered by semantic analysis,
 * which walks this tree.
 *
 * Every node keeps the [line] and [column] of the token it was built from, so
 * that semantic errors ("undefined variable 'x' at 12:9") can point back into
 * the source exactly as lexical and syntax errors do. As a rule this is the
 * node's first token; [MemberAccess] is the single exception, see there.
 */
sealed class Node {
    abstract val line: Int
    abstract val column: Int
}

// ------------------------------------------------------------------ program

/** The whole file: a sequence of class declarations and nothing else. */
data class Program(
    val classes: List<ClassDecl>,
    override val line: Int,
    override val column: Int
) : Node()

/**
 * `class Name [ extends Base ] is ... end`.
 *
 * [baseName] is a plain string because the grammar allows only an identifier
 * after `extends`, never a generic type such as `Array[Integer]`.
 */
data class ClassDecl(
    val name: String,
    val baseName: String?,
    val members: List<Member>,
    override val line: Int,
    override val column: Int
) : Node()

/** Anything that may appear directly inside a class body. */
sealed interface Member

// ------------------------------------------------------------------ members

/**
 * `method name(params) : Type` followed by one of the two body forms, or by
 * nothing at all.
 *
 * A `null` [body] is a forward declaration: the header alone, completed by a
 * full declaration with the same signature later in the class.
 */
data class MethodDecl(
    val name: String,
    val params: List<Param>,
    val returnType: TypeRef?,
    val body: MethodBody?,
    override val line: Int,
    override val column: Int
) : Node(), Member

/**
 * The two spellings of a method body. They are kept apart rather than turning
 * `=> e` into `is return e end`, so that the tree still matches the source
 * and the printed AST shows what the programmer actually wrote.
 */
sealed class MethodBody {
    /** `is Body end` */
    data class Block(val statements: List<Statement>) : MethodBody()

    /** `=> Expression` */
    data class Arrow(val expr: Expr) : MethodBody()
}

/** `this(params) is Body end`. */
data class ConstructorDecl(
    val params: List<Param>,
    val body: List<Statement>,
    override val line: Int,
    override val column: Int
) : Node(), Member

/** `name : Type` inside a parameter list. */
data class Param(
    val name: String,
    val type: TypeRef,
    override val line: Int,
    override val column: Int
) : Node()

/**
 * A class name as used in a type position, with optional generic arguments:
 * `Integer`, `Array[Integer]`, `List[Array[Real]]`.
 *
 * Generic *declarations* are out of scope, but the library classes `Array` and
 * `List` are generic, so their *uses* must be representable.
 */
data class TypeRef(
    val name: String,
    val typeArgs: List<TypeRef>,
    override val line: Int,
    override val column: Int
) : Node()

// --------------------------------------------------------------- statements

/**
 * Anything that may appear inside a method or constructor body.
 *
 * The specification writes `Body : { VariableDeclaration | Statement }`, i.e.
 * a declaration is not itself a statement. Here [VarDecl] is made a
 * [Statement] anyway, so that a body is one list in source order: semantic
 * analysis needs that order, because a local variable is in scope only after
 * its declaration.
 */
sealed class Statement : Node()

/**
 * `var name : init` — both a class member (a field) and a body item (a local
 * variable). The parser does not distinguish the two; the position in the
 * tree does.
 */
data class VarDecl(
    val name: String,
    val init: Expr,
    override val line: Int,
    override val column: Int
) : Statement(), Member

/** `name := value`. The target is a bare identifier, exactly as in the grammar. */
data class Assignment(
    val name: String,
    val value: Expr,
    override val line: Int,
    override val column: Int
) : Statement()

/** `while condition loop body end`. */
data class While(
    val condition: Expr,
    val body: List<Statement>,
    override val line: Int,
    override val column: Int
) : Statement()

/**
 * `if condition then thenBody [ else elseBody ] end`.
 *
 * [elseBody] is `null` when there is no `else` at all, and an empty list for
 * `else end`; the two mean the same thing, but the tree keeps what was written.
 */
data class If(
    val condition: Expr,
    val thenBody: List<Statement>,
    val elseBody: List<Statement>?,
    override val line: Int,
    override val column: Int
) : Statement()

/** `return [ value ]`. */
data class Return(
    val value: Expr?,
    override val line: Int,
    override val column: Int
) : Statement()

/**
 * An expression used as a statement, e.g. `a.set(i, v)`.
 *
 * EXTENSION: the specification's `Statement` has no such alternative, which
 * makes a call to a method that returns nothing impossible to write. See
 * "Conventions chosen" in `examples/README.md`.
 */
data class ExprStatement(
    val expr: Expr,
    override val line: Int,
    override val column: Int
) : Statement()

// -------------------------------------------------------------- expressions

sealed class Expr : Node()

data class IntLit(
    val value: Int,
    override val line: Int,
    override val column: Int
) : Expr()

data class RealLit(
    val value: Double,
    override val line: Int,
    override val column: Int
) : Expr()

data class BoolLit(
    val value: Boolean,
    override val line: Int,
    override val column: Int
) : Expr()

/** The keyword `this` used as a value: the current object. */
data class This(
    override val line: Int,
    override val column: Int
) : Expr()

/**
 * A bare name in an expression: `x`, `Integer`, `Array[Integer]`.
 *
 * Deliberately neutral. Whether `x` is a local, a parameter, a field or a
 * class is decided by semantic analysis. The name is stored as a [TypeRef]
 * because, when it does turn out to be a class, it may carry generic
 * arguments, and semantic analysis can then resolve it like any other type.
 */
data class Name(
    val type: TypeRef,
    override val line: Int,
    override val column: Int
) : Expr()

/**
 * `target.name`, without arguments: `a.Length`, `counter.toReal`.
 *
 * The position is that of [name], not of the first token of [target]. In a
 * chain such as `a.get(i).Greater(max)` the first token of every node is `a`;
 * an error like "Integer has no method 'Greater'" must point at `Greater`.
 */
data class MemberAccess(
    val target: Expr,
    val name: String,
    override val line: Int,
    override val column: Int
) : Expr()

/**
 * A call: [callee] followed by an argument list.
 *
 * `a.get(1)` is `Call(MemberAccess(Name a, "get"), [1])`; `Integer(0)` is
 * `Call(Name Integer, [0])`. Whether the latter constructs an object or calls
 * an unqualified method named `Integer` is again a semantic question. The
 * position is that of the callee, so `a.get(1)` sits at `get`.
 */
data class Call(
    val callee: Expr,
    val args: List<Expr>,
    override val line: Int,
    override val column: Int
) : Expr()
