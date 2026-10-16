package com.taskalarm.engine

import org.junit.Assert.*
import org.junit.Test

class VolumePolicyTest {
  @Test fun audibleLevelsArePreservedAndSilentLevelsGetAnAudibleFallback() {
    assertEquals(4, VolumePolicy.guardedLevel(4, 10))
    assertEquals(5, VolumePolicy.guardedLevel(0, 10))
    assertEquals(1, VolumePolicy.guardedLevel(0, 1))
    assertEquals(0, VolumePolicy.guardedLevel(0, 0))
    assertEquals(7, VolumePolicy.guardedLevel(20, 7))
  }
  @Test fun mutingRequiresBothConfigurationAndSolving() {
    assertFalse(VolumePolicy.mayMute(false, true))
    assertFalse(VolumePolicy.mayMute(true, false))
    assertTrue(VolumePolicy.mayMute(true, true))
  }
}
