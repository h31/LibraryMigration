import java.util.*

/**
 * Created by artyom on 08.09.16.
 */
class PathFinder(val edges: Set<Edge>, val src: Set<State>, val props: MutableMap<State, Map<String, Any>>) {
    lateinit var resultModel: Model

    fun findPath(goal: State) {
        for (state in src) {
            pending += Model(state = state, props = makeProps(state))
        }
        while (true) {
            if (pending.isEmpty()) {
                error("Empty pendings!")
            }
            val model = pending.poll()
            if (aStar(model, goal, edges)) {
                println("Solution found, route:")
                for (link in model.path.withIndex()) {
                    println(link.index.toString() + ". " + link.value.label())
                }
                resultModel = model
                return
            }
        }
    }

    class ModelCompare : Comparator<Model> {
        override fun compare(o1: Model?, o2: Model?): Int {
            if (o1 == null || o2 == null) return 0
            val pathDiff = o1.path.size - o2.path.size
            return pathDiff
        }
    }

    data class Model(val state: State,
                     val props: MutableMap<String, Any> = mutableMapOf()) {
        var path: List<Edge> = listOf()
        var stateProps: Map<State, Map<String, Any>> = mapOf()
    }

    fun makeProps(state: State): MutableMap<String, Any> {
        val existingProps = props[state]
        return if (existingProps != null) LinkedHashMap(existingProps) else mutableMapOf()
    }

    private val visited = mutableSetOf<Model>()
    private val pending = PriorityQueue<Model>(ModelCompare())

    fun aStar(current: Model, goal: State, edges: Set<Edge>): Boolean {
        if (current.state == goal) {
            return true
        }
        if (visited.contains(current)) {
            return false
        }
        visited += current
        val availableEdges = edges.filter { edge -> edge.src == current.state }
        for (edge in availableEdges) {
            val newModel = Model(state = edge.dst, props = makeProps(edge.dst))
            val isAllowed = edge.allowTransition(newModel.props)
            newModel.path = current.path + edge
            newModel.stateProps = current.stateProps + Pair(newModel.state, newModel.props)
            if (isAllowed) {
                pending.add(newModel)
            }
        }
        return false
    }
}

class PropsContext {
    var stateProps: Map<State, Map<String, Any>> = mapOf()

    fun addEdgeFromTrace(edge: Edge) {
        val props = makeProps(edge.dst)
        val isAllowed = edge.allowTransition(props)
        if (!isAllowed) error("Not allowed")
        stateProps += Pair(edge.dst, props)
    }

    fun makeProps(state: State): MutableMap<String, Any> {
        val existingProps = stateProps[state]
        return if (existingProps != null) LinkedHashMap(existingProps) else mutableMapOf()
    }

    fun NonEmptyProps() = stateProps.filter { map -> map.value.isNotEmpty() }
}