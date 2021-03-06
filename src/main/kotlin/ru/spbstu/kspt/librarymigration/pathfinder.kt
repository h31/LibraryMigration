package ru.spbstu.kspt.librarymigration

import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import mu.KotlinLogging
import java.util.*
import org.slf4j.LoggerFactory



/**
 * Created by artyom on 08.09.16.
 */
class PathFinder(val edges: Set<Edge>, val src: Set<State>, val initProps: Map<StateMachine, Map<String, Any>>,
                 val requiredActions: List<Action>) {
    lateinit var resultModel: Model

    // Ассоциативный массив семантических действий с указанием их количества
    val requiredActionsMultiset = requiredActions.fold(mapOf<Action, Int>(), {map, action -> map + Pair(action, map[action]?.inc() ?: 1)})

    private val logger = KotlinLogging.logger {}

    fun findPath(goal: State?) {
        // Начальные модели
        for (state in src) {
            pending += Model(state = state, props = getProps(state.machine, null)).setContext(src)
        }
        while (true) {
            if (haveMissingRequirements.isNotEmpty()) {
                processRequirements()
            }
            if (pending.isEmpty()) {
                logger.error("Not found!")
                error("Empty pendings!")
            }
            val model = pending.poll()
            if (aStar(model, goal, edges)) {
                logger.debug("Solution found, route:")
                for (link in model.path.withIndex()) {
                    logger.debug(link.index.toString() + ". " + link.value.label())
                }
                resultModel = model
                return
            }
        }
    }

    /**
     * Обработка зависимостей
     */
    private fun processRequirements() {
        val processedModels = mutableListOf<Model>()
        for ((model, requirements) in haveMissingRequirements) {
            val requiredModels = visited.filter { requirements.contains(it.state) }.filter { it.actions.isEmpty() } // TODO
            if (requiredModels.size == requirements.size) {
                logger.debug("Model ${model.state} received all dependencies ($requirements)")
                val newModel = model.copy(actions = model.actions + requiredModels.flatMap { it.actions })
                val newPath = model.path.toMutableList()
                newPath.addAll(Math.max(newPath.size - 1, 0), requiredModels.flatMap { it.path })
                newModel.path = newPath
                newModel.context += model.context + requiredModels.flatMap { it.context }
                for (requiredModel in requiredModels) {
                    newModel.stateProps += requiredModel.stateProps
                }
                pending += newModel
                processedModels += model
            }
        }
        processedModels.forEach { model -> haveMissingRequirements.removeAll { it.first == model } }
    }

    class ModelCompare : Comparator<Model> {
        override fun compare(o1: Model?, o2: Model?): Int {
            if (o1 == null || o2 == null) return 0
            val pathDiff = o1.path.size - o2.path.size
            return pathDiff
        }
    }

    data class Model(val state: State,
                     val props: Map<String, Any> = mutableMapOf(),
                     val actions: List<Action> = listOf()) {
        var path: List<Edge> = listOf()
        val pathSizeWithoutNoCostEdges by lazy { path.count { it !is CastEdge && it !is AutoEdge } }
        var stateProps: Map<StateMachine, Map<String, Any>> = mapOf()
        var context: Set<State> = setOf()

        fun setContext(c: Set<State>): Model {
            context = c
            return this
        }
    }

    fun getProps(machine: StateMachine, model: Model?): Map<String, Any> {
        val currentProps = model?.stateProps?.get(machine)
        val existingProps = initProps[machine]
        return when {
            currentProps != null -> currentProps
            existingProps != null -> existingProps
            else -> mapOf()
        }
    }

    fun makeActions(oldActions: List<Action>, newAction: List<Action>) = if (newAction.isNotEmpty()) (oldActions + newAction).sorted() else oldActions

    fun calcRequirements(edge: Edge, context: Set<State>): List<State> = when (edge) {
        is ExpressionEdge -> {
            val missingParams = edge.param.filterIsInstance<EntityParam>().filterNot { param -> context.contains(param.state) }.map(EntityParam::state)
//            val missingThis = if (context.contains(edge.src) == false) listOf(edge.src) else listOf()
            missingParams
        }
        is LinkedEdge -> calcRequirements(edge.edge, context)
        else -> listOf()
    }

    private val visited = mutableSetOf<Model>()
    private val haveMissingRequirements = mutableListOf<Pair<Model, List<State>>>()
    private val pending = PriorityQueue<Model>(ModelCompare())

    fun aStar(current: Model, goal: State?, edges: Set<Edge>): Boolean {
        if ((goal == null || current.state == goal) && current.actions == requiredActions) {
            return true
        }
        if (visited.contains(current)) {
            return false
        }
        visited += current
        val availableEdges = edges.filter { edge -> edge.src == current.state }
        val submittedModels = makeNewModel(current, availableEdges)
        if (submittedModels == 0) {
            print("")
            for (state in current.context) {
                val newModel = Model(state = state, props = getProps(state.machine, current), actions = current.actions)
                if (visited.contains(newModel)) {
                    continue
                }
                newModel.path = current.path
                newModel.stateProps = current.stateProps
                newModel.context = current.context
                pending.add(newModel)
            }
        }
        return false
    }

    private fun makeNewModel(current: Model, availableEdges: List<Edge>): Int {
        var submittedModels = 0
        for (edge in availableEdges) {
            if (edge is UsageEdge) {
                continue
            }
            val actions = edge.actions
            val requirements = calcRequirements(edge, current.context)
            val props = getProps(edge.dst.machine, current)
            val isAllowed = edge.allowTransition(props) && (if (edge is LinkedEdge) edge.edge.allowTransition(current.props) else true)
            val newProps = edge.propertyModifier(props)
            val newModel = Model(state = edge.dst, props = newProps, actions = makeActions(current.actions, actions))
            if (visited.contains(newModel)) {
                continue
            }
            newModel.path = current.path + edge
            newModel.stateProps = current.stateProps + Pair(newModel.state.machine, newModel.props)
            newModel.context = current.context.filterNot { it.machine == edge.dst.machine || if (edge is CastEdge) it == edge.src else false }.toSet() + edge.dst
            if (requirements.isNotEmpty()) {
                logger.debug("Model ${newModel.state} + edge $edge have requirements: $requirements")
                haveMissingRequirements += Pair(newModel, requirements)
                continue
            }
            val isProperAction = checkIsProperAction(actions, newModel)
            if (isAllowed && isProperAction) {
                pending.add(newModel)
                submittedModels++
            }
        }
        return submittedModels
    }

    private fun checkIsProperAction(actions: List<Action>, newModel: Model) = if (actions.isEmpty()) {
        true
    } else {
        requiredActions.containsAll(actions) && requiredActionsMultiset.all {
            pair -> newModel.actions.count { it == pair.key } <= pair.value
        }
    }
}

class PropsContext {
    var stateProps: Map<StateMachine, Map<String, Any>> = mapOf()
    val actionParams: MutableList<Pair<String, Expression>> = mutableListOf()
    var actions: List<Action> = listOf()

    var logger = LoggerFactory.getLogger(PropsContext::class.java)

    fun addEdgeFromTrace(locatedEdge: RouteExtractor.LocatedEdge) {
        val edge = locatedEdge.edge
        val props = makeProps(edge.dst)
        val isAllowed = edge.allowTransition(props)
        if (!isAllowed) logger.error("Not allowed: {} at {}", locatedEdge.edge, locatedEdge.node.begin.get())
        val newProps = edge.propertyModifier(props)
        stateProps += Pair(edge.dst.machine, newProps)
        for (action in edge.actions) {
            actions += action
            if (edge is ExpressionEdge) {
                for ((index, param) in edge.param.withIndex()) {
                    if (param is ActionParam) {
                        val expr = (locatedEdge.node as MethodCallExpr).arguments[index]
                        actionParams += Pair(param.propertyName, expr)
                    }
                }
            }
        }
    }

    fun makeProps(state: State): MutableMap<String, Any> {
        val existingProps = stateProps[state.machine]
        return if (existingProps != null) LinkedHashMap(existingProps) else mutableMapOf()
    }

    fun NonEmptyProps() = stateProps.filter { map -> map.value.isNotEmpty() }
}