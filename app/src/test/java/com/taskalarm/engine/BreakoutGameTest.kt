package com.taskalarm.engine

import com.taskalarm.app.TaskDraft
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BreakoutGameTest {
  private var time = 100_000L
  private fun game(saved: JSONObject = JSONObject(), seconds: Int = 60) = BreakoutGame(
    TaskDraft(kind = "breakout", gameSeconds = seconds).json(), saved, { time }, { time })
  private fun play(game: BreakoutGame, count: Int): BreakoutFrame {
    var frame = game.frame(.2f)
    repeat(count) { time += 100; frame = game.frame(if (it % 2 == 0) .7f else .2f) }
    return frame
  }
  @Test fun activeTimeCompletesEvenWithNoScoreRequirement() {
    val game = game(seconds = 5)
    val frame = play(game, 50)
    assertTrue(frame.complete)
    assertEquals(5000L, frame.playedMs)
  }
  @Test fun aHighScoreNeverCompletesTheAlarmWithoutActiveTime() {
    val frame = game(JSONObject().put("gameScore", 1_000_000)).frame()
    assertEquals(1_000_000, frame.score)
    assertEquals(0L, frame.playedMs)
    assertFalse(frame.complete)
  }
  @Test fun holdingStillStopsEarningTimeAndTwentySecondsResetsIt() {
    val game = game()
    play(game, 30)
    time += 100
    val first = game.frame(.2f)
    repeat(40) { time += 100; game.frame(.2f) }
    val stopped = game.frame(.2f)
    assertTrue(stopped.playedMs >= first.playedMs)
    time += 1000
    assertEquals(stopped.playedMs, game.frame(.2f).playedMs)
    time += 20_000
    assertEquals(0L, game.frame(.2f).playedMs)
    assertEquals(1, game.frame(.2f).resets)
  }
  @Test fun aLateInputCannotRetroactivelyEarnAFKTime() {
    val game = game()
    play(game, 20)
    time += 30_000
    val frame = game.frame(.9f)
    assertEquals(0L, frame.playedMs)
    assertFalse(frame.complete)
  }
  @Test fun backgroundPauseNeverEarnsTimeAndRetainsShortBreakProgress() {
    val game = game()
    val progress = play(game, 20).playedMs
    game.pause()
    time += 1500
    assertEquals(progress, game.frame(.9f).playedMs)
    game.pause(); time += 20_000
    assertEquals(0L, game.frame(.1f).playedMs)
  }
  @Test fun checkpointsRecoverTimeButExpiredCheckpointsReset() {
    val original = game()
    val progress = play(original, 30).playedMs
    val record = JSONObject()
    original.checkpoint(record)
    time += 1000
    assertEquals(progress, game(record).frame().playedMs)
    time += 20_000
    assertEquals(0L, game(record).frame().playedMs)
  }
  @Test fun stalledFramesDoNotAddUnobservedTimeAndBadCoordinatesAreIgnored() {
    val game = game()
    val frame = play(game, 10)
    time += 2000
    val next = game.frame(Float.NaN)
    assertEquals(frame.playedMs, next.playedMs)
    assertTrue(next.paddle.isFinite())
    assertEquals(next.playedMs, game.frame(Float.POSITIVE_INFINITY).playedMs)
  }
}
