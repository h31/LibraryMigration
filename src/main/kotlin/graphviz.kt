import com.samskivert.mustache.Mustache
import org.jgrapht.EdgeFactory
import org.jgrapht.ext.DOTExporter
import org.jgrapht.ext.EdgeNameProvider
import org.jgrapht.ext.VertexNameProvider
import org.jgrapht.graph.DirectedPseudograph
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.io.StringReader


/**
 * Created by artyom on 16.06.16.
 */

fun toDOT(library: Library): String {
    val edges = mutableListOf<Map<String, Any>>()
    val virtuals = library.stateMachines.map { it -> it to mutableListOf<Int>() }.toMap()
    val displayerMachines = library.stateMachines.filter { it -> it.inherits == null }

    var counter = 0
    for (machine in library.stateMachines) {
        for (edge in machine.getDisplayedEdges()) {
            val baseMap = mapOf<String, String>(
                    "src" to edge.src.id(),
                    "dst" to edge.dst.id(),
                    "edge" to edge.label(library))
            val values = if (edge is CallEdge && edge.linkedEdge != null) {

                virtuals.get(machine)!!.add(counter)
                baseMap + mapOf(
                        "machine" to edge.linkedEdge!!.dst.id(),
                        "linkedEdge" to edge.linkedEdge!!.label(library),
                        "counter" to counter++)
            } else baseMap
            edges += values
        }
    }

    val machines = library.stateMachines.mapIndexed {
        num, machine ->
        mapOf("num" to num,
                "name" to machine.label(library),
                "vertices" to machine.states,
                "virtuals" to virtuals.get(machine)!!.map {
                    count ->
                    mapOf("counter" to count)
                })
    }

    val compiler = Mustache.compiler()
    val template = compiler.compile(FileReader("graphviz.mustache"))
    return template.execute(mapOf("machines" to machines, "edges" to edges))
}

fun graphvizRender(graph: String, prefix: String) {
    val dotPath = Paths.get(prefix + ".dot")
    Files.write(dotPath, graph.toByteArray());
    DOTtoPDF(prefix)
}

fun DOTtoPDF(prefix: String) {
    val dotPath = prefix + ".dot"
    val pdfPath = prefix + ".pdf"
    val rt = Runtime.getRuntime();
    val command = "dot -Tpdf %s -o %s".format(dotPath, pdfPath)
    rt.exec(command)
}

fun toJGrapht(library: Library): DirectedPseudograph<State, Edge> {
    System.out.println("Start")
    val graph = DirectedPseudograph<State, Edge>(
            EdgeFactory { source, target -> source.machine.edges.first { edge -> edge.src == source && edge.dst == target }}
    )

    val exporter = DOTExporter<State, Edge>(VertexNameProvider { it.id() },
            VertexNameProvider { it -> it.machine.name + " " + it.label(library) }, EdgeNameProvider { it.label(library) })

    for (fsm in library.stateMachines) {
        System.out.println("FSM: " + fsm.toString())
        for (state in fsm.states) {
            System.out.println("Vertex: " + state.toString())
            graph.addVertex(state)
        }
//        val subgraph = DirectedSubgraph(graph, fsm.states.toSet(), null)
    }
    for (fsm in library.stateMachines) {
        for (edge in fsm.edges) {
            System.out.println("Edge: " + edge.toString())
            graph.addEdge(edge.src, edge.dst, edge)
        }
    }

    exporter.export(FileWriter(File("graph_debug.dot"), false), graph)
    DOTtoPDF("graph_debug")

    return graph
}