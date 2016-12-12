import java.util.*

/**
 * Created by artyom on 08.09.16.
 */
class PathFinder(val edges: Set<Edge>, val src: Set<State>, val initProps: Map<StateMachine, Map<String, Any>>,
                 val requiredActions: List<Action>) {
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
                     val props: MutableMap<String, Any> = mutableMapOf(),
                     val actions: List<Action> = listOf()) {
        var path: List<Edge> = listOf()
        var stateProps: Map<StateMachine, Map<String, Any>> = mapOf()
    }

    fun makeProps(state: State): MutableMap<String, Any> {
        val existingProps = initProps[state.machine]
        return if (existingProps != null) LinkedHashMap(existingProps) else mutableMapOf()
    }

    fun makeActions(oldActions: List<Action>, newAction: Action?) = if (newAction != null) oldActions + newAction else oldActions

    private val visited = mutableSetOf<Model>()
    private val pending = PriorityQueue<Model>(ModelCompare())

    fun aStar(current: Model, goal: State, edges: Set<Edge>): Boolean {
        if (current.state == goal && current.actions.containsAll(requiredActions)) {
            return true
        }
        if (visited.contains(current)) {
            return false
        }
        visited += current
        val availableEdges = edges.filter { edge -> edge.src == current.state }
        for (edge in availableEdges) {
            val action = edge.action
            val newModel = Model(state = edge.dst, props = makeProps(edge.dst), actions = makeActions(current.actions, action))
            val isAllowed = edge.allowTransition(newModel.props)
            newModel.path = current.path + edge
            newModel.stateProps = current.stateProps + Pair(newModel.state.machine, newModel.props)
            val isProperAction = if (action != null) requiredActions.contains(action) else true
            if (edge is UsageEdge && edge.edge is CallEdge && edge.edge.methodName == "setEntity") {
                if (requiredActions.contains(edge.edge.action) == false) {
                    continue
                }
//                val deps = backPropagation(edge, current)
            }
            if (isAllowed && isProperAction) {
                pending.add(newModel)
            }
        }
        return false
    }

    private fun backPropagation(step: Edge, current: Model): List<Edge> {
        val outputRoute = mutableListOf<Edge>()
        when (step) {
            is UsageEdge -> {
                if ((step.edge is CallEdge && step.edge.isStatic) == false) {
                    val dependencyStep = step.edge
                    val newRoute = findRoute(src, dependencyStep.src, initProps) // TODO
                    outputRoute += newRoute.path
                    outputRoute += dependencyStep
//                    for (edge in newRoute.path) {
//                        context.removeAll { it.machine == edge.dst.machine }
//                        context += edge.dst
//                    }
//                    props += newRoute.stateProps
                }
            }
//            is ExpressionEdge -> {
//                val missingDeps = step.param.filterIsInstance<EntityParam>().filterNot { param -> context.contains(param.state) }
//                for (dependency in missingDeps) {
//                    val newRoute = findRoute(context, dependency.state, null)
//                    outputRoute += newRoute.path
//                    for (edge in newRoute.path) {
//                        context.removeAll { it.machine == edge.dst.machine }
//                        context += edge.dst
//                    }
//                    props += newRoute.stateProps
//                }
//            }
        }
//        outputRoute += step
//        context.removeAll { it.machine == step.dst.machine }
//        context += step.dst
        return outputRoute
    }

    private fun findRoute(src: Set<State>, dst: State, props: Map<StateMachine, Map<String, Any>>): PathFinder.Model {
        println("  Searching route from %s to %s".format(src.joinToString(transform = State::stateAndMachineName), dst.stateAndMachineName()))
        val pathFinder = PathFinder(edges, src, props, listOf())
        pathFinder.findPath(dst)
        return pathFinder.resultModel
    }

    private fun getDependencyStep(step: Edge) = edges.first { edge: Edge -> (edge.src != edge.dst) && (edge is UsageEdge == false) && (edge.dst == step.dst) }
}

class PropsContext {
    var stateProps: Map<StateMachine, Map<String, Any>> = mapOf()
    var actionParams: Map<Action, Map<String, Any>> = mapOf()
    var actions: List<Action> = listOf()

    fun addEdgeFromTrace(locatedEdge: RouteExtractor.LocatedEdge) {
        val edge = locatedEdge.edge
        val props = makeProps(edge.dst)
        val isAllowed = edge.allowTransition(props)
        if (!isAllowed) error("Not allowed")
        stateProps += Pair(edge.dst.machine, props)
        val action = edge.action
        if (action != null) {
            actions += action
            actionParams += Pair(action, edge.actionParams(locatedEdge.node))
        }
    }

    fun makeProps(state: State): MutableMap<String, Any> {
        val existingProps = stateProps[state.machine]
        return if (existingProps != null) LinkedHashMap(existingProps) else mutableMapOf()
    }

    fun NonEmptyProps() = stateProps.filter { map -> map.value.isNotEmpty() }
}