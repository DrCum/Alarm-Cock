package com.taskalarm.engine

import java.security.SecureRandom

internal data class Challenge(val prompt: String, val answer: String)

internal object Tasks {
  private val random = SecureRandom()
  private fun number(min: Int, max: Int) = min + random.nextInt(max - min + 1)

  fun generate(kind: String, difficulty: String): Challenge {
    require(difficulty in listOf("easy", "medium", "hard"))
    if (kind == "text") {
      val alphabet = when (difficulty) {
        "easy" -> "abcdefghjkmnpqrstuvwxyz"
        "medium" -> "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        else -> "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#&"
      }
      val size = when (difficulty) { "easy" -> 6; "medium" -> 10; else -> 16 }
      val text = (1..size).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
      return Challenge(text, text)
    }
    require(kind == "math")
    return when (difficulty) {
      "easy" -> {
        val a = number(1, 20); val b = number(1, 20)
        Challenge("$a + $b = ?", (a + b).toString())
      }
      "medium" -> {
        val a = number(2, 12); val b = number(2, 12); val c = number(10, 50)
        Challenge("$a × $b + $c = ?", (a * b + c).toString())
      }
      else -> {
        val a = number(13, 29); val b = number(11, 19)
        val c = number(2, 12); val d = number(2, 12)
        Challenge("($a × $b) − ($c × $d) = ?", (a * b - c * d).toString())
      }
    }
  }

  fun matches(kind: String, expected: String, supplied: String): Boolean =
    if (kind == "math") supplied.trim().toIntOrNull() == expected.toIntOrNull()
    else supplied == expected
}
