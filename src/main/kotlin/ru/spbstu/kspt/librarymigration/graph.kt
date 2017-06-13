package ru.spbstu.kspt.librarymigration

/**
 * Created by artyom on 16.06.16.
 */

fun makeGraph1(): Library {
    val node = StateMachine(name = "Node")
    val num = StateMachine(name = "Number")
    val parent = node.inherit(name = "parent")
    val child = node.inherit(name = "child")
    val getNode = CallEdge(
            machine = node,
            methodName = "getNode",
            param = listOf(EntityParam(
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
            dst = child.getDefaultState(),
            edge = getNode
    )

    val getParent = CallEdge(
            machine = node,
            methodName = "getParent"
    )

    LinkedEdge(
            machine = node,
            dst = parent.getDefaultState(),
            edge = getParent
    )

    val nodeList = StateMachine(name = "NodeList")

    MakeArrayEdge(
            machine = node,
            dst = nodeList.getDefaultState(),
            getSize = getNum,
            getItem = getNode
    )

//    Edge(
//            machine = nodeList,
//            dst = child.getDefaultState(),
//            action = CallAction(
//                    methodName = "get",
//                    param = EntityParam(
//                            entity = GraphEntities.num,
//                            pos = 0
//                    )
//            )
//    )

    return Library(
            name = "graph1",
            stateMachines = listOf(node, nodeList, parent, child),
            machineTypes = mapOf(
                    node to "Node1",
                    nodeList to "List<Node1>",
                    num to "int"
            )
    )
}

fun makeGraph2(): Library {
    val nodeList = StateMachine(name = "NodeList")
    val num = StateMachine(name = "Number")

    val listGet = CallEdge(
            machine = nodeList,
            methodName = "get",
            param = listOf(EntityParam(
                    machine = num
            )
            )
    )

    val node = StateMachine(name = "Node")
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
            dst = parent.getDefaultState(),
            edge = getParent
    )

    val listNodeCreate = LinkedEdge(
            machine = node,
            dst = nodeList.getDefaultState(),
            edge = getNode
    )

    val nodeCreate = LinkedEdge(
            machine = nodeList,
            dst = child.getDefaultState(),
            edge = listGet
    )

    return Library(
            name = "graph2",
            stateMachines = listOf(node, nodeList, parent, child),
            machineTypes = mapOf(
                    node to "Node2",
                    nodeList to "List<Node2>",
                    num to "int"
            )
    )
}