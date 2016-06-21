import org.jgrapht.ext.DOTExporter
import org.jgrapht.ext.EdgeNameProvider
import org.jgrapht.ext.VertexNameProvider
import org.jgrapht.graph.DirectedPseudograph
import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Created by artyom on 16.06.16.
 */

fun edgeLabel(v: Edge) = v.action.label()

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
            storage.append("  %s [ label=\"%s\" ];\n".format(state.id(), state.label()))
        }
        for (edge in fsm.edges) {
            System.out.println("Edge: " + edge.toString())
            val createdMachine = edge.createdMachine
            if (createdMachine != null) {
                storage.append("  virtual%d [ shape = point ];\n".format(counter))
                storage.append("  %s -> virtual%d [ label=\"%s\" ];\n".format(edge.src.id(), counter, edgeLabel(edge)))
                storage.append("  virtual%d -> %s;\n".format(counter, edge.dst.id()))
                val newEdge = edge.copy(action = LinkedAction(edge))
                System.out.println("Link: " + newEdge.toString())
                storage.append("  virtual%d -> %s [ label=\"%s\" ];\n".format(counter, createdMachine.getInitState().id(), edgeLabel(newEdge)))
                counter++
            } else {
                storage.append("  %s -> %s [ label=\"%s\" ];\n".format(edge.src.id(), edge.dst.id(), edgeLabel(edge)))
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
    rt.exec(command)
}

fun toJGrapht(library: Library): DirectedPseudograph<State, Edge> {
    System.out.println("Start")
    val graph = DirectedPseudograph<State, Edge>(Edge::class.java)

    val exporter = DOTExporter<State, Edge>(VertexNameProvider { it.name },
            VertexNameProvider { it.label() }, EdgeNameProvider { edgeLabel(it) })

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
                val newEdge = edge.copy(action = LinkedAction(edge), autoRegister = false)
                System.out.println("Link: " + newEdge.toString())
                graph.addEdge(edge.src, createdMachine.getInitState(), newEdge)
            }
        }
    }

    exporter.export(FileWriter(File("graph_debug.dot"), false), graph)

    return graph
}