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
import java.io.File

/**
 * Created by artyom on 22.08.16.
 */

data class PendingExpression(val expression: Expression,
                             val edge: Edge,
                             val provides: String? = null,
                             val depends: List<String> = listOf())

data class Replacement(val oldNode: Node,
                       val pendingExpressions: List<PendingExpression>,
                       val removeOldNode: Boolean = pendingExpressions.isEmpty(),
                       val finalizerExpressions: List<PendingExpression> = listOf(),
                       var makeVariable: Boolean = true)

data class Route(val oldNode: Node,
                 val route: List<Edge>,
                 val edge: Edge,
                 var finalizerRoute: List<Edge> = listOf())

class Migration(val library1: Library,
                val library2: Library,
                val codeElements: CodeElements,
                val functionName: String,
                val sourceFile: File,
                val traceFile: File) {
    val dependencies: MutableMap<StateMachine, Expression> = mutableMapOf()
    // val pendingStmts = mutableListOf<Statement>()
    var nameGeneratorCounter = 0
    val replacements: MutableList<Replacement> = mutableListOf()
    val globalRoute: MutableList<Route> = mutableListOf()
    val needToMakeVariable: MutableMap<Pair<Route, Edge>, Boolean> = mutableMapOf()

    val extractor = RouteExtractor(library1, codeElements, functionName, sourceFile)
    val routeMaker = RouteMaker(globalRoute, extractor, traceFile, replacements, library1, library2, dependencies)
    val replacementPerformer = ReplacementPerformer(replacements, library2)

    fun doMigration() {
        println("Function: $functionName")
        routeMaker.makeRoutes()
        calcIfNeedToMakeVariable()

        for (route in globalRoute) {
            replacements += when (route.edge) {
                is CallEdge -> {
                    println("  Has a call action")
                    migrateMethodCall(route)
                }
                is ConstructorEdge -> {
                    println("  Has a constructor action")
                    migrateConstructorCall(route)
                }
                is LinkedEdge -> {
                    println("  Has a linked action")
                    migrateLinkedEdge(route)
                }
                else -> TODO()
            }
        }
        replacementPerformer.apply()
    }

    private fun calcIfNeedToMakeVariable() {
        val allSteps: MutableList<Pair<Route, Edge>> = mutableListOf()
        for (route in globalRoute) {
            for (step in route.route) {
                allSteps += Pair(route, step)
            }
        }
        for (step in allSteps) {
            val count = allSteps.count { pair -> pair.second.src.machine == step.second.machine || pair.second is CallEdge && (pair.second as CallEdge).param.any { it.machine == step.second.machine } }
            needToMakeVariable.put(step, count > 1)
//            if (allSteps.drop(stepIndexed.index+1).any { furtherStep -> furtherStep.second.src == step.second.dst })
        }
    }

    private fun associateEdges(edges: Collection<Edge>, node: Node) = edges.map { edge -> edge to node }

    private fun migrateLinkedEdge(route: Route): Replacement {
        val oldVarName = getVariableNameFromExpression(route.oldNode)
        val pendingExpressions = applySteps(route.route, oldVarName)
        if (pendingExpressions.isNotEmpty()) {
            val finalizerExpressions = applySteps(route.finalizerRoute, null)
            return Replacement(oldNode = route.oldNode, pendingExpressions = pendingExpressions, finalizerExpressions = finalizerExpressions)
        } else {
            val newNode = dependencies[route.edge.dst.machine] ?: error("No such dependency")
            return Replacement(oldNode = route.oldNode, pendingExpressions = listOf(PendingExpression(expression = newNode, edge = route.edge)), makeVariable = false)
        }
    }

    private fun migrateMethodCall(route: Route): Replacement {
        val pendingExpressions = applySteps(route.route, null)
        val finalizerExpressions = applySteps(route.finalizerRoute, null)
        return Replacement(oldNode = route.oldNode, pendingExpressions = pendingExpressions, finalizerExpressions = finalizerExpressions)
    }

    private fun migrateConstructorCall(route: Route): Replacement {
        val oldVarName = getVariableNameFromExpression(route.oldNode)
        val pendingExpressions = applySteps(route.route, oldVarName)
        val finalizerExpressions = applySteps(route.finalizerRoute, null)
        return Replacement(oldNode = route.oldNode, pendingExpressions = pendingExpressions, finalizerExpressions = finalizerExpressions)
    }

    private fun applySteps(steps: List<Edge>, oldVarName: String?): List<PendingExpression> {
        val pendingExpr = mutableListOf<PendingExpression>()
        for (step in steps) {
            println("    Step: " + step.label())
            val newExpressions: List<PendingExpression> = makeStep(step)
            val name = if ((step == steps.last()) && (oldVarName != null)) oldVarName else generateVariableName(step)
            val namedExpressions = newExpressions.map { it.copy(provides = name) }
            println("Received expressions: " + namedExpressions.toString())
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
        if (pendingExpression.provides != null) {
            dependencies[pendingExpression.edge.dst.machine] = NameExpr(pendingExpression.provides)
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

        val args = edge.param.map { param -> checkNotNull(dependencies[param.machine]) }

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
        val params = step.param.map { param -> checkNotNull(dependencies[param.machine]) }
        val expr = ObjectCreationExpr(null, ClassOrInterfaceType(step.dst.machine.type()), params)
        return expr
    }
}

class ReplacementPerformer(val replacements: List<Replacement>,
                           val library2: Library) {
    fun apply() {
        for (replacement in replacements) {
            applyReplacement(replacement)
        }
    }

    fun applyReplacement(replacement: Replacement) {
        val oldExpr = replacement.oldNode
        val (statement, blockStmt) = getBlockStmt(oldExpr)
        val statements = blockStmt.stmts

        if (replacement.pendingExpressions.isNotEmpty()) {
            val pos = statements.indexOf(statement)
            val pushedPendingExpressions = if (replacement.makeVariable) replacement.pendingExpressions else replacement.pendingExpressions.dropLast(1)
            val pendingStatements = pushedPendingExpressions.map { pending -> makeNewStatement(pending) }
            statements.addAll(pos, pendingStatements)
            for (stmt in pushedPendingExpressions) {
                stmt.expression.parentNode = blockStmt
            }

            val newExpr = if (replacement.makeVariable) {
                NameExpr(getNewVarName(replacement.pendingExpressions))
            } else {
                replacement.pendingExpressions.last().expression
            }
            replaceNode(newExpr, oldExpr)
        } else if (replacement.oldNode.parentNode is ExpressionStmt || replacement.oldNode.parentNode is VariableDeclarator) {
            statements.remove(statement)
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
        val (statement, blockStmt) = getBlockStmt(oldExpr)
        val statements = blockStmt.stmts
        val parent = oldExpr.parentNode
        when (parent) {
            is VariableDeclarator -> {
                statements.remove(statement)
                statement.parentNode = null
            }
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

    private fun getNewVarName(pendingStmts: List<PendingExpression>): String = pendingStmts.map { stmt -> stmt.provides }.lastOrNull() ?: error("New statement should provide a variable")

    private fun makeNewStatement(pendingExpression: PendingExpression): Statement {
        if (pendingExpression.provides != null) {
            return makeNewVariable(
                    type = checkNotNull(library2.machineSimpleTypes[pendingExpression.edge.dst.machine]),
                    name = pendingExpression.provides,
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
    fun extractFromJSON(traceFile: File): List<LocatedEdge> {
        val invocations = ObjectMapper().registerKotlinModule().readValue<List<Invocation>>(traceFile)
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
                        println("Missing linked node")
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
            println("--- Used edges:")
            for (edge in usedEdgesCleanedUp) {
                println(edge.edge.label())
            }
            println("---")
            graphvizRender(toDOT(library1, usedEdgesCleanedUp.map { usage -> usage.edge }), "extracted_" + functionName)
        }
        return usedEdgesCleanedUp.distinct()
    }

    fun makeProps(edges: List<LocatedEdge>): PropsContext {
        val props: PropsContext = PropsContext()
        for (edge in edges) {
            props.addEdgeFromTrace(edge.edge)
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
//        println("Route from %s to %s: ".format(src.stateAndMachineName(),
//                dst.stateAndMachineName()))
        for (state in route.withIndex()) {
            println("%d: %s".format(state.index, state.value.label()))
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
                 val traceFile: File,
                 val replacements: MutableList<Replacement>,
                 val library1: Library,
                 val library2: Library,
                 val dependencies: MutableMap<StateMachine, Expression>) {
    val context: MutableSet<State> = mutableSetOf()
    val props: MutableMap<State, Map<String, Any>> = mutableMapOf()

    fun makeRoutes() {
        val path = extractor.extractFromJSON(traceFile)
        val srcProps = extractor.makeProps(path)
//        checkRoute(path)

        fillContextWithInit()
        for (usage in path) {
            val edge = usage.edge
            println("Processing " + edge.label() + "... ")
            if (edge.src == edge.dst) {
                println("  Makes a loop, skipping")
                continue
            }
            extractDependenciesFromNode(edge, usage.node)
            if (edge.dst.machine in library2.stateMachines == false) {
                replacements += Replacement(usage.node, listOf())
                continue
            }
            val route = findRoute(context, edge.dst)
            props += route.stateProps
            globalRoute += Route(oldNode = usage.node, route = extendRoute(route.path), edge = edge)
        }
        addFinalizers()
    }

    private fun extendRoute(route: List<Edge>): List<Edge> {
        val outputRoute = mutableListOf<Edge>()
        for (step in route) {
            when (step) {
                is UsageEdge -> {
                    if ((step.edge is CallEdge && step.edge.isStatic) == false) {
                        val dependencyStep = getDependencyStep(step)
                        val newRoute = findRoute(context, dependencyStep.src)
                        outputRoute += newRoute.path
                        outputRoute += dependencyStep
                        for (edge in newRoute.path) {
                            context.removeAll { it.machine == edge.dst.machine }
                            context += edge.dst
                        }
                        props += newRoute.stateProps
                    }
                }
                is ConstructorEdge -> {
                    val missingDeps = step.param.filterNot { param -> context.contains(param.state) }
                    for (dependency in missingDeps) {
                        val newRoute = findRoute(context, dependency.state)
                        outputRoute += newRoute.path
                        for (edge in newRoute.path) {
                            context.removeAll { it.machine == edge.dst.machine }
                            context += edge.dst
                        }
                        props += newRoute.stateProps
                    }
                }
            }
            outputRoute += step
            context.removeAll { it.machine == step.dst.machine }
            context += step.dst
        }
        return outputRoute
    }

    private fun fillContextWithInit() {
        context += library2.stateMachines.flatMap { machine -> machine.states }.filter(State::isInit).toMutableSet()
    }

    private fun addFinalizers() {
        val contextMachines = context.map(State::machine)
        val needsFinalization = contextMachines.filter { machine -> library2.stateMachines.contains(machine) && machine.states.any(State::isFinal) }
        for (machine in needsFinalization) {
            val route = findRoute(context, machine.getFinalState()).path
            if (route.isNotEmpty()) {
                val firstOccurence = globalRoute.first { route -> route.route.any { edge -> edge.machine == machine } }
                firstOccurence.finalizerRoute = route
            }
        }
    }

    private fun findRoute(src: Set<State>, dst: State): PathFinder.Model {
        println("  Searching route from %s to %s".format(src.joinToString(transform = State::stateAndMachineName), dst.stateAndMachineName()))
        val edges = library2.stateMachines.flatMap(StateMachine::edges).toSet()
        val pathFinder = PathFinder(edges, src, props)
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
        val args = edge.param.mapIndexed { i, param -> param.state to methodCall.args[i] }.toMap()
        val scope = (edge.src to methodCall.scope)
        val deps = args + scope
        addDependenciesToContext(deps)
    }

    private fun extractDependenciesFromConstructor(edge: ConstructorEdge, constructorCall: ObjectCreationExpr) {
        val args = edge.param.mapIndexed { i, param -> param.state to constructorCall.args[i] }.toMap()
//        val scope = (edge.src to methodCall.scope)
        val deps = args // TODO: scope
        addDependenciesToContext(deps)
    }

    private fun addDependenciesToContext(deps: Map<State, Expression?>) {
        for ((dep, expr) in deps) {
            println("Machine ${dep.machine.label()} is now in state ${dep.label()}")
            if (context.contains(dep)) {
                continue
            }
            addToContext(dep)

            if (expr != null) {
                println("Machine ${dep.machine.label()} can be accessed by expr \"$expr\"")
                dependencies[dep.machine] = expr

                val autoDeps = library1.edges.filterIsInstance<AutoEdge>().filter { edge -> edge.src == dep }
                for (autoDep in autoDeps) {
                    val dstMachine = autoDep.dst.machine
                    println("Additionally machine ${dstMachine.label()} can be accessed by expr \"$expr\"")
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