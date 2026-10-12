package o

import java.io.File
import kotlin.system.exitProcess

private const val RULE_WIDTH = 68

/**
 * Command line driver: lexes and parses one file.
 *
 * ```
 * ./gradlew run --args="examples/08_polymorphism.o"            # AST tree
 * ./gradlew run --args="--tokens examples/03_tricky.o"         # token table
 * ```
 *
 * Prints the syntax tree on success (or, with `--tokens`, the token table of
 * phase 1). On a lexical or syntax error prints the offending source line
 * with a caret under it and exits with status 1.
 */
fun main(args: Array<String>) {
    val tokensOnly = args.isNotEmpty() && args[0] == "--tokens"
    val paths = if (tokensOnly) args.drop(1) else args.toList()
    if (paths.size != 1 || paths[0].startsWith("--")) {
        printUsage()
        exitProcess(2)
    }

    val file = File(paths[0])
    if (!file.isFile) {
        println()
        println("  ERROR  file not found: ${file.path}")
        println("         (paths are resolved from the project root: ${File(".").absoluteFile.parentFile})")
        println()
        exitProcess(1)
    }

    val source = file.readText()
    printHeader(if (tokensOnly) "O LANGUAGE LEXER" else "O LANGUAGE PARSER", file)

    try {
        val tokens = Lexer(source).tokenize()
        if (tokensOnly) {
            printTokenTable(tokens)
        } else {
            printTree(Parser(tokens).parseProgram())
        }
        exitProcess(0)
    } catch (e: LexerException) {
        printSourceError("LEXICAL ERROR", file, source, e.line, e.column, e.message)
        exitProcess(1)
    } catch (e: ParseException) {
        printSourceError("SYNTAX ERROR", file, source, e.line, e.column, e.message)
        exitProcess(1)
    }
}

private fun printUsage() {
    println()
    println("  O language compiler front end (lexer + parser)")
    println()
    println("  usage:  ./gradlew run --args=\"<file.o>\"            print the syntax tree")
    println("          ./gradlew run --args=\"--tokens <file.o>\"   print the token table")
    println("  e.g.    ./gradlew run --args=\"examples/08_polymorphism.o\"")
    println()
}

private fun printHeader(title: String, file: File) {
    println()
    println("=".repeat(RULE_WIDTH))
    println("  $title")
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

private fun printTree(program: Program) {
    for (line in AstPrinter.render(program)) {
        println("  $line")
    }
    println()
    val count = program.classes.size
    println("  $count " + if (count == 1) "class" else "classes")
    println()
}

/**
 * Shared by lexical and syntax errors: both exceptions carry a line and a
 * column, so one printer serves every phase that reports a source position.
 */
private fun printSourceError(kind: String, file: File, source: String, line: Int, column: Int, message: String?) {
    val lines = source.split("\n")
    val gutter = line.toString().length + 2

    println("  $kind  ${file.path}:$line:$column")
    println()

    if (line - 1 in lines.indices) {
        val raw = lines[line - 1].trimEnd('\r')
        // Tabs are one column for the lexer; expand them the same way in the
        // excerpt and in the caret prefix so the caret stays under the char.
        val text = raw.replace("\t", "    ")
        val caretOffset = raw.take(column - 1).replace("\t", "    ").length

        println("  " + line.toString().padStart(gutter) + " | " + text)
        println("  " + " ".repeat(gutter) + " | " + " ".repeat(caretOffset) + "^")
    }

    println()
    println("  $message")
    println()
}
