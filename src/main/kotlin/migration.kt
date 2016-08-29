import com.github.javaparser.ASTHelper
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.DirectedPseudograph

/**
 * Created by artyom on 22.08.16.
 */

data class PendingStatement(val statement: Statement,
                            val provides: String? = null,
                            val depends: List<String> = listOf())

class Migration(val library1: Library,
                val library2: Library,
                val codeElements: CodeElements,
                val graph1: DirectedPseudograph<State, Edge> = toJGrapht(library1),
                val graph2: DirectedPseudograph<State, Edge> = toJGrapht(library2)) {
    val dependencies: MutableMap<StateMachine, Expression> = mutableMapOf()
    // val pendingStmts = mutableListOf<Statement>()
    var nameGeneratorCounter = 0

    fun makeRoute(src: State, dst: State) {
        val route = DijkstraShortestPath.findPathBetween(graph2, src, dst)
        println("Route from %s to %s: ".format(src.stateAndMachineName(),
                dst.stateAndMachineName()))
        for (state in route.withIndex()) {
            println("%d: %s".format(state.index, state.value.label(library2)))
        }
    }

    fun doMigration() {
        val edges = library1.stateMachines.flatMap { it -> it.edges }
        for (edge in edges) {
            println("Processing " + edge.label(library1) + "... ")
            if (edge.src == edge.dst) {
                println("  Makes a loop, skipping")
                continue
            }
            when (edge) {
                is AutoEdge -> println("  Has an auto action, skipping")
                is CallEdge -> {
                    println("  Has a call action")
                    val route = findRoute(graph2, edge.src, edge.dst)
                    migrateMethodCall(edge, route)
                }
                is LinkedEdge -> {
                    println("  Has a linked action")
                    val dependency = edge.edge
                    val route = findRoute(graph2, edge.src, edge.dst)
                    migrateMethodCall(dependency, route)
                }
            }
        }
    }

    private fun migrateMethodCall(edge: CallEdge, route: List<Edge>) {
        val usages = getUsages(edge.methodName)
        if (usages.isNotEmpty()) {
            println("  Has %d usage(s)!".format(usages.size))
        }
        for (usage in usages) {
            val oldVarName = getVariableNameFromExpression(usage)
            dependencies.putAll(extractDependenciesFromMethod(edge, usage))
            // makeDependenciesFromEdge(edge)
            val pendingStmts = applySteps(route, oldVarName)
            replaceMethodCall(usage, pendingStmts, oldVarName)
        }
    }

    private fun applySteps(steps: List<Edge>, oldVarName: String?): List<PendingStatement> {
        val pendingStmts = mutableListOf<PendingStatement>()
        for (step in steps) {
            println("    Step: " + step.label(library2))

            val newStatements: List<PendingStatement> = when (step) {
                is CallEdge -> makeCallStatement(step)
                is ConstructorEdge -> TODO()
                is AutoEdge -> listOf()
                is LinkedEdge -> makeLinkedEdge(step, oldVarName)
                is MakeArrayEdge -> TODO() // makeArray(step.action)
                is TemplateEdge -> TODO()
                is UsageEdge -> makeUsageEdge(step)
                else -> error("Unknown action!")
            }
            println("Received statement: " + newStatements.toString())
            pendingStmts.addAll(newStatements)
        }
        return pendingStmts
    }

    private fun makeLinkedEdge(step: LinkedEdge, oldVarName: String?): List<PendingStatement> {
        val type = checkNotNull(library2.machineTypes[step.dst.machine])
        val name = oldVarName ?: "linkedEdge_%s_%d".format(step.machine.name, nameGeneratorCounter++)
        val expr = makeCallExpression(step.edge)
        val newVariable = makeNewVariable(type, name, expr)
        dependencies[step.dst.machine] = NameExpr(name)
        return listOf(PendingStatement(statement = newVariable, provides = name))
    }

    private fun makeUsageEdge(step: UsageEdge): List<PendingStatement> {
        if (step.edge is CallEdge && step.edge.isStatic) {
            return emptyList()
        }
        val (usedVariableName, newVariableStatement) = makeMissingDependency(step.edge.machine, step.dst)
        dependencies[step.edge.machine] = NameExpr(usedVariableName)
//        val callStatement = makeCallStatement(step.edge as CallEdge)
        return listOf(PendingStatement(statement = newVariableStatement, provides = usedVariableName))
    }

    private fun replaceMethodCall(methodCall: MethodCallExpr, pendingStmts: List<PendingStatement>, oldVarName: String?) {
        val (node, parent) = getBlockStmt(methodCall)
        if (oldVarName != null) {
            replaceCodeUsage(parent, oldVarName, pendingStmts)
        }
        val statements = parent.stmts
        val pos = statements.indexOf(node)
        statements.addAll(pos, pendingStmts.map { pending -> pending.statement })
        statements.remove(node)
        node.parentNode = null
        for (stmt in pendingStmts) {
            stmt.statement.parentNode = parent
        }
    }

    private fun getVariableNameFromExpression(methodCall: MethodCallExpr): String? {
        val parent = methodCall.parentNode
        return if (parent is VariableDeclarator) parent.id.name else null
    }

    private fun replaceCodeUsage(blockStmt: BlockStmt, oldVarName: String, pendingStmts: List<PendingStatement>) {
        val newVarName = pendingStmts.map { stmt -> stmt.provides }.lastOrNull() ?: error("New statement should provide a variable")
        if (oldVarName != newVarName) {
            replaceName(blockStmt, oldVarName, newVarName)
        }
    }

    private fun replaceName(node: Node, src: String, dst: String) {
        if (node is NameExpr && node.name == src) {
            node.name = dst
        }
        for (child in node.childrenNodes) {
            replaceName(child, src, dst)
        }
    }

    private fun unpackCallEdge(edge: Edge): CallEdge? {
        if (edge is LinkedEdge) {
            return edge.edge
        }
        else if (edge is UsageEdge) {
            return edge.edge as CallEdge
        }
        else if (edge is CallEdge) {
            return edge
        }
        else {
            return null
        }
    }

    private fun makeCallExpressionParams(edge: CallEdge): CallExpressionParams {
        val scope = if (edge.isStatic) {
            NameExpr(edge.machine.type(library2))
        } else {
            checkNotNull(dependencies[edge.machine])
        }

        val args = edge.param.map { param -> checkNotNull(dependencies[param.machine]) }

        return CallExpressionParams(scope, args)
    }

    private fun makeNewVariable(type: String, name: String, initExpr: Expression?): Statement {
        val newVariable = ASTHelper.createVariableDeclarationExpr(ClassOrInterfaceType(type), name)
        if (initExpr != null) {
            newVariable.vars.first().init = initExpr
        }
        return ExpressionStmt(newVariable)
    }

    private fun makeMissingDependency(machine: StateMachine, requiredState: State): Pair<String, Statement> {
        val type = checkNotNull(library2.machineTypes[machine])
        val name = "newMachine_%s_%d".format(machine.name, nameGeneratorCounter++)

        val step = library2.stateMachines.flatMap { machine -> machine.edges }
                .first { edge: Edge -> (edge is CallEdge == false) && (edge is UsageEdge == false) && (edge.dst == requiredState) }
        val initExpr = if (step is LinkedEdge) {
            makeCallExpression(step.edge)
        } else {
            null
        }

        val newVariableStatement = makeNewVariable(type, name, initExpr)
        return Pair(name, newVariableStatement)
    }

    private fun makeCallStatement(step: CallEdge) = listOf(PendingStatement(ExpressionStmt(makeCallExpression(step))))

    private fun makeCallExpression(step: CallEdge): Expression {
        val callParams = makeCallExpressionParams(step)
        val expr = MethodCallExpr(callParams.scope, step.methodName, callParams.args)
        return expr
    }

    private fun getBlockStmt(initialNode: Node): Pair<Statement, BlockStmt> {
        var node: Node = initialNode
        while (node.parentNode is BlockStmt == false) {
            node = node.parentNode
        }
        return Pair(node as Statement, node.parentNode as BlockStmt)
    }

    private fun findRoute(graph: DirectedPseudograph<State, Edge>, src: State, dst: State): List<Edge> {
        println("  Searching route from %s to %s".format(src.stateAndMachineName(), dst.stateAndMachineName()))
        return DijkstraShortestPath.findPathBetween(graph, src, dst)
    }

    private fun getUsages(methodName: String) =
            codeElements.methodCalls.filter { methodCall -> methodCall.name == methodName }

    private fun extractDependenciesFromMethod(edge: CallEdge, methodCall: MethodCallExpr): Map<StateMachine, Expression> {
        val args = edge.param.mapIndexed { i, param -> param.machine to methodCall.args[i] }.toMap()
        val scope = (edge.machine to methodCall.scope)
        val dependencies = args + scope
        return dependencies.filterValues { expr -> expr != null }
    }

    private fun makeDependenciesFromEdge(edge: Edge) {
        if (dependencies.containsKey(edge.machine) == false) {
            makeMissingDependency(edge.machine, edge.src)
        }
    }
}