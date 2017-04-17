import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import mu.KotlinLogging
import java.io.File
import java.util.*
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver



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
                val invocations: GroupedInvocation,
                val project: GradleProject) {
    val dependencies: MutableMap<StateMachine, Expression> = mutableMapOf()
    // val pendingStmts = mutableListOf<Statement>()
    var nameGeneratorCounter = 0
    val replacements: MutableList<Replacement> = mutableListOf()
    val globalRoute: MutableList<Route> = mutableListOf()

    private val logger = KotlinLogging.logger {}

    val ui = UserInteraction(library1.name, library2.name, sourceFile.relativeToOrSelf(File(".").absoluteFile).toString()) // TODO: Dirty hack

    val extractor = RouteExtractor(library1, codeElements, functionName, sourceFile, project)
    val routeMaker = RouteMaker(globalRoute, extractor, invocations, library1, library2, dependencies, ui)
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

    private fun edgeHasReturnValue(edge: Edge): Boolean = when (edge) {
        is LinkedEdge, is ConstructorEdge, is TemplateEdge, is CastEdge -> true
        is CallEdge -> edge.hasReturnValue
        else -> false
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
        is CallEdge, is LinkedEdge, is TemplateEdge, is ConstructorEdge, is CastEdge -> makeSimpleEdge(step)
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
        is CastEdge -> makeCastExpression(step)
        else -> TODO()
    }

    private fun getVariableNameFromExpression(methodCall: Node): String? {
        val parent = methodCall.parentNode.unpack()
        return if (parent is VariableDeclarator) parent.name.identifier else null
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
        val expr = MethodCallExpr(callParams.scope, step.methodName).setArguments(callParams.args.toNodeList())
        return expr
    }

    private fun makeTemplateExpression(step: TemplateEdge): Expression {
        val stringParams = step.templateParams.mapValues { state -> checkNotNull(dependencies[state.value.machine].toString()) }
        return templateIntoAST(fillPlaceholders(step.template, stringParams))
    }

    private fun makeCastExpression(step: CastEdge): Expression {
        if (step.explicitCast) {
            val type = library2.getType(step.dst.machine, null)
            val expr = CastExpr(ClassOrInterfaceType(type), dependencies[step.machine])
            return EnclosedExpr(expr)
        } else {
            return checkNotNull(dependencies[step.machine])
        }
    }

    private fun makeConstructorExpression(step: ConstructorEdge): Expression {
        val params = step.param.filterIsInstance<EntityParam>().map { param -> checkNotNull(dependencies[param.machine]) }
        val expr = ObjectCreationExpr(null, ClassOrInterfaceType(library2.getType(step.dst.machine, routeMaker.props[step.dst.machine])), params.toNodeList())
        return expr
    }

    fun migrateClassMembers(codeElements: CodeElements) {
        for (classDecl in codeElements.classes) {
            val fields = classDecl.members.filterIsInstance<FieldDeclaration>()
            for (field in fields.toList()) {
                if (field.variables.size > 1) {
                    continue
                }
                val fieldType = field.elementType.toString()
                val machine = getMachineForType(fieldType, library1) ?: continue
                val newType = getNewType(machine, library2, field)
                if (newType != null) {
                    field.variables.first().type = newType
                } else {
                    field.remove()
                }
            }
        }
    }

    fun migrateFunctionArguments(methodDecl: MethodOrConstructorDeclaration) {
        val node = methodDecl.get()
        if (node is ConstructorDeclaration) {
            val args = node.parameters
            for (arg in args) {
                val argType = arg.type.toString()
                val machine = getMachineForType(argType, library1) ?: continue
                arg.type = getNewType(machine, library2, arg)
            }
        }
    }

    fun migrateReturnValue(methodDecl: MethodOrConstructorDeclaration) {
        val node = methodDecl.get()
        if (node is MethodDeclaration) {
            val machine = getMachineForType(node.type.toString(), library1) ?: return
            node.type = getNewType(machine, library2, node)
        }
    }

    private fun getMachineForType(oldType: String, library1: Library): StateMachine? = library1.machineTypes.entries.firstOrNull { (_, type) -> library1.simpleType(type) == oldType }?.key

    private fun getNewType(machine: StateMachine, library2: Library, node: Node): ClassOrInterfaceType? {
        val replacementMachine = if (library2.machineTypes.contains(machine)) {
            machine
        } else {
            val replacements = library2.stateMachines.map { it.label() } + "Remove"
            val answer = ui.makeDecision("Replacement for ${machine.label()} at ${node.begin.get()}", replacements)
            if (answer == "Remove") {
                return null
            }
            library2.stateMachines.first { it.label() == answer }
        }
        val newType = library2.machineTypes[replacementMachine]?.replace('$', '.') // TODO: Without replace?
        return ClassOrInterfaceType(newType)
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
                (stmt.parentNode.unpack() as? BlockStmt)?.statements?.remove(stmt)
        }
    }

    fun applyReplacement(replacement: Replacement) {
        val oldExpr = replacement.oldNode
        val (statement, blockStmt) = getBlockStmt(oldExpr)
        val statements = blockStmt.statements

        if (replacement.pendingExpressions.isNotEmpty()) {
            val pos = statements.indexOf(statement)
            val pushedPendingExpressions = replacement.pendingExpressions.filter { it.makeVariable }
            val pendingStatements = pushedPendingExpressions.map { pending -> makeNewStatement(pending) }
            statements.addAll(pos, pendingStatements)
//            for (stmt in pendingStatements) {
//                stmt.parentNode = blockStmt
//            }

            val lastExpr = replacement.pendingExpressions.last()
            if (lastExpr.hasReturnValue && oldExpr.parentNode.get() !is VariableDeclarator) {
                val expr = lastExpr.provides!!
                replaceNode(expr, oldExpr)
            } else {
                removedStmts += statement
            }
        } else if (oldExpr.parentNode.get() is ExpressionStmt || oldExpr.parentNode.get() is VariableDeclarator || oldExpr.parentNode.get() is AssignExpr) {
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
        val parent = oldExpr.parentNode.get()
        when (parent) {
            is AssignExpr -> parent.value = newExpr
            is BinaryExpr -> parent.left = newExpr
            is ObjectCreationExpr -> {
                val argsPos = parent.arguments.indexOf(oldExpr)
                parent.arguments.set(argsPos, newExpr)
//                newExpr.parentNode = parent
            }
            is MethodCallExpr -> {
                val argsPos = parent.arguments.indexOf(oldExpr)
                if (argsPos >= 0) {
                    parent.arguments.set(argsPos, newExpr)
                } else {
                    parent.setScope(newExpr)
                }
//                newExpr.parentNode = parent
            }
            is ReturnStmt -> parent.setExpression(newExpr)
            is CastExpr -> parent.expression = newExpr
            is ExpressionStmt -> parent.expression = newExpr
            is ConditionalExpr -> when {
                parent.condition == oldExpr -> parent.condition = newExpr
                parent.thenExpr == oldExpr -> parent.thenExpr = newExpr
                parent.elseExpr == oldExpr -> parent.elseExpr = newExpr
                else -> TODO()
            }
            else -> error("Don't know how to insert into " + parent.toString())
        }
    }

    private fun getBlockStmt(initialNode: Node): Pair<Statement, BlockStmt> {
        var node: Node
        var parent: Node = initialNode
        do {
            node = parent
            parent = node.parentNode.get()
        } while (parent is BlockStmt == false)
        return Pair(node as Statement, parent as BlockStmt)
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
        val newVariable = VariableDeclarationExpr(ClassOrInterfaceType(type), name)
        if (initExpr != null) {
            newVariable.variables.first().setInitializer(initExpr)
        }
        return ExpressionStmt(newVariable)
    }
}

class RouteExtractor(val library1: Library,
                     val codeElements: CodeElements,
                     val functionName: String,
                     val sourceFile: File,
                     val project: GradleProject) {
    private val logger = KotlinLogging.logger {}

    fun extractFromJSON(invocations: GroupedInvocation): List<LocatedEdge> {
        val localInvocations = invocations[sourceFile.name]!![functionName] ?: return emptyList() // invocations.filter { inv -> inv.callerName == functionName && inv.filename == this.sourceFile.name }
//        val events = codeElements.codeEvents.sortedBy { event -> event.end.get() }
//        for (event in events) {
//            try {
//                val type = project.javaParserFacade.getType(event)
//                println(type)
//            } catch (ex: RuntimeException) {
//                System.err.println(ex)
//            }
//        }

        val usedEdges: MutableList<LocatedEdge> = mutableListOf()
        val edges = library1.stateMachines.flatMap { machine -> machine.edges }
        for (invocation in localInvocations) {
            if (invocation.kind == "method-call") {
                val callEdge = edges.filterIsInstance<CallEdge>().firstOrNull { edge ->
                    edge.methodName == invocation.name &&
                            edge.machine.describesType(invocation.simpleType()) &&
                            if (edge.param.isNotEmpty() && edge.param.first() is ConstParam) (edge.param.first() as ConstParam).value == invocation.args.first() else true
                }
                if (callEdge == null) {
                    println("Cannot find edge for $invocation")
                    continue
                }
                val methodCall = codeElements.methodCalls.firstOrNull { call -> call.name.identifier == invocation.name && call.end.unpack()?.line == invocation.line }
                if (methodCall == null) {
                    logger.error("Cannot find node for $invocation")
                    continue
                }
                usedEdges += LocatedEdge(callEdge, methodCall)
                if (methodCall.parentNode.get() is ExpressionStmt == false) {
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
                val constructorCall = codeElements.objectCreation.firstOrNull { objectCreation -> (objectCreation.type.toString() == invocation.simpleType()) && (objectCreation.end.get().line == invocation.line) }
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
            val nodeLine: Int = node.end.get().line,
            val nodeColumn: Int = node.end.get().column
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
                 val invocations: GroupedInvocation,
                 val library1: Library,
                 val library2: Library,
                 val dependencies: MutableMap<StateMachine, Expression>,
                 val ui: UserInteraction) {
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
            if (route == null) {
                continue
            }
            actionsQueue.clear()
            props += route.stateProps
            for (step in route.path) {
                addToContext(step.dst)
            }
            globalRoute += Route(oldNode = usage.node, route = route.path, edge = edge)
        }
        // check(actionsQueue.isEmpty())
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
            val route = findRoute(context, machine.getFinalState(), listOf())!!.path
            if (route.isNotEmpty()) {
                val firstOccurence = globalRoute.first { route -> route.route.any { edge -> edge.machine == machine } }
                firstOccurence.finalizerRoute = route
            }
        }
    }

    private fun findRoute(src: Set<State>, dst: State?, actions: List<Action>): PathFinder.Model? {
        var states = src.filter { state -> library2.states().contains(state) }.toSet()
        logger.info("  Searching route from ${states.joinToString(transform = State::stateAndMachineName)} to ${dst?.stateAndMachineName()} with ${actions.joinToString(transform = Action::name)}")
        val edges = library2.stateMachines.flatMap(StateMachine::edges).toSet()
        var iteration = 0
        while (true) {
            try {
                val pathFinder = PathFinder(edges, states, props, actions.sorted())
                pathFinder.findPath(dst)
                return pathFinder.resultModel
            } catch (ex: Exception) {
                val statesPool = library2.states()
                val response = ui.makeDecision("Add object to context, iteration $iteration", statesPool.map { it.toString() } + "Skip")
                if (response == "Skip") {
                    return null
                }
                val newState = statesPool.single { it.toString() == response }
                states -= states.filter { it.machine == newState.machine }
                states += newState
                val dependency = ui.makeDecision("Object name, iteration $iteration", null)
                dependencies += Pair(newState.machine, IntegerLiteralExpr(dependency))
                iteration++
            }
        }
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
        val args = edge.param.filterIsInstance<EntityParam>().mapIndexed { i, param -> param.state to methodCall.arguments[i] }.toMap()
        val scope = (edge.src to methodCall.scope.get())
        val deps = args + scope
        addDependenciesToContext(deps)
    }

    private fun extractDependenciesFromConstructor(edge: ConstructorEdge, constructorCall: ObjectCreationExpr) {
        val args = edge.param.filterIsInstance<EntityParam>().mapIndexed { i, param -> param.state to constructorCall.arguments[i] }.toMap()
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

fun <T> Optional<T>.unpack(): T? = orElse(null)

fun <NodeT: Node> List<NodeT>.toNodeList() = NodeList.nodeList(this)

