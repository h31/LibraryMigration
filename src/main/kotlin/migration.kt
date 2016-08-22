import com.github.javaparser.ASTHelper
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.type.ClassOrInterfaceType
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.DirectedPseudograph

/**
 * Created by artyom on 22.08.16.
 */

class Migration(val library1: Library,
                val library2: Library,
                val codeElements: CodeElements,
                val graph1: DirectedPseudograph<State, Edge> = toJGrapht(library1),
                val graph2: DirectedPseudograph<State, Edge> = toJGrapht(library2)) {
    val dependencies: MutableMap<StateMachine, Expression> = mutableMapOf()
    val pendingStmts = mutableListOf<Statement>()
    var stmtCounter = 0

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
            if (edge is AutoEdge) {
                println("  Has an auto action, skipping")
                continue
            }
            if (edge is CallEdge) {
                println("  Has a call action")
                val route = findRoute(graph2, edge.src, edge.dst)
                migrateMethodCall(edge, route)
            }
            if (edge is LinkedEdge) {
                println("  Has a linked action")
                val dependency = edge.edge
                val route = findRoute(graph2, edge.src, edge.dst)
                migrateMethodCall(dependency, route)
            }
        }
    }

    private fun migrateMethodCall(edge: CallEdge, route: List<Edge>) {
            val usages = getUsages(edge.methodName)
            if (usages.isNotEmpty()) {
                println("  Has %d usage(s)!".format(usages.size))
            }
            for (usage in usages) {
                dependencies.putAll(extractDependenciesFromMethod(edge, usage))
                applySteps(route)
            }
    }

    private fun applySteps(steps: List<Edge>) {
        for (step in steps) {
            println("    Step: " + step.label(library2))

            val newStatements: List<Statement> = when (step) {
                is CallEdge -> makeCallStatement(step)
                is ConstructorEdge -> TODO()
                is AutoEdge -> listOf()
                is LinkedEdge -> makeLinkedEdge(step)
                is MakeArrayEdge -> TODO() // makeArray(step.action)
                is TemplateEdge -> TODO()
                is UsageEdge -> TODO() // makeCallStatement(step.edge)
                else -> error("Unknown action!")
            }
            println("Received statement: " + newStatements.toString())
            pendingStmts.addAll(newStatements)
        }
    }

    private fun makeLinkedEdge(step: LinkedEdge): List<Statement> {
        val type = checkNotNull(library2.entityTypes[step.machine.entity])
        val name = "newLinkedEdge" + (stmtCounter++)
        val expr = makeCallExpression(step.edge)
        val newVariable = makeNewVariable(type, name, expr)
        return listOf(newVariable)
    }

    private fun removeMethodCall(methodCall: MethodCallExpr) {
        val parent = methodCall.parentNode
        parent.childrenNodes.remove(methodCall)
        if (parent is MethodCallExpr) {
            parent.scope = methodCall.scope
        } else if (parent is ExpressionStmt) {
            parent.expression = methodCall.scope
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
        val scope = if (edge.className != null) {
            NameExpr(edge.className)
        } else {
            dependencies[edge.machine]
        }

        val args = edge.param.map { param -> checkNotNull(dependencies[param.machine]) }

        return CallExpressionParams(scope, args)
    }

    private fun makeNewVariable(type: String, name: String, initExpr: Expression): Statement {
        val newVariable = ASTHelper.createVariableDeclarationExpr(ClassOrInterfaceType(type), name)
        newVariable.vars.first().init = initExpr
        return ExpressionStmt(newVariable)
    }

    private fun makeMissingDependency(machine: StateMachine) {
        val type = checkNotNull(library2.entityTypes[machine.entity])
        val name = "newMachine" + (stmtCounter++)

        val step = machine.edges.first { edge: Edge -> edge.src == machine.getInitState() && edge.dst == machine.getConstructedState() }
        val initExpr = makeCallExpression(step as CallEdge)

        makeNewVariable(type, name, initExpr)
    }

    private fun makeCallStatement(step: CallEdge) = listOf(ExpressionStmt(makeCallExpression(step)))

    private fun makeCallExpression(step: CallEdge): Expression {
        while (true) {
            try {
                val callParams = makeCallExpressionParams(step)
                val expr = MethodCallExpr(callParams.scope, step.methodName, callParams.args)
                return expr
            } catch (ex: NeedDependencyException) {
                makeMissingDependency(ex.machine)
            }
        }
    }

    private fun getBlockStmt(initialNode: Node): BlockStmt {
        var node: Node = initialNode
        while (node.parentNode is BlockStmt == false) {
            node = node.parentNode
        }
        return node.parentNode as BlockStmt
    }

    private fun findRoute(graph: DirectedPseudograph<State, Edge>, src: State, dst: State): List<Edge> {
        println("  Searching route from %s to %s".format(src.stateAndMachineName(), dst.stateAndMachineName()))
        return DijkstraShortestPath.findPathBetween(graph, src, dst)
    }

    private fun getUsages(methodName: String) =
            codeElements.methodCalls.filter { methodCall -> methodCall.name == methodName }

    private fun extractDependenciesFromMethod(edge: CallEdge, methodCall: MethodCallExpr): Map<StateMachine, Expression> {
        return edge.param.mapIndexed { i, param -> param.machine to methodCall.args[i] }.toMap() + (edge.machine to methodCall.scope)
    }
}