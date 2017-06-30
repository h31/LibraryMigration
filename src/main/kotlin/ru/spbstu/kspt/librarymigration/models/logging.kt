package ru.spbstu.kspt.librarymigration.models

import ru.spbstu.kspt.librarymigration.*

/**
 * Created by artyom on 22.06.17.
 */

object Logging {
    val all: List<Library> by lazy { listOf(makeLog4j(), makeSLF4J()) }

    object Actions {
        val info = Action("Info", withSideEffects = true)
        val debug = Action("Debug", withSideEffects = true)
        val warning = Action("Warning", withSideEffects = true)
        val error = Action("Error", withSideEffects = true)
    }

    fun makeSLF4J(): Library {
        val loggerFactory = StateMachine("LoggerFactory")
        val logger = StateMachine("Logger")
        val classReference = StateMachine("ClassReference")

        LinkedEdge(
                dst = logger.getConstructedState(),
                edge = CallEdge(
                        machine = loggerFactory,
                        src = loggerFactory.getInitState(),
                        methodName = "getLogger",
                        param = listOf(EntityParam(classReference)),
                        isStatic = true
                )
        )

        CallEdge(
                machine = logger,
                actions = listOf(Actions.info),
                param = listOf(ActionParam("Message")),
                methodName = "info"
        )

        return Library("SLF4J", listOf(logger, loggerFactory, classReference),
                mapOf(
                        logger to "org.slf4j.Logger",
                        loggerFactory to "org.slf4j.LoggerFactory",
                        classReference to "java.lang.Class"))
    }

    fun makeLog4j(): Library {
        val logger = StateMachine("Logger")
        val logManager = StateMachine("LogManager")
        val classReference = StateMachine("ClassReference")

        CallEdge(
                machine = logger,
                src = logger.getInitState(),
                dst = logger.getConstructedState(),
                methodName = "getLogger",
                param = listOf(EntityParam(classReference)),
                isStatic = true
        )

        LinkedEdge(
                dst = logger.getConstructedState(),
                edge = CallEdge(
                        machine = logManager,
                        src = logManager.getInitState(),
                        methodName = "getLogger",
                        param = listOf(EntityParam(classReference)),
                        isStatic = true
                )
        )

        CallEdge(
                machine = logger,
                actions = listOf(Actions.info),
                param = listOf(ActionParam("Message")),
                methodName = "info"
        )

        return Library("Log4j", listOf(logger, classReference, logManager),
                mapOf(
                        logger to "org.apache.log4j.Logger",
                        classReference to "java.lang.Class",
                        logManager to "org.apache.log4j.LogManager"))
    }
}