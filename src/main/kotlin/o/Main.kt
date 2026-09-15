package o

import java.io.File
import kotlin.system.exitProcess

private const val RULE_WIDTH = 68

/**
 * Command line driver for the lexer.
 *
 * ```
 * ./gradlew run --args="examples/03_tricky.o"
 * ```
 *
 * Prints the token table on success; on a lexical error prints the offending
 * source line with a caret under it and exits with status 1.
 */
fun main(args: Array<String>) {
    if (args.size != 1) {
        printUsage()
        exitProcess(2)
    }

    val file = File(args[0])
    if (!file.isFile) {
        println()
        println("  ERROR  file not found: ${file.path}")
        println("         (paths are resolved from the project root: ${File(".").absoluteFile.parentFile})")
        println()
        exitProcess(1)
    }

    val source = file.readText()
    printHeader(file)

    try {
        val tokens = Lexer(source).tokenize()
        printTokenTable(tokens)
        exitProcess(0)
    } catch (e: LexerException) {
        printLexicalError(file, source, e)
        exitProcess(1)
    }
}

private fun printUsage() {
    println()
    println("  O language lexer")
    println()
    println("  usage:  ./gradlew run --args=\"<file.o>\"")
    println("  e.g.    ./gradlew run --args=\"examples/03_tricky.o\"")
    println()
}

private fun printHeader(file: File) {
    println()
    println("=".repeat(RULE_WIDTH))
    println("  O LANGUAGE LEXER")
    println("  source: ${file.path}")
    println("=".repeat(RULE_WIDTH))
    println()
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

    println(
        "  " + "TYPE".padEnd(typeWidth) + "  | " +
            "LEXEME".padEnd(lexemeWidth) + "  | " + "LINE:COLUMN"
    )
    println(separator)
    for ((type, lexeme, position) in rows) {
        println(
            "  " + type.padEnd(typeWidth) + "  | " +
                lexeme.padEnd(lexemeWidth) + "  | " + position
        )
    }
    println(separator)
    println()
    println("  ${tokens.size} tokens")
    println()
}

private fun printLexicalError(file: File, source: String, e: LexerException) {
    val lines = source.split("\n")
    val gutter = e.line.toString().length + 2

    println("  LEXICAL ERROR  ${file.path}:${e.line}:${e.column}")
    println()

    if (e.line - 1 in lines.indices) {
        val raw = lines[e.line - 1].trimEnd('\r')
        // Tabs are one column for the lexer; expand them the same way in the
        // excerpt and in the caret prefix so the caret stays under the char.
        val text = raw.replace("\t", "    ")
        val caretOffset = raw.take(e.column - 1).replace("\t", "    ").length

        println("  " + e.line.toString().padStart(gutter) + " | " + text)
        println("  " + " ".repeat(gutter) + " | " + " ".repeat(caretOffset) + "^")
    }

    println()
    println("  ${e.message}")
    println()
}
