import com.github.systemdir.gml.YedGmlWriter
import com.github.systemdir.gml.model.EdgeGraphicDefinition
import com.github.systemdir.gml.model.GraphicDefinition
import com.github.systemdir.gml.model.NodeGraphicDefinition
import com.github.systemdir.gml.model.YedGmlGraphicsProvider
import com.samskivert.mustache.Mustache
import org.jgrapht.EdgeFactory
import org.jgrapht.ext.VertexNameProvider
import org.jgrapht.graph.DirectedPseudograph
import java.awt.Color
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Paths


/**
 * Created by artyom on 16.06.16.
 */

fun toDOT(library: Library, visited: List<Edge> = listOf()): String {
    val edges = mutableListOf<Map<String, Any>>()
    val virtuals = library.stateMachines.map { it -> it to mutableListOf<Int>() }.toMap()

    var counter = 0
    for (machine in library.stateMachines) {
        for (edge in machine.getDisplayedEdges()) {
            val baseMap = mapOf<String, Any>(
                    "src" to edge.src.id(),
                    "dst" to edge.dst.id(),
                    "edge" to edge.label(),
                    "visited" to visited.contains(edge),
                    "style" to edge.getStyle())
            val values = if (edge is ExpressionEdge && edge.linkedEdge != null) {

                virtuals.get(machine)!!.add(counter)
                baseMap + mapOf(
                        "machine" to edge.linkedEdge!!.dst.id(),
                        "linkedEdge" to edge.linkedEdge!!.label(),
                        "counter" to counter++,
                        "linkedVisited" to visited.contains(edge.linkedEdge!!))
            } else baseMap
            edges += values
        }
    }

    val machines = library.stateMachines.mapIndexed {
        num, machine ->
        mapOf("num" to num,
                "name" to machine.label(),
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
    val folder = "pictures/"
    val folderPath = Paths.get(folder)
    if (Files.isDirectory(folderPath) == false) Files.createDirectory(folderPath)
    val dotPath = Paths.get(folder + prefix + ".dot")
    Files.write(dotPath, graph.toByteArray());
    DOTtoPDF(folder + prefix)
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
            EdgeFactory { source, target -> source.machine.edges.first { edge -> edge.src == source && edge.dst == target } }
    )

    val provider = ExampleGraphicsProvider()

    val writer = YedGmlWriter.Builder<State, Edge, StateMachine>(provider, YedGmlWriter.PrintLabels.PRINT_VERTEX_LABELS,
            YedGmlWriter.PrintLabels.PRINT_EDGE_LABELS,
            YedGmlWriter.PrintLabels.PRINT_GROUP_LABELS)
            .setGroups(library.stateMachines.map { it to it.states }.toMap(), VertexNameProvider<StateMachine> { it.name })
            .setVertexIDProvider({ when(it) {
                is State -> "\"" + it.id() + "\""
                is StateMachine -> "\"" + it.name + "\""
                else -> error("")
            } })
            .setVertexLabelProvider { it.label() }
            .setEdgeLabelProvider { it.label() }
            .build()


//    val exporter = DOTExporter<State, Edge>(VertexNameProvider { it.id() },
//            VertexNameProvider { it -> it.machine.name + " " + it.label() }, EdgeNameProvider { it.label() })

    for (fsm in library.stateMachines) {
//        System.out.println("FSM: " + fsm.toString())
        for (state in fsm.states) {
//            System.out.println("Vertex: " + state.toString())
            graph.addVertex(state)
        }
//        val subgraph = DirectedSubgraph(graph, fsm.states.toSet(), null)
    }
    for (fsm in library.stateMachines) {
        for (edge in fsm.edges) {
//            System.out.println("Edge: " + edge.toString())
            graph.addEdge(edge.src, edge.dst, edge)
        }
    }

    writer.export(FileWriter(File("graph_debug.gml"), false), graph)
    // DOTtoPDF("graph_debug")

    return graph
}

class ExampleGraphicsProvider : YedGmlGraphicsProvider<State, Edge, StateMachine> {
    override fun getVertexGraphics(vertex: State): NodeGraphicDefinition {
        return NodeGraphicDefinition.Builder()
                .setFill(Color.LIGHT_GRAY)
                .setLineColor(Color.BLACK)
                .setFontStyle(GraphicDefinition.FontStyle.ITALIC)
                .build()
    }

    override fun getEdgeGraphics(edge: Edge, edgeSource: State, edgeTarget: State): EdgeGraphicDefinition {
        return EdgeGraphicDefinition.Builder()
                .setTargetArrow(EdgeGraphicDefinition.ArrowType.SHORT_ARROW)
                .setLineType(GraphicDefinition.LineType.DASHED)
                .build()
    }

    override fun getGroupGraphics(group: StateMachine, groupElements: Set<State>): NodeGraphicDefinition? {
        val builder = NodeGraphicDefinition.Builder()
                .setFill(Color.GRAY)
                .setLabelColour(Color.ORANGE)
        builder.setLabelPlacement(NodeGraphicDefinition.LabelPlacement.top)
        builder.setLineType(GraphicDefinition.LineType.DASHED)
        return builder.build()
    }
}

fun main(args: Array<String>) {
    toJGrapht(HttpModels.java)
}
