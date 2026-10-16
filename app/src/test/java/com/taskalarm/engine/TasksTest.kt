package com.taskalarm.engine

import org.junit.Assert.*
import org.junit.Test

class TasksTest {
  @Test fun mathAnswersMatchRenderedExpressions() {
    for (level in listOf("easy", "medium", "hard")) repeat(500) {
      val challenge = Tasks.generate("math", level)
      val numbers = Regex("\\d+").findAll(challenge.prompt).map { it.value.toInt() }.toList()
      val answer = when (numbers.size) {
        2 -> numbers[0] + numbers[1]
        3 -> numbers[0] * numbers[1] + numbers[2]
        4 -> numbers[0] * numbers[1] - numbers[2] * numbers[3]
        else -> error("Unrecognized problem")
      }
      assertTrue(Tasks.matches("math", challenge.answer, answer.toString()))
      assertFalse(Tasks.matches("math", challenge.answer, (answer + 1).toString()))
    }
  }
  @Test fun textIsRandomAndCaseSensitive() {
    val lengths = mapOf("easy" to 6, "medium" to 10, "hard" to 16)
    for ((level, size) in lengths) {
      val generated = (1..100).map { Tasks.generate("text", level) }
      assertTrue(generated.map { it.prompt }.toSet().size > 1)
      for (challenge in generated) {
        assertEquals(size, challenge.prompt.length)
        assertTrue(Tasks.matches("text", challenge.answer, challenge.prompt))
        assertFalse(Tasks.matches("text", challenge.answer, challenge.prompt + " "))
      }
    }
    assertFalse(Tasks.matches("text", "A7m", "a7m"))
  }
  @Test fun barcodeMatchingKeepsLeadingZerosAndWhitespace() {
    assertTrue(Tasks.matches("barcode", "0012345", "0012345"))
    assertFalse(Tasks.matches("barcode", "0012345", "12345"))
    assertFalse(Tasks.matches("barcode", "item ", "item"))
  }
  @Test fun mathDoesNotAcceptPartialNumbersOrExpressions() {
    assertFalse(Tasks.matches("math", "12", "12abc"))
    assertFalse(Tasks.matches("math", "12", "6+6"))
    assertTrue(Tasks.matches("math", "12", " 12 "))
  }
}
