/**
 * Created by artyom on 16.06.16.
 */

object Entities {
    val node: Entity = Entity(name = "Node", srcType = "Node1", dstType = "Node2")
    val nodeList: Entity = Entity(name = "NodeList", srcType = "List")
    val num: Entity = Entity(name = "Number", srcType = "int")
}

fun makeGraph1(): Library {
    val node = StateMachine(entity = Entities.node)
    val getNode = Edge(machine = node,
            action = Action.METHOD_CALL,
            methodName = "getNode",
            params = listOf(Param(entity = Entities.num, pos = 0)))
    node.edges += getNode

    getNode.createdMachine = node

    return Library(listOf(node))
}

fun makeGraph2(): Library {
    val list = StateMachine(entity = Entities.nodeList)

    val listGet = Edge(machine = list,
            action = Action.METHOD_CALL,
            methodName = "get",
            params = listOf(Param(entity = Entities.num, pos = 0)))

    list.edges += listGet


    val node = StateMachine(entity = Entities.node)

    val getNode = Edge(machine = node,
            action = Action.METHOD_CALL,
            methodName = "getNodeList",
            params = listOf(Param(entity = Entities.num, pos = 0)))

    node.edges += getNode

    getNode.createdMachine = list

    listGet.createdMachine = node

    return Library(listOf(node, list))
}