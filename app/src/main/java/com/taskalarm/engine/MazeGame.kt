package com.taskalarm.engine

import org.json.JSONObject
import kotlin.math.*

internal data class MazeWall(val x: Float, val y: Float, val width: Float, val height: Float)
internal data class MazePoint(val x: Float, val y: Float)
internal data class MazeFrame(val x: Float, val y: Float, val walls: List<MazeWall>, val checkpoints: List<MazePoint>, val reached: Int, val complete: Boolean)

/** Coordinates and collision checks belong to the engine, never to the drawn board. */
internal class MazeGame(difficulty: String, record: JSONObject) {
  private val count = when (difficulty) { "hard" -> 3; "medium" -> 2; else -> 1 }
  private val wallWidth = when(count) { 1 -> .65f; 2 -> .70f; else -> .73f }
  val walls = (0 until count).map { i -> MazeWall(if (i % 2 == 0) 0f else 1-wallWidth, (i + 1f) / (count + 2), wallWidth, .035f) }
  val checkpoints = walls.mapIndexed { i, wall -> MazePoint(if (i % 2 == 0) .86f else .14f, wall.y + .105f) } + MazePoint(.5f, .93f)
  private var x = record.optDouble("mazeX", .14).toFloat().coerceIn(RADIUS, 1-RADIUS)
  private var y = record.optDouble("mazeY", .10).toFloat().coerceIn(RADIUS, 1-RADIUS)
  private var reached = record.optInt("mazeReached").coerceIn(0, checkpoints.size)
  private var last = 0L
  fun pause() { last = 0 }
  fun frame(now: Long, inputX: Float, inputY: Float): MazeFrame {
    val dt = if (last == 0L) 0L else (now - last).takeIf { it in 1..100 } ?: 0L
    last = now
    val speed = when (count) { 3 -> .34f; 2 -> .29f; else -> .24f }
    val dx = inputX.takeIf { it.isFinite() }?.coerceIn(-1f,1f) ?: 0f
    val dy = inputY.takeIf { it.isFinite() }?.coerceIn(-1f,1f) ?: 0f
    repeat(10) {
      val nx = (x + dx * speed * dt / 10000f).coerceIn(RADIUS, 1-RADIUS)
      if (!collides(nx,y)) x = nx
      val ny = (y + dy * speed * dt / 10000f).coerceIn(RADIUS, 1-RADIUS)
      if (!collides(x,ny)) y = ny
      if (dt > 0 && reached < checkpoints.size) {
        val point = checkpoints[reached]
        if (hypot(x-point.x, y-point.y) < .065f) reached++
      }
    }
    return snapshot()
  }
  private fun collides(x: Float, y: Float) = walls.any { wall ->
    val cx = x.coerceIn(wall.x, wall.x+wall.width)
    val cy = y.coerceIn(wall.y, wall.y+wall.height)
    hypot(x-cx,y-cy) < RADIUS
  }
  fun snapshot() = MazeFrame(x,y,walls,checkpoints,reached,reached == checkpoints.size)
  fun checkpoint(record: JSONObject) { record.put("mazeX", x).put("mazeY", y).put("mazeReached", reached).put("nativeComplete", reached == checkpoints.size) }
  companion object { const val RADIUS = .025f }
}
