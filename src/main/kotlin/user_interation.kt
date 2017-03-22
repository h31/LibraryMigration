import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File

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

class UserInteraction(val library1: String, val library2: String, val file: String) {
    fun makeDecision(question: String, cases: List<String>): String {
        val answers = AnswerCache.answers
        val cachedAnswer = answers.firstOrNull { it.file == file && it.library1 == library1 && it.library2 == library2 && it.question == question }
        if (cachedAnswer != null) {
            return cachedAnswer.response
        } else {
            val response = askUser(question, cases)
            AnswerCache.addAnswer(Answer(file = file, library1 = library1, library2 = library2, question = question, response = response))
            return response
        }
    }

    private fun askUser(question: String, cases: List<String>): String {
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
}

data class Answer(val file: String,
                  val library1: String,
                  val library2: String,
                  val question: String,
                  val response: String)

fun main(args: Array<String>) {
    println(UserInteraction("src", "dst", "path/to/file").makeDecision("Hi", listOf("1", "2")))
}