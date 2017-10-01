package ru.spbstu.kspt.librarymigration.parser

interface Node

data class Library(val name: String,
                   val automata: List<Automaton>,
                   val types: List<Type>,
                   val converters: List<Converter>,
                   val functions: List<FunctionDecl>): Node

open class NodeList(val list: List<Node>) : Node, List<Node> by list

//class TypeList(list: List<Node>) : NodeList(list)

data class Automaton(val name: String,
                     val states: List<State>,
                     val shifts: List<Shift>) : Node

data class Type(val semanticType: String, val codeType: String) : Node

data class Converter(val entity: String, val expression: String) : Node

data class FunctionDecl(val entity: String, val name: String,
                        val args: List<FunctionArgument>,
                        val actions: List<Action>) : Node

data class Action(val name: String, val args: List<String>) : Node

data class FunctionArgument(val name: String, val type: String) : Node

data class State(val name: String) : Node

data class Shift(val from: String, val to: String, val functions: List<String>) : Node
