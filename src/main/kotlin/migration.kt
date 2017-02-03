import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.javaparser.ASTHelper
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import mu.KotlinLogging
import java.io.File

/**
 * Created by artyom on 22.08.16.
 */

data class PendingExpression(val expression: Expression,
                             val edge: Edge,
                             val provides: Expression? = null,
                             val name: String? = null,
                             val hasReturnValue: Boolean = provides != null,
                             var makeVariable: Boolean = false)

data class Replacement(val oldNode: Node,
                       val pendingExpressions: List<PendingExpression>,
                       val removeOldNode: Boolean = pendingExpressions.isEmpty(),
                       val finalizerExpressions: List<PendingExpression> = listOf())

data class Route(val oldNode: Node,
                 val route: List<Edge>,
                 val edge: Edge,
                 var finalizerRoute: List<Edge> = listOf()) {
    val edgeInsertRules = mutableListOf<EdgeInsertRules>()
}

data class EdgeInsertRules(val edge: Edge,
                           val hasReturnValue: Boolean,
                           val makeStatement: Boolean)

class Migration(val library1: Library,
                val library2: Library,
                val codeElements: CodeElements,
                val functionName: String,
                val sourceFile: File,
                val invocations: List<RouteExtractor.Invocation>) {
    val dependencies: MutableMap<StateMachine, Expression> = mutableMapOf()
    // val pendingStmts = mutableListOf<Statement>()
    var nameGeneratorCounter = 0
    val replacements: MutableList<Replacement> = mutableListOf()
    val globalRoute: MutableList<Route> = mutableListOf()
    val needToMakeVariable: MutableMap<Pair<Route, Edge>, Boolean> = mutableMapOf()

    private val logger = KotlinLogging.logger {}

    val extractor = RouteExtractor(library1, codeElements, functionName, sourceFile)
    val routeMaker = RouteMaker(globalRoute, extractor, invocations, library1, library2, dependencies)
    val replacementPerformer = ReplacementPerformer(replacements, routeMaker)

    fun doMigration() {
        logger.info("Function: $functionName")
        routeMaker.makeRoutes()
        makeInsertRules()
//        calcIfNeedToMakeVariable()

        for (route in globalRoute) {
            replacements += when (route.edge) {
                is CallEdge -> migrateMethodCall(route)
                is ConstructorEdge -> migrateConstructorCall(route)
                is LinkedEdge -> migrateLinkedEdge(route)
                is AutoEdge -> Replacement(route.oldNode, listOf())
                else -> TODO()
            }
        }
        replacementPerformer.apply()
    }

//    private fun calcIfNeedToMakeVariable() {
//        val allSteps: MutableList<Pair<Route, Edge>> = mutableListOf()
//        for (route in globalRoute) {
//            for (step in route.route) {
//                allSteps += Pair(route, step)
//            }
//        }
//        for (step in allSteps) {
//            val count = allSteps.count { pair -> pair.second.src.machine == step.second.machine || pair.second is CallEdge && (pair.second as CallEdge).param.filterIsInstance<EntityParam>().any { it.machine == step.second.machine } }
//            needToMakeVariable.put(step, count > 1)
////            if (allSteps.drop(stepIndexed.index+1).any { furtherStep -> furtherStep.second.src == step.second.dst })
//        }
//    }

    private fun makeInsertRules() {
        val steps = mutableListOf<Triple<Int, Int, Edge>>()
        for (route in globalRoute.withIndex()) {
            for (edge in route.value.route.withIndex()) {
                steps += Triple(route.index, edge.index, edge.value)
            }
        }
        for ((routeIndex, edgeIndex, step) in steps) {
            val nextSteps = steps.filter { it.first > routeIndex || (it.first == routeIndex && it.second > edgeIndex) }.map { it.third }
            val usageCount = nextSteps.count { usesEdge(it, step) }
            val hasReturnValue = edgeHasReturnValue(step)
            globalRoute[routeIndex].edgeInsertRules += EdgeInsertRules(edge = step, hasReturnValue = hasReturnValue, makeStatement = !hasReturnValue || (usageCount > 1))
        }
    }

    private fun edgeHasReturnValue(edge: Edge): Boolean {
        return when (edge) {
            is LinkedEdge, is ConstructorEdge, is TemplateEdge -> true
            is CallEdge -> edge.hasReturnValue
            else -> false
        }
    }

    private fun associateEdges(edges: Collection<Edge>, node: Node) = edges.map { edge -> edge to node }

    private fun usesEdge(current: Edge, step: Edge): Boolean {
        val usesAsThis = (current.src.machine == step.dst.machine)
        val usesAsParam = if (current is ExpressionEdge) current.param.any { it is EntityParam && it.machine == step.dst.machine } else false
        val usesAsLinkedEdge = if (current is LinkedEdge) usesEdge(current.edge, step) else false
        return usesAsThis || usesAsParam || usesAsLinkedEdge // current.src.machine == step.dst.machine || if (current is ExpressionEdge) current.param.any { it is EntityParam && it.machine == step.dst.machine } else false
    }

    private fun migrateLinkedEdge(route: Route): Replacement {
        val oldVarName = getVariableNameFromExpression(route.oldNode)
        val pendingExpressions = applySteps(route.route, route.edgeInsertRules, oldVarName)
        if (pendingExpressions.isNotEmpty()) {
            val finalizerExpressions = applySteps(route.finalizerRoute, listOf(), null)
            return Replacement(oldNode = route.oldNode, pendingExpressions = pendingExpressions, finalizerExpressions = finalizerExpressions)
        } else {
            val newNode = dependencies[route.edge.dst.machine] ?: error("No such dependency")
            return Replacement(oldNode = route.oldNode, pendingExpressions = listOf(PendingExpression(expression = newNode, edge = route.edge, provides = newNode)))
        }
    }

    private fun migrateMethodCall(route: Route): Replacement {
        val pendingExpressions = applySteps(route.route, route.edgeInsertRules, null)
        val finalizerExpressions = applySteps(route.finalizerRoute, listOf(), null)
        return Replacement(oldNode = route.oldNode, pendingExpressions = pendingExpressions, finalizerExpressions = finalizerExpressions, removeOldNode = true)
    }

    private fun migrateConstructorCall(route: Route): Replacement {
        val oldVarName = getVariableNameFromExpression(route.oldNode)
        val pendingExpressions = applySteps(route.route, route.edgeInsertRules, oldVarName)
        val finalizerExpressions = applySteps(route.finalizerRoute, listOf(), null)
        return Replacement(oldNode = route.oldNode, pendingExpressions = pendingExpressions, finalizerExpressions = finalizerExpressions)
    }

    private fun applySteps(steps: List<Edge>, rules: List<EdgeInsertRules>, oldVarName: String?): List<PendingExpression> {
        val pendingExpr = mutableListOf<PendingExpression>()
        for ((index, step) in steps.withIndex()) {
            logger.info("    Step: " + step.label())
            val newExpressions: List<PendingExpression> = makeStep(step)
            val variableDeclarationReplacement = (step == steps.last()) && (oldVarName != null)
            val name = if (variableDeclarationReplacement) oldVarName else generateVariableName(step)
            val rule = rules[index]
            val namedExpressions = newExpressions.map { when {
                rule.makeStatement || variableDeclarationReplacement -> it.copy(provides = NameExpr(name), name = name, hasReturnValue = rule.hasReturnValue, makeVariable = true)
                rule.hasReturnValue -> it.copy(provides = it.expression, hasReturnValue = true)
                else -> it
            } }
            logger.info("Received expressions: " + namedExpressions.toString())
            for (expr in namedExpressions) {
                addToContext(expr)
            }
            pendingExpr.addAll(namedExpressions)
        }
        return pendingExpr
    }

    private fun makeStep(step: Edge) = when (step) {
        is CallEdge, is LinkedEdge, is TemplateEdge, is ConstructorEdge -> makeSimpleEdge(step)
        is UsageEdge, is AutoEdge -> emptyList()
        is MakeArrayEdge -> TODO() // makeArray(step.action)
        else -> TODO("Unknown action!")
    }

    private fun makeSimpleEdge(step: Edge): List<PendingExpression> {
        return listOf(PendingExpression(edge = step, expression = makeExpression(step)))
    }

    private fun generateVariableName(step: Edge) = "migration_${step.dst.machine.name}_${nameGeneratorCounter++}"

    private fun addToContext(pendingExpression: PendingExpression) {
        if (pendingExpression.hasReturnValue && pendingExpression.provides != null) {
            dependencies[pendingExpression.edge.dst.machine] = pendingExpression.provides
        }
    }

    private fun makeExpression(step: Edge): Expression = when (step) {
        is LinkedEdge -> makeExpression(step.edge)
        is TemplateEdge -> makeTemplateExpression(step)
        is ConstructorEdge -> makeConstructorExpression(step)
        is UsageEdge -> makeExpression(step.edge)
        is CallEdge -> makeCallExpression(step)
        else -> TODO()
    }

    private fun getVariableNameFromExpression(methodCall: Node): String? {
        val parent = methodCall.parentNode
        return if (parent is VariableDeclarator) parent.id.name else null
    }

    private fun makeCallExpressionParams(edge: CallEdge): CallExpressionParams {
        val scope = if (edge.isStatic) {
            NameExpr(edge.machine.type())
        } else {
            checkNotNull(dependencies[edge.machine])
        }

        val fetched = mutableListOf<Pair<String, Expression>>()

        val args = edge.param.map { param -> when (param) {
            is EntityParam -> checkNotNull(dependencies[param.machine])
            is ActionParam -> {
                val pair = routeMaker.srcProps.actionParams.first { param.propertyName == it.first }
                fetched += pair
                NameExpr(pair.second.toString())
            }
            is ConstParam -> NameExpr(param.value)
            else -> TODO()
        } }

        for (pair in fetched) {
            routeMaker.srcProps.actionParams -= pair
        }

        return CallExpressionParams(scope, args)
    }

    private fun makeCallExpression(step: CallEdge): Expression {
        val callParams = makeCallExpressionParams(step)
        val expr = MethodCallExpr(callParams.scope, step.methodName, callParams.args)
        return expr
    }

    private fun makeTemplateExpression(step: TemplateEdge): Expression {
        val stringParams = step.templateParams.mapValues { state -> checkNotNull(dependencies[state.value.machine].toString()) }
        return templateIntoAST(fillPlaceholders(step.template, stringParams))
    }

    private fun makeConstructorExpression(step: ConstructorEdge): Expression {
        val params = step.param.filterIsInstance<EntityParam>().map { param -> checkNotNull(dependencies[param.machine]) }
        val expr = ObjectCreationExpr(null, ClassOrInterfaceType(library2.getType(step.dst.machine, routeMaker.props[step.dst.machine])), params)
        return expr
    }
}

class ReplacementPerformer(val replacements: List<Replacement>,
                           val routeMaker: RouteMaker) {
    val removedStmts = mutableListOf<Statement>()

    private val logger = KotlinLogging.logger {}

    fun apply() {
        for (replacement in replacements) {
            applyReplacement(replacement)
        }
        for (stmt in removedStmts) {
//            if (replacements.flatMap { it.pendingExpressions }.none { getBlockStmt(it.expression).first == stmt } ) {
                (stmt.parentNode as? BlockStmt)?.stmts?.remove(stmt)
//            }
        }
    }

    fun applyReplacement(replacement: Replacement) {
        val oldExpr = replacement.oldNode
        val (statement, blockStmt) = getBlockStmt(oldExpr)
        val statements = blockStmt.stmts

        if (replacement.pendingExpressions.isNotEmpty()) {
            val pos = statements.indexOf(statement)
            val pushedPendingExpressions = replacement.pendingExpressions.filter { it.makeVariable }
            val pendingStatements = pushedPendingExpressions.map { pending -> makeNewStatement(pending) }
            statements.addAll(pos, pendingStatements)
            for (stmt in pendingStatements) {
                stmt.parentNode = blockStmt
            }

            val lastExpr = replacement.pendingExpressions.last()
            if (lastExpr.hasReturnValue && oldExpr.parentNode !is VariableDeclarator) {
                val expr = lastExpr.provides!!
                replaceNode(expr, oldExpr)
            } else {
                removedStmts += statement
            }
        } else if (replacement.oldNode.parentNode is ExpressionStmt || replacement.oldNode.parentNode is VariableDeclarator) {
            logger.info("Remove $statement")
            removedStmts += statement
        }

        makeFinalizers(statements, replacement)
    }

    private fun makeFinalizers(statements: MutableList<Statement>, replacement: Replacement) {
        val lastNotReturnStatement = statements.indexOfLast { stmt -> stmt !is ReturnStmt }
        if (replacement.finalizerExpressions.isNotEmpty()) {
            val newStatements = replacement.finalizerExpressions.map { ExpressionStmt(it.expression) }
            statements.addAll(lastNotReturnStatement + 1, newStatements)
        }
    }

    private fun replaceNode(newExpr: Expression, oldExpr: Node) {
        val parent = oldExpr.parentNode
        when (parent) {
            is AssignExpr -> parent.value = newExpr
            is BinaryExpr -> parent.left = newExpr
            is ObjectCreationExpr -> {
                val argsPos = parent.args.indexOf(oldExpr)
                parent.args.set(argsPos, newExpr)
                newExpr.parentNode = parent
            }
            is MethodCallExpr -> {
                val argsPos = parent.args.indexOf(oldExpr)
                if (argsPos >= 0) {
                    parent.args.set(argsPos, newExpr)
                } else {
                    parent.scope = newExpr
                }
                newExpr.parentNode = parent
            }
            is ReturnStmt -> parent.expr = newExpr
            is CastExpr -> parent.expr = newExpr
            is ExpressionStmt -> parent.expression = newExpr
            else -> error("Don't know how to insert into " + parent.toString())
        }
    }

    private fun getBlockStmt(initialNode: Node): Pair<Statement, BlockStmt> {
        var node: Node = initialNode
        while (node.parentNode is BlockStmt == false) {
            node = node.parentNode
        }
        return Pair(node as Statement, node.parentNode as BlockStmt)
    }

    // private fun getNewVarName(pendingStmts: List<PendingExpression>): String = pendingStmts.map { stmt -> stmt.provides }.lastOrNull() ?: error("New statement should provide a variable")

    private fun makeNewStatement(pendingExpression: PendingExpression): Statement {
        if (pendingExpression.hasReturnValue) {
            val machine = pendingExpression.edge.dst.machine
            return makeNewVariable(
                    type = checkNotNull(machine.library.getType(machine, routeMaker.props[machine])),
                    name = checkNotNull(pendingExpression.name),
                    initExpr = pendingExpression.expression
            )
        } else {
            return ExpressionStmt(pendingExpression.expression)
        }
    }

    private fun makeNewVariable(type: String, name: String, initExpr: Expression?): Statement {
        val newVariable = ASTHelper.createVariableDeclarationExpr(ClassOrInterfaceType(type), name)
        if (initExpr != null) {
            newVariable.vars.first().init = initExpr
        }
        return ExpressionStmt(newVariable)
    }
}

class RouteExtractor(val library1: Library,
                     val codeElements: CodeElements,
                     val functionName: String,
                     val sourceFile: File) {
    private val logger = KotlinLogging.logger {}

    fun extractFromJSON(invocations: List<Invocation>): List<LocatedEdge> {
        val localInvocations = invocations.filter { inv -> inv.callerName == functionName && inv.filename == this.sourceFile.name }

        val usedEdges: MutableList<LocatedEdge> = mutableListOf()
        val edges = library1.stateMachines.flatMap { machine -> machine.edges }
        for (invocation in localInvocations) {
            if (invocation.kind == "method-call") {
                val callEdge = edges.filterIsInstance<CallEdge>().firstOrNull { edge ->
                    edge.methodName == invocation.name &&
                            invocation.simpleType() == edge.machine.type()
                }
                if (callEdge == null) {
//                    println("Cannot find edge for $invocation")
                    continue
                }
                val methodCall = codeElements.methodCalls.first { call -> call.name == invocation.name && call.end.line == invocation.line }
                usedEdges += LocatedEdge(callEdge, methodCall)
                if (methodCall.parentNode is ExpressionStmt == false) {
                    val linkedEdge = callEdge.linkedEdge
                    if (linkedEdge != null) {
                        usedEdges += LocatedEdge(linkedEdge, methodCall)
//                        usedEdges += associateEdges(linkedEdge.getSubsequentAutoEdges(), methodCall)
                    } else {
                        logger.error("Missing linked node")
                    }
                }
            } else if (invocation.kind == "constructor-call") {
                val constructorEdge = edges.filterIsInstance<ConstructorEdge>().firstOrNull { edge -> invocation.simpleType() == edge.machine.type() }
                if (constructorEdge == null) {
//                    println("Cannot find edge for $invocation")
                    continue
                }
                val constructorCall = codeElements.objectCreation.firstOrNull { objectCreation -> (objectCreation.type.toString() == invocation.simpleType()) && (objectCreation.end.line == invocation.line) }
                if (constructorCall == null) {
                    error("Cannot find node for $invocation")
                }
                usedEdges += LocatedEdge(constructorEdge, constructorCall)
            }
        }
        val usedEdgesCleanedUp = usedEdges.distinct()
        if (usedEdgesCleanedUp.isNotEmpty()) {
            logger.info("--- Used edges:")
            for (edge in usedEdgesCleanedUp) {
                logger.info(edge.edge.label())
            }
            logger.info("---")
            graphvizRender(toDOT(library1, usedEdgesCleanedUp.map { usage -> usage.edge }), "extracted_" + functionName)
        }
        return usedEdgesCleanedUp.distinct()
    }

    fun makeProps(edges: List<LocatedEdge>): PropsContext {
        val props: PropsContext = PropsContext()
        for (edge in edges) {
            props.addEdgeFromTrace(edge)
        }
        return props
    }

    fun checkRoute(route: List<Edge>) {
        route.filterNot { edge -> edge is UsageEdge }.fold(listOf<Edge>(), { visited, edge ->
            if (visited.isEmpty() || visited.any { prevEdge -> edge.src == prevEdge.dst }) {
                visited + edge
            } else {
                error("Unexpected edge! Visited: ${printRoute(visited)}, edge: ${edge.label()}")
            }
        })
    }

    private fun printRoute(route: List<Edge>) {
//        logger.info("Route from %s to %s: ".format(src.stateAndMachineName(),
//                dst.stateAndMachineName()))
        for (state in route.withIndex()) {
            logger.info("%d: %s".format(state.index, state.value.label()))
        }
    }

    data class LocatedEdge(
            val edge: Edge,
            val node: Node,
            val nodeLine: Int = node.end.line,
            val nodeColumn: Int = node.end.column
    )

    @JsonIgnoreProperties("node")
    data class Invocation(
            val name: String,
            val filename: String,
            val line: Int = 0,
            val type: String,
            val callerName: String,
            val kind: String,
            val args: List<String>,
            val id: String,
            val place: String) {
        fun simpleType() = type.substringAfterLast('.').replace('$', '.')
    }
}

class RouteMaker(val globalRoute: MutableList<Route>,
                 val extractor: RouteExtractor,
                 val invocations: List<RouteExtractor.Invocation>,
                 val library1: Library,
                 val library2: Library,
                 val dependencies: MutableMap<StateMachine, Expression>) {
    val context: MutableSet<State> = mutableSetOf()
    val props: MutableMap<StateMachine, Map<String, Any>> = mutableMapOf()
    val actionsQueue = mutableListOf<Action>()
    lateinit var srcProps: PropsContext

    private val logger = KotlinLogging.logger {}

    fun makeRoutes() {
        val path = extractor.extractFromJSON(invocations)
        srcProps = extractor.makeProps(path)
        props += library2.stateMachines.map { machine -> machine to machine.migrateProperties(srcProps.stateProps) }
//        checkRoute(path)

        fillContextWithInit()
        for (usage in path) {
            val edge = usage.edge
            logger.info("Processing " + edge.label() + "... ")
            extractDependenciesFromNode(edge, usage.node)
            val actions = edge.actions
            if (edge.canBeSkipped()) {
                logger.info("  Makes a loop, skipping")
                for (action in actions.filter { it.withSideEffects == false }) {
                    actionsQueue += action
                }
                if (globalRoute.any { route -> route.oldNode == usage.node }) {
                    continue
                }
                globalRoute += Route(oldNode = usage.node, route = listOf(), edge = AutoEdge(edge.machine)) // TODO
                continue
            }
            var dst: State? = edge.dst
            if (edge.dst.machine in library2.stateMachines == false) {
                if (actions.isNotEmpty()) {
                    dst = null
                } else {
                    if (globalRoute.any { route -> route.oldNode == usage.node }) {
                        continue
                    }
                    globalRoute += Route(oldNode = usage.node, route = listOf(), edge = AutoEdge(edge.machine)) // TODO
                    continue
                }
            }
            val route = findRoute(context, dst, actionsQueue + actions)
            actionsQueue.clear()
            props += route.stateProps
            for (step in route.path) {
                addToContext(step.dst)
            }
            globalRoute += Route(oldNode = usage.node, route = route.path, edge = edge)
        }
        // addFinalizers() // TODO
    }

//    private fun extendRoute(route: List<Edge>): List<Edge> {
//        val outputRoute = mutableListOf<Edge>()
//        for (step in route) {
//            when (step) {
//                is UsageEdge -> {
//                    if ((step.edge is CallEdge && step.edge.isStatic) == false) {
////                        val dependencyStep = step.edge
//                        val newRoute = findRoute(context, step.src, null) // dependencyStep.action
//                        outputRoute += newRoute.path
//                        outputRoute += step.edge
//                        for (edge in newRoute.path) {
//                            context.removeAll { it.machine == edge.dst.machine }
//                            context += edge.dst
//                        }
//                        props += newRoute.stateProps
//                    } else {
//                        outputRoute += step
//                        context.removeAll { it.machine == step.dst.machine }
//                        context += step.dst
//                    }
//                }
//                is ExpressionEdge -> {
//                    val missingDeps = step.param.filterIsInstance<EntityParam>().filterNot { param -> context.contains(param.state) }.map(EntityParam::state) + (if (context.contains(step.src) == false) listOf(step.src) else listOf())
//                    for (dependency in missingDeps) {
//                        val newRoute = findRoute(context, dependency, null)
//                        outputRoute += newRoute.path
//                        for (edge in newRoute.path) {
//                            context.removeAll { it.machine == edge.dst.machine }
//                            context += edge.dst
//                        }
//                        props += newRoute.stateProps
//                    }
//                    outputRoute += step
//                    context.removeAll { it.machine == step.dst.machine }
//                    context += step.dst
//                }
//                is LinkedEdge -> {
//                    val missingDeps = step.edge.param.filterIsInstance<EntityParam>().filterNot { param -> context.contains(param.state) }
//                    for (dependency in missingDeps) {
//                        val newRoute = findRoute(context, dependency.state, null)
//                        outputRoute += newRoute.path
//                        for (edge in newRoute.path) {
//                            context.removeAll { it.machine == edge.dst.machine }
//                            context += edge.dst
//                        }
//                        props += newRoute.stateProps
//                    }
//                    outputRoute += step
//                    context.removeAll { it.machine == step.dst.machine }
//                    context += step.dst
//                }
//            }
//        }
//        return outputRoute
//    }

    private fun fillContextWithInit() {
        context += library2.stateMachines.flatMap { machine -> machine.states }.filter(State::isInit).toMutableSet()
    }

    private fun addFinalizers() {
        val contextMachines = context.map(State::machine)
        val needsFinalization = contextMachines.filter { machine -> library2.stateMachines.contains(machine) && machine.states.any(State::isFinal) }
        for (machine in needsFinalization) {
            val route = findRoute(context, machine.getFinalState(), listOf()).path
            if (route.isNotEmpty()) {
                val firstOccurence = globalRoute.first { route -> route.route.any { edge -> edge.machine == machine } }
                firstOccurence.finalizerRoute = route
            }
        }
    }

    private fun findRoute(src: Set<State>, dst: State?, actions: List<Action>): PathFinder.Model {
        logger.info("  Searching route from ${src.joinToString(transform = State::stateAndMachineName)} to ${dst?.stateAndMachineName()} with ${actions.joinToString(transform = Action::name)}")
        val edges = library2.stateMachines.flatMap(StateMachine::edges).toSet()
        val pathFinder = PathFinder(edges, src, props, actions.sorted())
        pathFinder.findPath(dst)
        return pathFinder.resultModel
    }

//    private fun getUsages(methodName: String) =
//            codeElements.methodCalls.filter { methodCall -> methodCall.name == methodName }

    private fun extractDependenciesFromNode(edge: Edge, node: Node) = when {
        node is MethodCallExpr && edge is CallEdge -> extractDependenciesFromMethod(edge, node)
        node is MethodCallExpr && edge is LinkedEdge -> extractDependenciesFromMethod(edge.edge as CallEdge, node)
        node is ObjectCreationExpr && edge is ConstructorEdge -> extractDependenciesFromConstructor(edge, node)
        else -> TODO()
    }

    private fun extractDependenciesFromMethod(edge: CallEdge, methodCall: MethodCallExpr) {
        val args = edge.param.filterIsInstance<EntityParam>().mapIndexed { i, param -> param.state to methodCall.args[i] }.toMap()
        val scope = (edge.src to methodCall.scope)
        val deps = args + scope
        addDependenciesToContext(deps)
    }

    private fun extractDependenciesFromConstructor(edge: ConstructorEdge, constructorCall: ObjectCreationExpr) {
        val args = edge.param.filterIsInstance<EntityParam>().mapIndexed { i, param -> param.state to constructorCall.args[i] }.toMap()
//        val scope = (edge.src to methodCall.scope)
        val deps = args // TODO: scope
        addDependenciesToContext(deps)
    }

    private fun addDependenciesToContext(deps: Map<State, Expression?>) {
        for ((dep, expr) in deps) {
            logger.info("Machine ${dep.machine.label()} is now in state ${dep.label()}")
            if (context.contains(dep)) {
                continue
            }
            addToContext(dep)

            if (expr != null) {
                logger.info("Machine ${dep.machine.label()} can be accessed by expr \"$expr\"")
                dependencies[dep.machine] = expr

                val autoDeps = library1.edges.filterIsInstance<AutoEdge>().filter { edge -> edge.src == dep }
                for (autoDep in autoDeps) {
                    val dstMachine = autoDep.dst.machine
                    logger.info("Additionally machine ${dstMachine.label()} can be accessed by expr \"$expr\"")
                    addToContext(autoDep.dst)
                    dependencies[dstMachine] = expr
                }
            }
        }
    }

    private fun addToContext(state: State) {
        val removed = context.filter { it.machine == state.machine }
        context.removeAll(removed)
        context += state
    }

    private fun getDependencyStep(step: Edge) = library2.stateMachines.flatMap { machine -> machine.edges }
            .first { edge: Edge -> (edge.src != edge.dst) && (edge is UsageEdge == false) && (edge.dst == step.dst) }
}