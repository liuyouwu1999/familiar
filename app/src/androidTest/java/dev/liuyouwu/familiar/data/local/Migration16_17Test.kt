package dev.liuyouwu.familiar.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class Migration16_17Test {
    private val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val db = "test-db-learning-profile"

    @Test
    fun migrate16To17_createsTableWithDefaults() {
        helper.createDatabase(db, 16).close()
        helper.runMigrationsAndValidate(db, 17, true, AppDatabase.MIGRATION_16_17).close()
    }
}
