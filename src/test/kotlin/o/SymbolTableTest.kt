package o

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SymbolTableTest {
    private fun variable(name: String, type: OType = OType.INTEGER) =
        VariableSymbol(name, type, IntLit(0, 1, 1))

    @Test fun declarationAndLookup() {
        val scope = Scope()
        val symbol = variable("value")
        assertTrue(scope.declare(symbol))
        assertSame(symbol, scope.lookup("value"))
        assertNull(scope.lookup("missing"))
    }

    @Test fun duplicatesDoNotReplaceOriginal() {
        val scope = Scope()
        val original = variable("value")
        scope.declare(original)
        assertFalse(scope.declare(variable("value", OType.BOOLEAN)))
        assertSame(original, scope.lookup("value"))
    }

    @Test fun childSeesParentButParentCannotSeeChild() {
        val parent = Scope()
        parent.declare(variable("outer"))
        val child = parent.child()
        child.declare(variable("inner"))
        assertEquals(OType.INTEGER, child.lookup("outer")?.type)
        assertNull(parent.lookup("inner"))
        assertNull(child.lookupLocal("outer"))
    }

    @Test fun nearestDeclarationWinsWithoutChangingParent() {
        val parent = Scope()
        parent.declare(variable("x"))
        val child = parent.child()
        assertTrue(child.declare(variable("x", OType.REAL)))
        assertEquals(OType.REAL, child.child().lookup("x")?.type)
        assertEquals(OType.INTEGER, parent.lookup("x")?.type)
    }

    @Test fun siblingsAreIndependent() {
        val parent = Scope()
        parent.child().declare(variable("x"))
        assertNull(parent.child().lookup("x"))
    }

    @Test fun typeRenderingIncludesNestedArguments() {
        assertEquals("List[Array[Integer]]", OType("List", listOf(OType("Array", listOf(OType.INTEGER)))).toString())
    }
}
