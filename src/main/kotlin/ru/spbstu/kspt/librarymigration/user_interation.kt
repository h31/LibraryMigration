package ru.spbstu.kspt.librarymigration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems

/**
 * Created by artyom on 09.03.17.
 */

object AnswerCache {
    val cacheFile = File("answers.json")
    private var answerCache: List<Answer>? = null
    val answers: List<Answer> get() {
        if (answerCache == null) {
            if (cacheFile.exists()) {
                answerCache = ObjectMapper().registerKotlinModule().readValue<List<Answer>>(cacheFile)
            } else {
                answerCache = emptyList()
            }
        }
        return answerCache!!
    }

    fun addAnswer(answer: Answer) {
        answerCache = answers + answer
        ObjectMapper().registerKotlinModule().writerWithDefaultPrettyPrinter().writeValue(cacheFile, answerCache)
    }
}

class UserInteraction(val library1: String, val library2: String, val fileObj: File) {
    val file = platformIndependentPath()

    fun makeDecision(question: String, cases: List<String>?): String {
        val answers = AnswerCache.answers
        val cachedAnswer = answers.firstOrNull { it.file == file && it.library1 == library1 && it.library2 == library2 && it.question == question }
        if (cachedAnswer != null) {
            return cachedAnswer.response
        } else {
            if (System.getenv().containsKey("CI")) {
                throw IOException()
            }
            val response = if (cases != null) {
                askUserCase(question, cases)
            } else {
                askUserFreeform(question)
            }
            AnswerCache.addAnswer(Answer(file = file, library1 = library1, library2 = library2, question = question, response = response))
            return response
        }
    }

    private fun askUserCase(question: String, cases: List<String>): String {
        println("Question: $question")
        for (case in cases.withIndex()) {
            println("${case.index + 1}. ${case.value}")
        }
        while (true) {
            val response = readLine()?.toIntOrNull()
            if (response != null) {
                return cases[response-1]
            } else {
                println("Incorrect input")
            }
        }
    }

    private fun askUserFreeform(question: String): String {
        println("Question: $question")
        while (true) {
            val response = readLine()
            if (response != null) {
                return response
            } else {
                println("Incorrect input")
            }
        }
    }

    fun platformIndependentPath(): String {
        val relative = fileObj.relativeToOrSelf(File(".").absoluteFile).toString() // TODO: Dirty hack
        return if (FileSystems.getDefault().separator == "\\") {
            relative.replace('/', '\\')
        } else {
            relative
        }
    }
}

data class Answer(val file: String,
                  val library1: String,
                  val library2: String,
                  val question: String,
                  val response: String)

fun main(args: Array<String>) {
    println(UserInteraction("src", "dst", File("path/to/file")).makeDecision("Hi", listOf("1", "2")))
}