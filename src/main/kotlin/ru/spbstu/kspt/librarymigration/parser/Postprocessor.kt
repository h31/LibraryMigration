package ru.spbstu.kspt.librarymigration.parser

import ru.spbstu.kspt.librarymigration.CallEdge
import ru.spbstu.kspt.librarymigration.Edge
import ru.spbstu.kspt.librarymigration.State
import ru.spbstu.kspt.librarymigration.StateMachine

class Postprocessor {
    fun process(library: Library): ru.spbstu.kspt.librarymigration.Library {
        val stateMachine = library.automata.map {
            val sm = StateMachine(name = it.name)
            it.states.forEach { State(name = it.name, machine = sm) }
            sm
        }
        return ru.spbstu.kspt.librarymigration.Library(name = library.name,
                stateMachines = stateMachine, machineTypes = mapOf())
    }
}

//fun ru.spbstu.kspt.librarymigration.parser.FunctionDecl.encode(machine: StateMachine, shift: Shift): Edge {
//    return CallEdge(machine = machine)
//}