package ru.spbstu.kspt.librarymigration.parser

interface Node

data class LibraryDecl(val name: String,
                   val automata: List<Automaton>,
                   val types: List<Type>,
                   val converters: List<Converter>,
                   val functions: List<FunctionDecl>): Node

open class NodeList<T>(val list: List<T>) : Node, List<T> by list

//class TypeList(list: List<Node>) : NodeList(list)

data class Automaton(val name: String,
                     val states: List<StateDecl>,
                     val shifts: List<ShiftDecl>) : Node

data class Type(val semanticType: String, val codeType: String) : Node

data class Converter(val entity: String, val expression: String) : Node

data class FunctionDecl(val entity: String, val name: String,
                        val args: List<FunctionArgument>,
                        val actions: List<ActionDecl>) : Node

data class ActionDecl(val name: String, val args: List<String>) : Node

data class FunctionArgument(val name: String, val type: String) : Node

data class StateDecl(val name: String) : Node

data class ShiftDecl(val from: String, val to: String, val functions: List<String>) : Node
