package o

import java.io.File
import java.io.IOException
import java.io.PrintStream
import kotlin.system.exitProcess

private const val RULE_WIDTH = 68

/** CLI entry point; [runCompiler] is independently testable without exiting the JVM. */
fun main(args: Array<String>) {
    exitProcess(runCompiler(args))
}

/** Returns 0 on success, 1 on input/compilation errors, and 2 on usage errors. */
fun runCompiler(args: Array<String>, out: PrintStream = System.out): Int = CompilerCli(out).run(args)

private data class CliOptions(
    val path: String,
    val tokens: Boolean,
    val analyze: Boolean,
    val optimize: Boolean,
    val before: Boolean,
    val passes: Set<OptimizationPass>
)

private class CompilerCli(private val out: PrintStream) {
    fun run(args: Array<String>): Int {
        if (args.size == 1 && args[0] in setOf("--help", "-h")) {
            printUsage()
            return 0
        }
        val options = parseOptions(args) ?: return 2
        val file = File(options.path)
        if (!file.isFile) {
            out.println("  ERROR  file not found: ${file.path}")
            return 1
        }
        val source = try {
            file.readText()
        } catch (e: IOException) {
            out.println("  ERROR  cannot read ${file.path}: ${e.message}")
            return 1
        }
        val title = when {
            options.tokens -> "O LANGUAGE LEXER"
            options.optimize -> "O LANGUAGE AST OPTIMIZER"
            options.analyze -> "O LANGUAGE SEMANTIC ANALYZER"
            else -> "O LANGUAGE PARSER"
        }
        printHeader(title, file)
        try {
            val tokens = Lexer(source).tokenize()
            if (options.tokens) {
                printTokenTable(tokens)
                return 0
            }
            val original = Parser(tokens).parseProgram()
            if (options.before) {
                out.println("  AST BEFORE")
                printTree(original)
            }
            if (options.analyze || options.optimize) {
                val analysis = SemanticAnalyzer().analyze(original)
                if (!analysis.isSuccess) {
                    for (diagnostic in analysis.diagnostics) {
                        printSourceError("SEMANTIC ERROR", file, source,
                            diagnostic.line, diagnostic.column, diagnostic.message)
                    }
                    return 1
                }
                out.println("  Semantic analysis successful.")
                out.println()
            }
            if (options.optimize) {
                val optimized = Optimizer().optimize(original, options.passes)
                out.println("  Passes: " + options.passes.joinToString(", ") { it.cliName })
                out.println("  AST AFTER")
                printTree(optimized)
            } else if (!options.analyze && !options.before) {
                printTree(original)
            }
            return 0
        } catch (e: LexerException) {
            printSourceError("LEXICAL ERROR", file, source, e.line, e.column, e.message)
        } catch (e: ParseException) {
            printSourceError("SYNTAX ERROR", file, source, e.line, e.column, e.message)
        }
        return 1
    }

    private fun parseOptions(args: Array<String>): CliOptions? {
        val flags = mutableSetOf<String>()
        val paths = mutableListOf<String>()
        var requestedPasses: Set<OptimizationPass>? = null
        for (arg in args) {
            when {
                arg in setOf("--tokens", "--analyze", "--optimize", "--ast-before", "--ast-after") -> flags.add(arg)
                arg.startsWith("--passes=") -> {
                    if (requestedPasses != null) return usageError("--passes may be provided once")
                    val names = arg.substringAfter('=').split(',')
                    val known = OptimizationPass.entries.associateBy { it.cliName }
                    if (names.any { it !in known }) {
                        return usageError("unknown or empty optimization pass; choose ${known.keys.joinToString(", ")}")
                    }
                    requestedPasses = OptimizationPass.entries.filter { it.cliName in names }.toSet()
                }
                arg.startsWith('-') -> return usageError("unknown option '$arg'")
                else -> paths.add(arg)
            }
        }
        if (paths.size != 1) return usageError("provide exactly one source file")
        if ("--tokens" in flags && (flags.size > 1 || requestedPasses != null)) {
            return usageError("--tokens must be used on its own with a source file")
        }
        val optimize = "--optimize" in flags || "--ast-after" in flags
        if (requestedPasses != null && !optimize) return usageError("--passes requires --optimize or --ast-after")
        return CliOptions(paths.single(), "--tokens" in flags, "--analyze" in flags, optimize,
            "--ast-before" in flags,
            requestedPasses ?: OptimizationPass.entries.toSet())
    }

    private fun usageError(message: String): CliOptions? {
        out.println("  ERROR  $message")
        printUsage()
        return null
    }

    private fun printUsage() {
        out.println()
        out.println("  O language compiler front end (lexer, parser, semantic analysis, AST optimization)")
        out.println("  usage: o-compiler [options] <file.o>")
        out.println("    --tokens       print tokens (standalone mode)")
        out.println("    --analyze      validate symbols, types, overloads, returns and inheritance")
        out.println("    --optimize     validate and run all optimization passes; print optimized AST")
        out.println("    --ast-before   print original AST (default when no analysis mode is selected)")
        out.println("    --ast-after    validate, optimize and print optimized AST")
        out.println("    --passes=fold,conditional,unreachable,unused  select optimization passes")
        out.println("    --help, -h     show this help")
        out.println()
        out.println("  ./gradlew run --args=\"--analyze examples/semantic_valid.o\"")
        out.println("  ./gradlew run --args=\"--optimize --ast-before examples/optimization_constant_folding.o\"")
        out.println()
    }

    private fun printHeader(title: String, file: File) {
        out.println()
        out.println("=".repeat(RULE_WIDTH))
        out.println("  $title")
        out.println("  source: ${file.path}")
        out.println("=".repeat(RULE_WIDTH))
        out.println()
    }

    private fun printTokenTable(tokens: List<Token>) {
        val rows = tokens.map { token ->
            Triple(
                token.type.name,
                if (token.type == TokenType.EOF) "<eof>" else token.lexeme,
                "${token.line}:${token.column}"
            )
        }

        val typeWidth = maxOf(rows.maxOf { it.first.length }, "TYPE".length)
        val lexemeWidth = maxOf(rows.maxOf { it.second.length }, "LEXEME".length)
        val posWidth = maxOf(rows.maxOf { it.third.length }, "LINE:COLUMN".length)

        // Column layout: 2 spaces, TYPE, 2 spaces, '|', 1 space, LEXEME, ...
        val separator = "  " + "-".repeat(typeWidth + 2) + "+" +
            "-".repeat(lexemeWidth + 3) + "+" + "-".repeat(posWidth + 1)

        out.println(
            "  " + "TYPE".padEnd(typeWidth) + "  | " +
                "LEXEME".padEnd(lexemeWidth) + "  | " + "LINE:COLUMN"
        )
        out.println(separator)
        for ((type, lexeme, position) in rows) {
            out.println(
                "  " + type.padEnd(typeWidth) + "  | " +
                    lexeme.padEnd(lexemeWidth) + "  | " + position
            )
        }
        out.println(separator)
        out.println()
        out.println("  ${tokens.size} tokens")
        out.println()
    }

    private fun printTree(program: Program) {
        for (line in AstPrinter.render(program)) {
            out.println("  $line")
        }
        out.println()
        val count = program.classes.size
        out.println("  $count " + if (count == 1) "class" else "classes")
        out.println()
    }

    /**
     * Shared by all three diagnostic stages; every error carries a source position.
     */
    private fun printSourceError(kind: String, file: File, source: String, line: Int, column: Int, message: String?) {
        val lines = source.split("\n")
        val gutter = line.toString().length + 2

        out.println("  $kind  ${file.path}:$line:$column")
        out.println()

        if (line - 1 in lines.indices) {
            val raw = lines[line - 1].trimEnd('\r')
            // Tabs are one column for the lexer; expand them the same way in the
            // excerpt and in the caret prefix so the caret stays under the char.
            val text = raw.replace("\t", "    ")
            val caretOffset = raw.take(column - 1).replace("\t", "    ").length

            out.println("  " + line.toString().padStart(gutter) + " | " + text)
            out.println("  " + " ".repeat(gutter) + " | " + " ".repeat(caretOffset) + "^")
        }

        out.println()
        out.println("  $message")
        out.println()
    }
}
