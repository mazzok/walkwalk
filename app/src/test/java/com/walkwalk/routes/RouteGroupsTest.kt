package com.walkwalk.routes

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeRepository
import com.walkwalk.data.HikeSource
import com.walkwalk.data.HikeStatus
import com.walkwalk.data.TrackPointEntity
import com.walkwalk.data.WalkwalkDb
import com.walkwalk.domain.TrackPoint
import com.walkwalk.domain.straightLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteGroupsTest {
    private lateinit var db: WalkwalkDb
    private lateinit var repo: HikeRepository
    private val limitM = MutableStateFlow(50)
    private lateinit var groups: RouteGroups

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), WalkwalkDb::class.java)
            .allowMainThreadQueries().build()
        repo = HikeRepository(db)
        groups = RouteGroups(repo, limitM)
    }

    @After
    fun tearDown() = db.close()

    private val ab = straightLine(2.0, 5_000, 101)
    // points are stored by time, so the way back reverses the positions, not the times
    private val ba = ab.zip(ab.reversed()) { t, pos -> pos.copy(time = t.time) }

    private suspend fun hike(start: Long, pts: List<TrackPoint>, routeName: String? = null): Long {
        val dao = db.hikeDao()
        val id = dao.insertHike(
            HikeEntity(name = "h$start", startTime = start, endTime = start + 1, status = HikeStatus.FINISHED,
                source = HikeSource.RECORDED, routeName = routeName),
        )
        dao.insertPoints(pts.map { TrackPointEntity.from(id, it) })
        return id
    }

    private fun List<HikeListEntry>.ids() = map { e ->
        when (e) {
            is HikeListEntry.Single -> listOf(e.hike.id)
            is HikeListEntry.Group -> e.hikes.map { it.id }
        }
    }

    @Test
    fun groupsSameRouteAndDirectionNewestFirst() = runTest {
        val a1 = hike(1000, ab)
        val b1 = hike(2000, ba)
        val a2 = hike(3000, ab)
        val other = hike(4000, straightLine(2.0, 5_000, 101, startLat = 48.0))
        val b2 = hike(5000, ba)
        val entries = groups.observe().first()
        assertEquals(listOf(listOf(b2, b1), listOf(other), listOf(a2, a1)), entries.ids())
        assertEquals(HikeListEntry.Single::class, entries[1]::class)
    }

    @Test
    fun offListsEveryHikeAlone() = runTest {
        val a1 = hike(1000, ab)
        val a2 = hike(2000, ab)
        limitM.value = 0
        assertEquals(listOf(listOf(a2), listOf(a1)), groups.observe().first().ids())
    }

    @Test
    fun groupTakesTheMostCommonRouteName() = runTest {
        hike(1000, ab, "A nach B")
        hike(2000, ab, "A to B")
        hike(3000, ab, "A nach B")
        hike(4000, ab)
        val group = groups.observe().first().single() as HikeListEntry.Group
        assertEquals("A nach B", group.name)
    }

    @Test
    fun nameTieGoesToTheNewestHike() {
        fun h(start: Long, name: String?) =
            HikeEntity(name = "h", startTime = start, endTime = null, status = HikeStatus.FINISHED, source = HikeSource.RECORDED, routeName = name)
        assertEquals("new", RouteGroups.groupName(listOf(h(3, "new"), h(2, null), h(1, "old"))))
        assertEquals(null, RouteGroups.groupName(listOf(h(2, null), h(1, null))))
    }

    @Test
    fun namingAGroupShowsUpForNewHikes() = runTest {
        val a1 = hike(1000, ab)
        val a2 = hike(2000, ab)
        repo.nameRoute(listOf(a1, a2), "A nach B")
        hike(3000, ab)
        val group = groups.observe().first().single() as HikeListEntry.Group
        assertEquals("A nach B", group.name)
        assertEquals(3, group.hikes.size)
    }
}
