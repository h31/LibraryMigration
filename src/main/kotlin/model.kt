/**
 * Created by artyom on 16.06.16.
 */

enum class Action {
    CONSTRUCTOR, METHOD_CALL, STATIC_CALL, AUTO
}

data class Entity(val name: String,
                  val srcType: String,
                  val dstType: String = srcType)

data class Library(val stateMachines: List<StateMachine>)

data class StateMachine(val entity: Entity) {
    val states: MutableSet<State> = mutableSetOf()
    val edges: MutableSet<Edge> = mutableSetOf()

    init {
        states += makeInitState(this)
        states += makeConstructedState(this)
        edges += Edge(
                machine = this,
                src = getInitState(),
                dst = getConstructedState(),
                action = Action.AUTO,
                methodName = "auto",
                params = listOf())
    }

    fun getInitState() = states.first { state -> state.name == "Init" }
    fun getConstructedState() = states.first { state -> state.name == "Constructed" }
}

data class State(val name: String,
                 val machine: StateMachine) {
    init {
        machine.states.add(this)
    }

    fun findInLibrary(library: Library): StateMachine {
        for (machine in library.stateMachines) {
            for (state in machine.states) {
                if (state == this) {
                    return machine;
                }
            }
        }
        throw Exception()
    }
}

fun makeInitState(machine: StateMachine) = State("Init", machine)
fun makeConstructedState(machine: StateMachine) = State("Constructed", machine)

data class Edge(val machine: StateMachine,
                val src: State = makeConstructedState(machine),
                val dst: State = makeConstructedState(machine),
                val action: Action,
                val methodName: String,
                val params: List<Param>) {
    var createdMachine: StateMachine? = null
}

data class Param(val entity: Entity,
                 val pos: Int)