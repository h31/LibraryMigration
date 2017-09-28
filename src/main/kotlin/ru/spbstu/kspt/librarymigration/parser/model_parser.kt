package ru.spbstu.kspt.librarymigration

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelBaseVisitor
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelLexer
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelParser
import ru.spbstu.kspt.librarymigration.modelreader.LibraryModelVisitor

/**
 * Created by artyom on 13.07.17.
 */
class ModelParser {
    fun parse() {
        val stream = javaClass.classLoader.getResourceAsStream("OkHttp.lsl")
//        println(stream.bufferedReader().readText())
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
        val sections = ctx.description().section().map { visit(it) }
        val entities = sections.filterIsInstance<EntityList>().single() // .map { it.name to it.type }.toMap()
        val fsms = sections.filterIsInstance<StateMachine>()
        val tinyMachines = entities.filterNot { entity -> fsms.any { it.name == entity.name } }.map { StateMachine(it.name) }
        val allMachines = fsms + tinyMachines
        val machineTypes = entities.map { allMachines.single { fsm -> fsm.name == it.name } to it.type }.toMap()
        return Library(name = libraryName, machineTypes = machineTypes, stateMachines = allMachines)
    }

    override fun visitAutomatonDescription(ctx: LibraryModelParser.AutomatonDescriptionContext): Node {
        val stateMachine = StateMachine(name = ctx.automatonName().text)
        stateMachine.states += ctx.stateDecl().map { visit(it) }.map { State((it as StateName).name, machine = stateMachine) }
        stateMachine.edges += ctx.shiftDecl().map { visit(it) } as List<Edge>
        return stateMachine
    }

    override fun visitTypeDecl(ctx: LibraryModelParser.TypeDeclContext): Node =
            Entity(name = ctx.semanticType().text, type = ctx.codeType().text)

    override fun visitTypesSection(ctx: LibraryModelParser.TypesSectionContext): Node =
            EntityList(ctx.typeDecl().map { visit(it) as Entity })

    override fun visitStateDecl(ctx: LibraryModelParser.StateDeclContext): Node {
        return StateName(name = ctx.stateName().text)
    }

    override fun visitShiftDecl(ctx: LibraryModelParser.ShiftDeclContext): Node {
        return AutoEdge(machine = StateMachine("Dummy"), src = ru.spbstu.kspt.librarymigration.State())
    }
}

fun main(args: Array<String>) {
    ModelParser().parse()
}