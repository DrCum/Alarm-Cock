package com.taskalarm.engine

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

internal data class Brick(val x: Float, val y: Float, val width: Float = .145f, val height: Float = .045f)
internal data class BreakoutFrame(
  val paddle: Float, val paddleWidth: Float, val ballX: Float, val ballY: Float,
  val bricks: List<Brick>, val score: Int, val best: Int, val playedMs: Long,
  val targetMs: Long, val idleMs: Long, val idleLimitMs: Long, val active: Boolean,
  val resets: Int, val complete: Boolean
)

/** Native physics and monotonic active-time accounting; points never determine completion. */
internal class BreakoutGame(
  task: JSONObject,
  saved: JSONObject,
  private val elapsed: () -> Long,
  private val wall: () -> Long
) {
  private val target = task.optInt("gameSeconds", 60) * 1000L
  private val idleLimit = task.optInt("gameIdleSeconds", 20) * 1000L
  private val difficulty = task.optString("difficulty", "easy")
  private val width = when (difficulty) { "easy" -> .28f; "medium" -> .22f; else -> .17f }
  private val speed = when (difficulty) { "easy" -> .43f; "medium" -> .57f; else -> .72f }
  private var paddle = .5f
  private var ballX = .5f
  private var ballY = .7f
  private var vx = speed * .5f
  private var vy = -speed
  private var bricks = newBricks()
  private var score = saved.optInt("gameScore")
  private var played = saved.optLong("gamePlayedMs").coerceIn(0, target)
  private var resets = saved.optInt("gameResets")
  private var lastTick: Long? = null
  private var lastInput: Long? = saved.optLong("gameLastInputAt").takeIf { it > 0 }?.let {
    elapsed() - (wall() - it).coerceAtLeast(0)
  }
  private var lastInputWall = saved.optLong("gameLastInputAt")
  private var lastSave = elapsed()
  private var resetDuringFrame = false

  private fun newBricks() = (0 until 4).flatMap { row -> (0 until 6).map { col -> Brick(.018f + col / 6f, .08f + row * .065f) } }.toMutableList()

  fun pause() { lastTick = null }

  fun frame(input: Float? = null, best: Int = 0): BreakoutFrame {
    val time = elapsed()
    val idle = lastInput?.let { (time - it).coerceAtLeast(0) } ?: idleLimit
    resetDuringFrame = false
    if (idle >= idleLimit && played > 0) { played = 0; resets++; resetDuringFrame = true }
    val dt = lastTick?.let { (time - it).coerceAtLeast(0) } ?: 0
    val active = idle < 2500L && lastInput != null
    // A suspended/slow UI never earns time for the interval during which it wasn't running.
    if (active && dt in 1..250) played = (played + dt).coerceAtMost(target)
    lastTick = time
    if (input != null && input.isFinite()) {
      val position = input.coerceIn(width / 2, 1 - width / 2)
      if (abs(position - paddle) >= .005f) {
        paddle = position; lastInput = time; lastInputWall = wall()
      }
    }
    physics(dt.coerceAtMost(50) / 1000f)
    return BreakoutFrame(paddle, width, ballX, ballY, bricks.toList(), score, maxOf(best, score), played,
      target, lastInput?.let { (time - it).coerceAtLeast(0) } ?: idleLimit, idleLimit,
      lastInput?.let { time - it < 2500 } == true, resets, played >= target)
  }

  private fun physics(dt: Float) {
    ballX += vx * dt; ballY += vy * dt
    if (ballX < .015f) { ballX = .015f; vx = abs(vx) }
    if (ballX > .985f) { ballX = .985f; vx = -abs(vx) }
    if (ballY < .015f) { ballY = .015f; vy = abs(vy) }
    if (vy > 0 && ballY in .9f.. .94f && abs(ballX - paddle) <= width / 2 + .015f) {
      ballY = .9f
      vx = ((ballX - paddle) / (width / 2) * speed * .85f).coerceIn(-speed * .85f, speed * .85f)
      vy = -sqrt((speed * speed - vx * vx).coerceAtLeast(.02f))
    }
    val hit = bricks.indexOfFirst { ballX in (it.x - .012f)..(it.x + it.width + .012f) && ballY in (it.y - .012f)..(it.y + it.height + .012f) }
    if (hit >= 0) { bricks.removeAt(hit); vy = -vy; score += 10 }
    if (bricks.isEmpty()) bricks = newBricks()
    // Unlimited balls. Missing the paddle never adds a score or lives requirement.
    if (ballY > 1.02f) { ballX = paddle; ballY = .72f; vx = speed * .5f; vy = -speed }
  }

  fun needsSave() = resetDuringFrame || elapsed() - lastSave >= 1000 || played >= target
  fun checkpoint(record: JSONObject) {
    record.put("gamePlayedMs", played).put("gameScore", score).put("gameLastInputAt", lastInputWall).put("gameResets", resets)
    lastSave = elapsed()
  }
}
