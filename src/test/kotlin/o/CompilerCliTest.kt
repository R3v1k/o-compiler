package o

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerCliTest {
    private fun invoke(vararg args: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val code = PrintStream(buffer, true, Charsets.UTF_8).use { runCompiler(arrayOf(*args), it) }
        return code to buffer.toString(Charsets.UTF_8)
    }

    private fun source(text: String, action: (String) -> Unit) {
        val file = File.createTempFile("o-compiler-cli-", ".o")
        try {
            file.writeText(text)
            action(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyDefaultPrintsTreeWithoutEnforcingSemantics() = source("class A is var x : missing end") { path ->
        val (code, output) = invoke(path)
        assertEquals(0, code)
        assertTrue("O LANGUAGE PARSER" in output)
        assertTrue("Name missing" in output)
        assertFalse("SEMANTIC ERROR" in output)
    }

    @Test
    fun legacyTokenModeDoesNotParse() = source("end class") { path ->
        val (code, output) = invoke("--tokens", path)
        assertEquals(0, code)
        assertTrue("O LANGUAGE LEXER" in output)
        assertTrue("LEXEME" in output)
        assertTrue("EOF" in output)
    }

    @Test
    fun helpAndBadUsageHaveDistinctStatuses() {
        for (flag in listOf("--help", "-h")) {
            val (code, output) = invoke(flag)
            assertEquals(0, code)
            assertTrue("--analyze" in output)
            assertTrue("--passes=" in output)
        }
        for (args in listOf(emptyArray(), arrayOf("--unknown"), arrayOf("a.o", "b.o"))) {
            val (code, output) = invoke(*args)
            assertEquals(2, code)
            assertTrue("usage:" in output)
        }
    }

    @Test
    fun incompatibleAndMalformedFlagsAreUsageErrors() {
        for (args in listOf(
            arrayOf("--tokens", "--analyze", "a.o"),
            arrayOf("--passes=fold", "a.o"),
            arrayOf("--optimize", "--passes=", "a.o"),
            arrayOf("--optimize", "--passes=unknown", "a.o"),
            arrayOf("--optimize", "--passes=fold", "--passes=unused", "a.o")
        )) assertEquals(2, invoke(*args).first)
    }

    @Test
    fun missingSourceIsAnInputError() {
        val (code, output) = invoke("examples/no-such-source-file.o")
        assertEquals(1, code)
        assertTrue("file not found" in output)
    }

    @Test
    fun lexicalAndSyntaxFailuresUseSourceDiagnostics() {
        source("class A is @ end") { path ->
            val (code, output) = invoke("--analyze", path)
            assertEquals(1, code)
            assertTrue("LEXICAL ERROR" in output)
            assertTrue("$path:1:12" in output)
            assertTrue("^" in output)
        }
        source("class A is") { path ->
            val (code, output) = invoke("--optimize", path)
            assertEquals(1, code)
            assertTrue("SYNTAX ERROR" in output)
            assertFalse("AST AFTER" in output)
        }
    }

    @Test
    fun semanticFailurePreventsOptimization() = source("class A is method f : Integer => missing end") { path ->
        val (code, output) = invoke("--optimize", "--ast-before", path)
        assertEquals(1, code)
        assertTrue("AST BEFORE" in output)
        assertTrue("SEMANTIC ERROR" in output)
        assertTrue("missing" in output)
        assertFalse("AST AFTER" in output)
        assertFalse("Exception" in output)
    }

    @Test
    fun multipleSemanticDiagnosticsArePrinted() = source(
        "class A is method f is first := 1 second := 2 end end"
    ) { path ->
        val (code, output) = invoke(path, "--analyze")
        assertEquals(1, code)
        assertTrue("first" in output)
        assertTrue("second" in output)
        assertEquals(2, Regex("SEMANTIC ERROR").findAll(output).count())
    }

    @Test
    fun validAnalysisCanPrintOriginalAst() = source("class A is method f : Integer => 2.Plus(3) end") { path ->
        val (code, output) = invoke(path, "--ast-before", "--analyze")
        assertEquals(0, code)
        assertTrue("Semantic analysis successful" in output)
        assertTrue("AST BEFORE" in output)
        assertTrue("MemberAccess .Plus" in output)
        assertFalse("AST AFTER" in output)
    }

    @Test
    fun analyzeOnlyDoesNotPrintAst() = source("class A is end") { path ->
        val (code, output) = invoke("--analyze", path)
        assertEquals(0, code)
        assertTrue("Semantic analysis successful" in output)
        assertFalse("Class A" in output)
    }

    @Test
    fun optimizationShowsRealBeforeAndAfterTrees() = source("class A is method f : Integer => 2.Plus(3.Mult(4)) end") { path ->
        val (code, output) = invoke("--optimize", path, "--ast-before")
        assertEquals(0, code)
        assertTrue("MemberAccess .Plus" in output.substringBefore("AST AFTER"))
        val after = output.substringAfter("AST AFTER")
        assertTrue("IntLit 14" in after)
        assertFalse("MemberAccess .Plus" in after)
    }

    @Test
    fun astAfterImpliesOptimization() = source("class A is method f : Boolean => true.Not() end") { path ->
        val (code, output) = invoke(path, "--ast-after")
        assertEquals(0, code)
        assertTrue("BoolLit false" in output)
        assertFalse("AST BEFORE" in output)
    }

    @Test
    fun passSelectionDoesNotRunUnselectedFolding() = source("class A is method f : Integer => 2.Plus(3) end") { path ->
        val (code, output) = invoke("--optimize", "--passes=unused", path)
        assertEquals(0, code)
        assertTrue("Passes: unused" in output)
        assertTrue("MemberAccess .Plus" in output)
        assertFalse("IntLit 5" in output)
    }

    @Test
    fun invalidDeadCodeIsStillValidatedBeforeElimination() = source(
        "class A is method f : Integer is return 1 var x : missing end end"
    ) { path ->
        val (code, output) = invoke("--optimize", path)
        assertEquals(1, code)
        assertTrue("missing" in output)
        assertFalse("AST AFTER" in output)
    }
}
