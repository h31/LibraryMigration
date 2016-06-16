import org.jgrapht.ext.DOTExporter
import org.jgrapht.ext.EdgeNameProvider
import org.jgrapht.ext.VertexNameProvider
import org.jgrapht.graph.DirectedPseudograph
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Created by artyom on 16.06.16.
 */

fun stateName(v: State) = v.name + "_" + v.machine.entity.name
fun stateLabel(v: State) = v.machine.entity.name + ": " + v.name


fun edgeLabel(v: Edge, isSrc: Boolean): String {
//    "%s(%s: %s)".format(v.methodName, v.params.first().entity.name, type)
    val args = if (v.params.isNotEmpty()) {
        val arg = v.params.first()
        val previousArgs = "..., ".repeat(arg.pos)
        val argType = if (isSrc) arg.entity.srcType else arg.entity.dstType
        "(%s%s: %s)".format(previousArgs, arg.entity.name, argType)
    } else {
        "()"
    }
    when (v.action) {
        Action.CONSTRUCTOR -> return "new " + v.methodName + args
        Action.METHOD_CALL -> return v.methodName + args
        Action.STATIC_CALL -> return v.methodName + args
        Action.AUTO -> return ""
    }
}

fun toGraphviz(library: Library, isSrc: Boolean, rankLR: Boolean): String {
    val storage = StringBuilder()

    storage.append("digraph G {\n")
    if (rankLR) {
        storage.append("  rankdir = LR;\n")
    }

    var counter = 0

    for (fsm in library.stateMachines) {
        System.out.println("FSM: " + fsm.toString())
        for (state in fsm.states) {
            System.out.println("Vertex: " + state.toString())
            storage.append("  %s [ label=\"%s\" ];\n".format(stateName(state), stateLabel(state)))
        }
        for (edge in fsm.edges) {
            System.out.println("Edge: " + edge.toString())
            val createdMachine = edge.createdMachine
            if (createdMachine != null) {
                storage.append("  virtual%d [ shape = point ];\n".format(counter))
                storage.append("  %s -> virtual%d [ label=\"%s\" ];\n".format(stateName(edge.src), counter, edgeLabel(edge, isSrc)))
                storage.append("  virtual%d -> %s;\n".format(counter, stateName(edge.dst)))
                val newEdge = edge.copy(action = Action.CONSTRUCTOR,
                        methodName = createdMachine.entity.name,
                        params = listOf())
                System.out.println("Link: " + newEdge.toString())
                storage.append("  virtual%d -> %s [ label=\"%s\" ];\n".format(counter, stateName(createdMachine.getInitState()), edgeLabel(newEdge, isSrc)))
                counter++
            } else {
                storage.append("  %s -> %s [ label=\"%s\" ];\n".format(stateName(edge.src), stateName(edge.dst), edgeLabel(edge, isSrc)))
            }
        }
    }

    storage.append("}\n")
    return storage.toString()
}

fun graphvizRender(graph: String, prefix: String) {
    val dotPath = Paths.get(prefix + ".dot")
    val pdfPath = Paths.get(prefix + ".pdf")
    Files.write(dotPath, graph.toByteArray());
    val rt = Runtime.getRuntime();
    val command = "dot -Tpdf %s -o %s".format(dotPath, pdfPath)
    println(command)
    rt.exec(command)
}

fun toJGrapht(library: Library): DirectedPseudograph<State, Edge> {
    System.out.println("Start")
    val graph = DirectedPseudograph<State, Edge>(Edge::class.java)

    for (fsm in library.stateMachines) {
        System.out.println("FSM: " + fsm.toString())
        for (state in fsm.states) {
            System.out.println("Vertex: " + state.toString())
            graph.addVertex(state)
        }
        for (edge in fsm.edges) {
            System.out.println("Edge: " + edge.toString())
            graph.addEdge(edge.src, edge.dst, edge)
        }

//        val subgraph = DirectedSubgraph(graph, fsm.states.toSet(), null)
    }
    for (fsm in library.stateMachines) {
        for (edge in fsm.edges) {
            val createdMachine = edge.createdMachine
            if (createdMachine != null) {
                val newEdge = edge.copy(action = Action.CONSTRUCTOR,
                        methodName = createdMachine.entity.name,
                        params = listOf())
                System.out.println("Link: " + newEdge.toString())
                graph.addEdge(edge.src, createdMachine.getInitState(), newEdge)
            }
        }
    }

    return graph
}