package ru.spbstu.kspt.librarymigration

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.expr.Expression
import com.samskivert.mustache.Mustache

/**
 * Created by artyom on 05.07.16.
 */

fun fillPlaceholders(template: String, stringParams: Map<String, String>): String {
    val compiled = Mustache.compiler().escapeHTML(false).compile(template)
    return compiled.execute(stringParams)
}

fun makeStringParams(params: Map<String, Edge>, variables: Map<StateMachine, String>) = params.mapValues { it -> generateCode(it.value, variables[it.value.machine]) }

fun templateIntoAST(template: String) = JavaParser.parseExpression<Expression>(template)

fun generateCode(edge: Edge, obj: String?): String = when (edge) {
    is CallEdge -> obj + "." + edge.methodName
    is ConstructorEdge -> "new " + obj
    is AutoEdge -> obj ?: throw Exception()
    else -> throw Exception()
}

fun main(args: Array<String>) {
    val machine = StateMachine(name = "Test")
    val edge = CallEdge(machine = machine, methodName = "method")

//    println(fillPlaceholders("Hello {{ obj }}", templateParams = mapOf("obj" to edge), variables = mapOf(machine to "object")))
}