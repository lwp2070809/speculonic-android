package de.lwp2070809.speculonic.data

import java.io.FileNotFoundException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MigrationOutcomeTest {
    @Test
    fun missingSourceIsDeferredAndTargetFailuresAreReported() {
        val outcome = MigrationOutcome()
        outcome.recordFailure(MissingPrivateAudioException())
        assertNull(outcome.firstFailure)
        val targetError = FileNotFoundException("Target provider refused the lyrics output stream")
        outcome.recordFailure(targetError)
        outcome.recordFailure(MissingPrivateAudioException())
        assertSame(targetError, outcome.firstFailure)
    }

    @Test
    fun revokedPermissionTakesPrecedenceOverEarlierWriteFailure() {
        val outcome = MigrationOutcome()
        outcome.recordFailure(FileNotFoundException("Target audio"))
        val error = SecurityException("Permission revoked")
        outcome.recordFailure(error)
        assertSame(error, outcome.firstFailure)
    }
}
