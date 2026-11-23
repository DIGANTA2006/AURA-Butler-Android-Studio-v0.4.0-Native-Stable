package `in`.diganta.aurabutler.document

import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryIntelligenceTest {
    @Test
    fun `dependency neighbor is retained for a relevant class`() {
        val selection = RepositoryIntelligence.select(
            files = listOf(
                RepositoryIntelligence.SourceFile(
                    "app/Main.kt",
                    "import data.UserRepo\nclass Main { private val repo = UserRepo() }"
                ),
                RepositoryIntelligence.SourceFile(
                    "data/UserRepo.kt",
                    "class UserRepo { fun loadUser() = 1 }"
                ),
                RepositoryIntelligence.SourceFile(
                    "data/UserRepoTest.kt",
                    "class UserRepoTest { fun testLoadUser() {} }"
                ),
                RepositoryIntelligence.SourceFile(
                    "build.gradle.kts",
                    "plugins { id(\"com.android.application\") }"
                )
            ),
            ownerPrompt = "fix UserRepo loadUser bug",
            maxFiles = 8
        )

        val paths = selection.selected.map { it.path }.toSet()
        assertTrue("data/UserRepo.kt" in paths)
        assertTrue("app/Main.kt" in paths)
        assertTrue(selection.projectMap.contains("app/Main.kt -> data/UserRepo.kt"))
    }

    @Test
    fun `architecture spine is retained for broad audits`() {
        val selection = RepositoryIntelligence.select(
            files = listOf(
                RepositoryIntelligence.SourceFile("src/Foo.kt", "class Foo"),
                RepositoryIntelligence.SourceFile("src/Bar.kt", "class Bar"),
                RepositoryIntelligence.SourceFile("settings.gradle.kts", "rootProject.name = \"demo\""),
                RepositoryIntelligence.SourceFile("build.gradle.kts", "plugins {}")
            ),
            ownerPrompt = "audit the project architecture",
            maxFiles = 8
        )

        val paths = selection.selected.map { it.path }.toSet()
        assertTrue("settings.gradle.kts" in paths)
        assertTrue("build.gradle.kts" in paths)
        assertTrue(selection.projectMap.contains("Repository Intelligence Map v0.17"))
    }
}
