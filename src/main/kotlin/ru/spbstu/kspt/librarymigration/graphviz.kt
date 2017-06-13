package ru.spbstu.kspt.librarymigration

import com.github.systemdir.gml.YedGmlWriter
import com.github.systemdir.gml.model.EdgeGraphicDefinition
import com.github.systemdir.gml.model.GraphicDefinition
import com.github.systemdir.gml.model.NodeGraphicDefinition
import com.github.systemdir.gml.model.YedGmlGraphicsProvider
import com.samskivert.mustache.Mustache
import org.jgrapht.EdgeFactory
import org.jgrapht.graph.DirectedPseudograph
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Paths
import org.slf4j.LoggerFactory
import java.io.*


/**
 * Created by artyom on 16.06.16.
 */

interface Visualization {
    fun makeDescription(library: Library, visited: List<Edge> = listOf()): String
    fun render(prefix: String)
    val descriptionExtension: String

    fun writeDescription(graph: String, name: String): String {
        val folder = "pictures/"
        val folderPath = Paths.get(folder)
        if (Files.isDirectory(folderPath) == false) Files.createDirectory(folderPath)
        val prefix = folder + name
        val descPath = Paths.get(prefix + descriptionExtension)
        Files.write(descPath, graph.toByteArray())
        return prefix
    }

    fun makePicture(library: Library, name: String = library.name, visited: List<Edge> = listOf()) {
        val desc = makeDescription(library)
        val prefix = writeDescription(desc, name)
        render(prefix)
    }
}

class DOTVisualization : Visualization {
    override val descriptionExtension: String = ".dot"
    override fun makeDescription(library: Library, visited: List<Edge>): String {
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

    override fun render(prefix: String) {
        val dotPath = prefix + ".dot"
        val pdfPath = prefix + ".pdf"
        val rt = Runtime.getRuntime();
        val command = "dot -Tpdf %s -o %s".format(dotPath, pdfPath)
        try {
            rt.exec(command)
        } catch (ex: IOException) {
            println("Graphviz is not available!")
        }
    }


}

class GMLVisualization : Visualization {
    override val descriptionExtension: String = ".gml"
    var logger = LoggerFactory.getLogger(GMLVisualization::class.java)

    override fun makeDescription(library: Library, visited: List<Edge>): String {
        val graph = DirectedPseudograph<State, Edge>(
                EdgeFactory { source, target -> source.machine.edges.first { edge -> edge.src == source && edge.dst == target } }
        )

        val provider = GraphicsProvider()

        val writer = YedGmlWriter.Builder<State, Edge, StateMachine>(provider, YedGmlWriter.PrintLabels.PRINT_VERTEX_LABELS,
                YedGmlWriter.PrintLabels.PRINT_EDGE_LABELS,
                YedGmlWriter.PrintLabels.PRINT_GROUP_LABELS)
                .setGroups(library.stateMachines.map { it to it.states }.toMap(), { it.name })
                .setVertexIDProvider({
                    when (it) {
                        is State -> "\"" + it.id() + "\""
                        is StateMachine -> "\"" + it.name + "\""
                        else -> error("")
                    }
                })
                .setVertexLabelProvider { it.label() }
                .setEdgeLabelProvider { it.label() }
                .build()

        for (fsm in library.stateMachines) {
            for (state in fsm.states) {
                graph.addVertex(state)
            }
        }
        for (fsm in library.stateMachines) {
            for (edge in fsm.edges) {
                graph.addEdge(edge.src, edge.dst, edge)
            }
        }

        val stringWriter = StringWriter()
        writer.export(stringWriter, graph)
        return stringWriter.toString()
    }

    override fun render(prefix: String) = logger.warn("User should do rendering manually")

    class GraphicsProvider : YedGmlGraphicsProvider<State, Edge, StateMachine> {
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
}