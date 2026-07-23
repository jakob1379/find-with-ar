package dev.arsignallocator.ui

import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class TopDownMapProjectionTest {
    @Test
    fun `generated ARCore right turns remain right turns on the map`() {
        val random = Random(20260723L)
        repeat(100) {
            val originX = random.nextDouble() * 8.0 - 4.0
            val originZ = random.nextDouble() * 8.0 - 4.0
            val heading = random.nextDouble() * 2.0 * Math.PI
            val forwardX = kotlin.math.sin(heading)
            val forwardZ = -kotlin.math.cos(heading)
            val rightX = kotlin.math.cos(heading)
            val rightZ = kotlin.math.sin(heading)
            val origin = topDownMapOffset(originX, originZ, -5.0, -5.0, 10f)
            val beforeTurn = topDownMapOffset(
                originX + forwardX,
                originZ + forwardZ,
                -5.0,
                -5.0,
                10f,
            )
            val afterTurn = topDownMapOffset(
                originX + forwardX + rightX,
                originZ + forwardZ + rightZ,
                -5.0,
                -5.0,
                10f,
            )
            val incomingX = beforeTurn.x - origin.x
            val incomingY = beforeTurn.y - origin.y
            val outgoingX = afterTurn.x - beforeTurn.x
            val outgoingY = afterTurn.y - beforeTurn.y

            assertTrue(
                "map reflected a right turn at heading $heading",
                incomingX * outgoingY - incomingY * outgoingX > 0f,
            )
        }
    }
}
