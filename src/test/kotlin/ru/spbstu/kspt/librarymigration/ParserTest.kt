package ru.spbstu.kspt.librarymigration

import org.junit.Assert
import org.junit.Test
import ru.spbstu.kspt.librarymigration.parser.ModelParser

class ParserTest {
    @Test
    fun okHttpAST() {
        val stream = javaClass.classLoader.getResourceAsStream("OkHttp.lsl")
        val library = ModelParser().parse(stream)
        Assert.assertEquals(22, library.types.size)
        Assert.assertEquals(2, library.automata.size)
        Assert.assertEquals(2, library.automata.first { it.name == "Builder" }.shifts.size)
        Assert.assertEquals(4, library.functions.size)
//        println(prettyPrinter(library.toString()))
    }

    @Test
    fun okHttpModel() {
        val stream = javaClass.classLoader.getResourceAsStream("OkHttp.lsl")
        val ast = ModelParser().parse(stream)
        val library = ModelParser().postprocess(ast)
        Assert.assertEquals(22, library.stateMachines.size)
        Assert.assertEquals(22, library.allTypes().size)
        val fsm = library.stateMachines.first { it.name == "Builder" }
        Assert.assertEquals(2 + 2, fsm.edges.size)
        val callEdges = fsm.edges.filterIsInstance<CallEdge>()
        val postEdge = callEdges.first { it.methodName == "post" }
        Assert.assertEquals(2, postEdge.actions.size)
        val headerEdge = callEdges.first { it.methodName == "header" }
        Assert.assertEquals(4, headerEdge.param.size) // TODO: Why 4?
        Assert.assertEquals(2, headerEdge.param.filterIsInstance<ActionParam>().size)
        println(prettyPrinter(library.toString()))
    }
}