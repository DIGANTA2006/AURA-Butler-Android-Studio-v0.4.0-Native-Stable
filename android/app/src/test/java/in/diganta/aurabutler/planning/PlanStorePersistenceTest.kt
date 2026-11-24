package `in`.diganta.aurabutler.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanStorePersistenceTest {
    @Test
    fun storeRestoresCurrentPlanAndBoundedCheckpointHistory() {
        val persistence = FakePlanPersistence()
        val firstStore = PlanStore(persistence)
        val plan = TaskPlanner.Plan(
            id = "durable-plan",
            title = "Prepare release",
            description = "Build, test and verify release"
        )

        firstStore.save(plan)
        firstStore.save(plan.copy(status = TaskPlanner.PlanStatus.ACTIVE))
        firstStore.save(plan.copy(status = TaskPlanner.PlanStatus.PAUSED))
        val restored = PlanStore(persistence)

        assertEquals(
            TaskPlanner.PlanStatus.PAUSED,
            restored.get("durable-plan")?.status
        )
        assertEquals(3, restored.getHistory("durable-plan").size)
        assertEquals(1, restored.getActivePlans().size)
    }

    @Test
    fun persistenceFailureDoesNotPublishAnUncommittedCheckpoint() {
        val persistence = FakePlanPersistence(failOnSave = true)
        val store = PlanStore(persistence)
        val plan = TaskPlanner.Plan(
            id = "not-committed",
            title = "Never reported saved",
            description = "Persistence failure must be visible"
        )

        assertThrows(IllegalStateException::class.java) {
            store.save(plan)
        }
        assertNull(store.get(plan.id))
        assertEquals(0, store.getHistory(plan.id).size)
    }

    @Test
    fun reloadObservesPlansCommittedByAnotherRuntime() {
        val persistence = FakePlanPersistence()
        val foreground = PlanStore(persistence)
        val background = PlanStore(persistence)
        val plan = TaskPlanner.Plan(
            id = "created-in-background",
            title = "Background plan",
            description = "A WorkManager worker created this plan."
        )
        background.save(plan.copy(status = TaskPlanner.PlanStatus.PAUSED))

        assertNull(foreground.get(plan.id))
        foreground.reload()

        assertEquals(TaskPlanner.PlanStatus.PAUSED, foreground.get(plan.id)?.status)
    }

    @Test
    fun reloadKeepsPlansWhenNoPersistenceAdapterIsConfigured() {
        val store = PlanStore()
        val plan = TaskPlanner.Plan(
            id = "memory-only",
            title = "In-memory plan",
            description = "No persistence adapter is configured."
        )
        store.save(plan)

        store.reload()

        assertEquals(plan.id, store.get(plan.id)?.id)
    }

    @Test
    fun deletionIsDurablyPropagated() {
        val persistence = FakePlanPersistence()
        val initial = PlanStore(persistence)
        val plan = TaskPlanner.Plan(
            id = "to-delete",
            title = "Temporary plan",
            description = "Remove on request"
        )
        initial.save(plan)

        initial.delete(plan.id)
        val restored = PlanStore(persistence)

        assertNull(restored.get(plan.id))
        assertEquals(0, restored.getAll().size)
    }

    private class FakePlanPersistence(
        private val failOnSave: Boolean = false
    ) : PlanStorePersistence {
        private val states = linkedMapOf<String, PersistedPlanState>()

        override fun loadAll(): List<PersistedPlanState> = states.values.toList()

        override fun save(state: PersistedPlanState) {
            check(!failOnSave) { "simulated durable write failure" }
            states[state.current.id] = state
        }

        override fun delete(planId: String) {
            states.remove(planId)
        }
    }
}
