package ru.spbstu.kspt.librarymigration.parser

import ru.spbstu.kspt.librarymigration.*
import ru.spbstu.kspt.librarymigration.State

class Postprocessor {
    val machines = mutableMapOf<String, StateMachine>()
    val functions = mutableMapOf<Pair<String, String>, FunctionDecl>()
//    val entities get() = machines + types

    fun process(library: Library): ru.spbstu.kspt.librarymigration.Library {
        library.types.map { StateMachine(name = it.semanticType) }.associateByTo(machines, StateMachine::name)
        library.functions.associateByTo(functions, { Pair(it.entity, it.name) })
        for (fsm in library.automata) {
            val machine = machines[fsm.name]!!
            for (state in fsm.states) {
                State(name = state.name, machine = machine)
            }
            for (shift in fsm.shifts) {
                for (func in shift.functions) {
                    encodeFunction(machine = machine,
                            functionDecl = functions[fsm.name to func]!!,
                            shift = shift)
                }
            }
        }
        for (conv in library.converters) {
            val machine = machines[conv.entity]!!
            val args = Regex("<(w+)>").findAll(conv.expression)
                    .map { it.groupValues[1] }.map { it to machines[it]!!.getConstructedState() }.toMap()
            TemplateEdge(machine = machine, src = machine.getInitState(),
                    dst = machine.getConstructedState(), template = conv.expression, templateParams = args)
        }
        val types = library.types.map { machines[it.semanticType]!! to it.codeType }.toMap()

        return ru.spbstu.kspt.librarymigration.Library(name = library.name,
                stateMachines = machines.values.toList(), machineTypes = types)
    }

    fun encodeFunction(machine: StateMachine, functionDecl: FunctionDecl, shift: Shift): Edge {
        val dst = if (shift.to == "self") shift.from else shift.to
        return CallEdge(machine = machine, src = machine.stateByName(shift.from),
                dst = machine.stateByName(dst), methodName = functionDecl.name,
                param = functionDecl.args.map { EntityParam(machine = checkNotNull(machines[it.type])) })
    }

    fun StateMachine.stateByName(name: String): State {
        return states.first { it.name == name }
    }
}