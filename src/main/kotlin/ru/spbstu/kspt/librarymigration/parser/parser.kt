package ru.spbstu.kspt.librarymigration.parser

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelBaseVisitor
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelLexer
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelParser

/**
 * Created by artyom on 13.07.17.
 */
class ModelParser {
    fun parse() {
        val stream = javaClass.classLoader.getResourceAsStream("OkHttp.lsl")
        val charStream = CharStreams.fromStream(stream)
        val lexer = LibraryModelLexer(charStream)
        val tokenStream = CommonTokenStream(lexer)
        val parser = LibraryModelParser(tokenStream)

        val start = parser.start()
        println("Hello World!")

        val library = LibraryModelReader().visit(start)
        println("Hello World!")
    }
}

class LibraryModelReader : LibraryModelBaseVisitor<Node>() {
    override fun visitStart(ctx: LibraryModelParser.StartContext): Node {
        val libraryName = ctx.libraryName().Identifier().text
        val automata = ctx.description().automatonDescription().visit().unpack<Automaton>()
        val typeList = ctx.description().typesSection().visit().unpack<Type>()
        return Library(name = libraryName, automata = automata, types = typeList,
                converters = listOf(), functions = listOf())
    }

    override fun visitAutomatonDescription(ctx: LibraryModelParser.AutomatonDescriptionContext): Node {
        val states = ctx.stateDecl().visit().unpack<State>()
        val shifts = ctx.shiftDecl().visit().unpack<Shift>()
        return Automaton(name = ctx.automatonName().text, states = states, shifts = shifts)
    }

    override fun visitTypeDecl(ctx: LibraryModelParser.TypeDeclContext): Type =
            Type(semanticType = ctx.semanticType().text, codeType = ctx.codeType().text)

    override fun visitTypesSection(ctx: LibraryModelParser.TypesSectionContext): Node =
            ctx.typeDecl().visit()

    override fun visitStateDecl(ctx: LibraryModelParser.StateDeclContext): Node =
            State(name = ctx.stateName().text)

//    override fun visitShiftDecl(ctx: LibraryModelParser.ShiftDeclContext): Node {
//        return AutoEdge(machine = StateMachine("Dummy"), src = ru.spbstu.kspt.librarymigration.State())
//    }

    fun List<ParserRuleContext>.visit(): NodeList = NodeList(map { visit(it) })
    fun <T> NodeList.unpack() = list as List<T>
//    fun List<LibraryModelParser.TypeDeclContext>.visit() = TypeList(map { visit(it) })
}

fun main(args: Array<String>) {
    ModelParser().parse()
}