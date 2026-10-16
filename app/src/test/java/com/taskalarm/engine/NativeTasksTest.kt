package com.taskalarm.engine

import android.hardware.Sensor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NativeTasksTest {
  @Test fun stepCounterIgnoresHistoricalDuplicateAndResetReadings() {
    val progress=SensorProgress("walk",3)
    progress.sample(Sensor.TYPE_STEP_COUNTER,1000f,1,200)
    assertEquals(3,progress.steps)
    progress.sample(Sensor.TYPE_STEP_COUNTER,1002f,2,200); assertEquals(5,progress.steps)
    progress.sample(Sensor.TYPE_STEP_COUNTER,1010f,2,200); assertEquals(5,progress.steps)
    progress.sample(Sensor.TYPE_STEP_COUNTER,0f,3,200); assertEquals(5,progress.steps)
    progress.sample(Sensor.TYPE_STEP_COUNTER,1f,4,200); assertEquals(6,progress.steps)
    progress.sample(Sensor.TYPE_STEP_COUNTER,1000f,5,200); assertEquals(6,progress.steps)
    progress.pause(); progress.sample(Sensor.TYPE_STEP_COUNTER,1005f,6,200); assertEquals(6,progress.steps)
  }
  @Test fun detectorNeedsDistinctValidHardwareEvents() {
    val progress=SensorProgress("walk")
    progress.sample(Sensor.TYPE_STEP_DETECTOR,1f,100,200)
    progress.sample(Sensor.TYPE_STEP_DETECTOR,1f,100,200)
    progress.sample(Sensor.TYPE_STEP_DETECTOR,2f,101,200)
    progress.sample(Sensor.TYPE_STEP_DETECTOR,Float.NaN,102,200)
    assertEquals(1,progress.steps)
    progress.sample(Sensor.TYPE_STEP_DETECTOR,1f,103,200); assertEquals(2,progress.steps)
  }
  @Test fun lightNeedsContinuousFreshSamplesAndResetsOnDarkOrBackground() {
    val progress=SensorProgress("light")
    progress.sample(Sensor.TYPE_LIGHT,300f,1000,200)
    progress.sample(Sensor.TYPE_LIGHT,300f,2000,200)
    progress.sample(Sensor.TYPE_LIGHT,300f,3000,200); assertEquals(2000,progress.heldMs.toInt())
    progress.sample(Sensor.TYPE_LIGHT,100f,3500,200); assertEquals(0,progress.heldMs.toInt())
    progress.sample(Sensor.TYPE_LIGHT,300f,4000,200)
    progress.sample(Sensor.TYPE_LIGHT,300f,5000,200); assertEquals(1000,progress.heldMs.toInt())
    progress.stale(6600); assertEquals(0,progress.heldMs.toInt())
    progress.sample(Sensor.TYPE_LIGHT,300f,7000,200); assertEquals(0,progress.heldMs.toInt())
    progress.sample(Sensor.TYPE_LIGHT,300f,8000,200); progress.pause(); assertEquals(0,progress.heldMs.toInt())
  }
  @Test fun mazeCannotTunnelThroughWallsOrTeleportAfterAPause() {
    val maze=MazeGame("hard",JSONObject())
    var time=1000L; maze.frame(time,0f,1f)
    repeat(600) { time+=33; maze.frame(time,0f,1f) }
    val frame=maze.snapshot()
    assertTrue(frame.y < frame.walls.first().y)
    assertEquals(0,frame.reached); assertFalse(frame.complete)
    val before=frame.y; maze.frame(time+100_000,0f,1f); assertEquals(before,maze.snapshot().y,0f)
    maze.pause(); maze.frame(time+100_033,0f,1f); assertEquals(before,maze.snapshot().y,0f)
  }
  @Test fun everyMazeHasANavigableOrderedPathAndPersistsItsCheckpoints() {
    for(difficulty in listOf("easy","medium","hard")) {
      var maze=MazeGame(difficulty,JSONObject()); var time=1000L
      maze.frame(time,0f,0f)
      fun move(x: Float,y: Float) {
        repeat(2000) {
          val f=maze.snapshot(); val dx=x-f.x; val dy=y-f.y
          if(abs(dx)<.007f && abs(dy)<.007f) return
          time+=20; maze.frame(time,if(abs(dx)<.005f) 0f else dx.coerceIn(-.5f,.5f)*5,if(abs(dy)<.005f) 0f else dy.coerceIn(-.5f,.5f)*5)
        }
        fail("Maze path stuck at $difficulty: ${maze.snapshot()}")
      }
      val points=maze.snapshot().checkpoints
      for((i,point) in points.withIndex()) {
        move(point.x,maze.snapshot().y)
        move(point.x,point.y)
        assertEquals(i+1,maze.snapshot().reached)
        val saved=JSONObject(); maze.checkpoint(saved)
        maze=MazeGame(difficulty,saved); maze.frame(time,0f,0f)
      }
      assertTrue(maze.snapshot().complete)
    }
  }
}
