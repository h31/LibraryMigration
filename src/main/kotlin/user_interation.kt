/**
 * Created by artyom on 09.03.17.
 */

class UserInteraction {
    fun askUser(question: String, cases: List<String>): String {
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

fun main(args: Array<String>) {
    println(UserInteraction().askUser("Hi", listOf("1", "2")))
}