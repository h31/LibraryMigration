import com.samskivert.mustache.Mustache
import java.io.FileReader
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