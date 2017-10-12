package ru.spbstu.kspt.librarymigration.parser

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelBaseVisitor
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelLexer
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelParser
import ru.spbstu.kspt.librarymigration.prettyPrinter
import java.io.InputStream

/**
 * Created by artyom on 13.07.17.
 */
class ModelParser {
    fun parse(stream: InputStream): Library {
        val charStream = CharStreams.fromStream(stream)
        val lexer = LibraryModelLexer(charStream)
        val tokenStream = CommonTokenStream(lexer)
        val parser = LibraryModelParser(tokenStream)
        val start = parser.start()

        return LibraryModelReader().visitStart(start)
    }

    fun postprocess(library: Library) = Postprocessor().process(library)
}

class LibraryModelReader : LibraryModelBaseVisitor<Node>() {
    override fun visitStart(ctx: LibraryModelParser.StartContext): Library {
        val libraryName = ctx.libraryName().Identifier().text
        val desc = ctx.description()
        val automata = desc.automatonDescription().map { visitAutomatonDescription(it) }
        val typeList = desc.typesSection().single().typeDecl().map { visitTypeDecl(it) }
        val converters = desc.convertersSection().single().converter().map { visitConverter(it) }
        val functions = desc.funDecl().map { visitFunDecl(it) }
        return Library(name = libraryName, automata = automata, types = typeList,
                converters = converters, functions = functions)
    }

    override fun visitAutomatonDescription(ctx: LibraryModelParser.AutomatonDescriptionContext): Automaton {
        val states = ctx.stateDecl().map { visitStateDecl(it) }
        val shifts = ctx.shiftDecl().map { visitShiftDecl(it) }
        return Automaton(name = ctx.automatonName().text, states = states, shifts = shifts)
    }

    override fun visitTypeDecl(ctx: LibraryModelParser.TypeDeclContext): Type =
            Type(semanticType = ctx.semanticType().text, codeType = ctx.codeType().text)

    override fun visitStateDecl(ctx: LibraryModelParser.StateDeclContext): State =
            State(name = ctx.stateName().text)

    override fun visitShiftDecl(ctx: LibraryModelParser.ShiftDeclContext): Shift =
            Shift(from = ctx.srcState().text, to = ctx.dstState().text,
                    functions = ctx.funName().map { it.text })

    override fun visitConverter(ctx: LibraryModelParser.ConverterContext): Converter =
            Converter(entity = ctx.destEntity().text, expression = ctx.converterExpression().text)

    override fun visitFunDecl(ctx: LibraryModelParser.FunDeclContext): FunctionDecl {
        val args = ctx.funArgs().funArg().map { visitFunArg(it) }
        val actions = ctx.funProperties().map { visit(it) }.filterIsInstance<Action>()
        return FunctionDecl(entity = ctx.entityName().text, name = ctx.funName().text,
                args = args, actions = actions)
    }

    override fun visitActionDecl(ctx: LibraryModelParser.ActionDeclContext): Node {
        return Action(name = ctx.actionName().text, args = ctx.Identifier().map { it.text })
    }

    override fun visitFunArg(ctx: LibraryModelParser.FunArgContext): FunctionArgument =
            FunctionArgument(name = ctx.argName().text, type = ctx.argType().text)
}

fun main(args: Array<String>) {
}