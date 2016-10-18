import java.util.*

/**
 * Created by artyom on 08.09.16.
 */
class PathFinder(val edges: Set<Edge>) {
    fun findPath(start: State, goal: State): List<Edge> {
        pending += Model(state = start, path = listOf())
        while (true) {
            if (pending.isEmpty()) {
                error("Empty pendings!")
            }
            val model = pending.poll()
            if (aStar(model, goal, edges)) {
                println("Solution found, route:")
                for (link in model.path.withIndex()) {
                    println(link.index.toString() + ". " + link.value)
                }
                return model.path
            }
        }
    }

    class ModelCompare: Comparator<Model> {
        override fun compare(o1: Model?, o2: Model?): Int {
            if (o1 == null || o2 == null) return 0
            val pathDiff = o1.path.size - o2.path.size
            return pathDiff
        }
    }

    class Model(val state: State,
                val path: List<Edge> = listOf()) {
        override fun equals(other: Any?): Boolean = other is Model && state.equals(other.state)
        override fun hashCode() = state.hashCode()
        override fun toString() = "Model(state=$state, path=$path)"
    }

    val visited = mutableSetOf<Model>()
    val pending = PriorityQueue<Model>(ModelCompare())

    fun aStar(current: Model, goal: State, edges: Set<Edge>): Boolean {
        if (current.state == goal) {
            return true
        }
        if (visited.contains(current)) {
            return false
        }
        visited += current
        //println("Current model:")
        //println(current)
        val availableEdges = edges.filter { edge -> edge.src == current.state }
        //println("Pending links:")
        //println(goodLinks)
        for (edge in availableEdges) {
//        var properties = current.properties
//        for (newProp in link.sets) {
//            //println("Set %d to %s".format(newProp.first, newProp.second.toString()))
//            properties += newProp
//        }
            val newModel = Model(state = edge.dst, path = current.path + edge)
            //println("New model:")
            //println(newModel)
            pending.add(newModel)
        }
        //println("Pending models:")
        //println(pending)
        return false
    }
}