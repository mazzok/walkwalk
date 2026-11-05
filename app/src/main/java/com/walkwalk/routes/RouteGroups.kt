package com.walkwalk.routes

import com.walkwalk.data.HikeEntity
import com.walkwalk.data.HikeRepository
import com.walkwalk.domain.LatLon
import com.walkwalk.domain.RouteMatch
import com.walkwalk.domain.RouteShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

/** A row of the hike list: one hike, or the hikes that took the same route (newest first). */
sealed interface HikeListEntry {
    val newest: HikeEntity

    data class Single(val hike: HikeEntity) : HikeListEntry {
        override val newest get() = hike
    }

    data class Group(val name: String?, val hikes: List<HikeEntity>) : HikeListEntry {
        override val newest get() = hikes.first()
    }
}

/** Groups the finished hikes by route for the hike list; [limitM] is the route bubble, 0 = no grouping. */
class RouteGroups(private val repo: HikeRepository, private val limitM: Flow<Int>) {
    // a finished hike's geometry never changes, so its resampled shape is kept for the app's lifetime
    private val shapes = ConcurrentHashMap<Long, List<LatLon>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<HikeListEntry>> =
        combine(repo.observeFinishedHikes(), limitM) { hikes, m -> hikes to m }
            .mapLatest { (hikes, m) -> entries(hikes, m) }
            .flowOn(Dispatchers.Default)

    private suspend fun entries(hikes: List<HikeEntity>, limitM: Int): List<HikeListEntry> {
        if (limitM <= 0) return hikes.map { HikeListEntry.Single(it) }
        shapes.keys.retainAll(hikes.map { it.id }.toSet())
        val oldestFirst = hikes.sortedWith(compareBy({ it.startTime }, { it.id }))
        val routeShapes = oldestFirst.map { h ->
            RouteShape(h.id, shapes[h.id] ?: RouteMatch.resample(repo.points(h.id)).also { shapes[h.id] = it })
        }
        val byId = hikes.associateBy { it.id }
        return RouteMatch.group(routeShapes, limitM.toDouble())
            .map { ids -> ids.map { byId.getValue(it) }.sortedWith(compareByDescending<HikeEntity> { it.startTime }.thenByDescending { it.id }) }
            .map { members -> if (members.size == 1) HikeListEntry.Single(members[0]) else HikeListEntry.Group(groupName(members), members) }
            .sortedWith(compareByDescending<HikeListEntry> { it.newest.startTime }.thenByDescending { it.newest.id })
    }

    companion object {
        /** The most common route name among [hikes] (newest first); a tie goes to the newest hike's name. */
        fun groupName(hikes: List<HikeEntity>): String? {
            val names = hikes.mapNotNull { it.routeName }
            val counts = names.groupingBy { it }.eachCount()
            return names.distinct().maxByOrNull { counts.getValue(it) }
        }
    }
}
