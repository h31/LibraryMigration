/**
 * Created by artyom on 16.06.16.
 */

object GraphEntities {
    val node: Entity = Entity(name = "Node")
    val nodeList: Entity = Entity(name = "NodeList")
    val num: Entity = Entity(name = "Number")
}

fun makeGraph1(): Library {
    val node = StateMachine(entity = GraphEntities.node)
    val num = StateMachine(entity = GraphEntities.num)
    val parent = node.inherit(name = "parent")
    val child = node.inherit(name = "child")
    val getNode = CallEdge(
            machine = node,
                    methodName = "getNode",
                    param = listOf(Param(
                            machine = num
                    )
                    )
    )

    val getNum = CallEdge(
            machine = node,
                    methodName = "getNodeNum"
    )

    LinkedEdge(
            machine = node,
            dst = child.getInitState(),
                    edge = getNode
    )

    val getParent = CallEdge(
            machine = node,
                    methodName = "getParent"
    )

    LinkedEdge(
            machine = node,
            dst = parent.getInitState(),
                    edge = getParent
    )

    val nodeList = StateMachine(entity = GraphEntities.nodeList)

    MakeArrayEdge(
            machine = node,
            dst = nodeList.getInitState(),
                    getSize = getNum,
                    getItem = getNode
    )

//    Edge(
//            machine = nodeList,
//            dst = child.getInitState(),
//            action = CallAction(
//                    methodName = "get",
//                    param = Param(
//                            entity = GraphEntities.num,
//                            pos = 0
//                    )
//            )
//    )

    return Library(
            stateMachines = listOf(node, nodeList, parent, child),
            machineTypes = mapOf(
                    node to "Node1",
                    nodeList to "List<Node1>",
                    num to "int"
            )
    )
}

fun makeGraph2(): Library {
    val nodeList = StateMachine(entity = GraphEntities.nodeList)
    val num = StateMachine(entity = GraphEntities.num)

    val listGet = CallEdge(
            machine = nodeList,
                    methodName = "get",
                    param = listOf(Param(
                            machine = num
                    )
                    )
    )

    val node = StateMachine(entity = GraphEntities.node)
    val parent = node.inherit(name = "parent")
    val child = node.inherit(name = "child")

    val getNode = CallEdge(
            machine = node,
                    methodName = "getNodeList"
    )

    val getParent = CallEdge(
            machine = node,
                    methodName = "getParentNode"
    )

    LinkedEdge(
            machine = node,
            dst = parent.getInitState(),
                    edge = getParent
    )

    val listNodeCreate = LinkedEdge(
            machine = node,
            dst = nodeList.getInitState(),
                    edge = getNode
    )

    val nodeCreate = LinkedEdge(
            machine = nodeList,
            dst = child.getInitState(),
                    edge = listGet
    )

    return Library(
            stateMachines = listOf(node, nodeList, parent, child),
            machineTypes = mapOf(
                    node to "Node2",
                    nodeList to "List<Node2>",
                    num to "int"
            )
    )
}