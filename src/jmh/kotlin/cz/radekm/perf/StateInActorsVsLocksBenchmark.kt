package cz.radekm.perf

import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.AbstractMap
import kotlin.random.Random

typealias MapId = String

data class InsertIntoMap(val mapId: MapId, val items: List<Map.Entry<String, Int>>)

@State(Scope.Benchmark)
open class Maps {
    private val numInsertsTotal = 1_000_000

    @Param("40", "50")
    var seed = 0

    @Param("1", "2", "4", "8")
    var numProducers = 0
    @Param("1", "8")
    var numMaps = 0
    @Param("1", "32")
    var mapEntriesPerInsert = 0

    var mapIds = mutableSetOf<MapId>()
    var insertsPerProducer: Array<MutableList<InsertIntoMap>> = Array(0) { error("Absurd") }

    @Setup(Level.Trial)
    fun setUp() {
        val rnd = Random(seed)
        fun randomString() = String(rnd.nextBytes(15))

        insertsPerProducer = Array(numProducers) { mutableListOf() }

        mapIds = mutableSetOf()
        while (mapIds.size < numMaps) {
            mapIds.add(randomString())
        }
        val mapIdsList = mapIds.toList()

        (1..numInsertsTotal).forEach {
            val producer = rnd.nextInt(numProducers)
            val mapId = mapIdsList[rnd.nextInt(numMaps)]
            val items = List(mapEntriesPerInsert) {
                AbstractMap.SimpleEntry(randomString(), rnd.nextInt())
            }
            insertsPerProducer[producer].add(
                InsertIntoMap(mapId, items)
            )
        }
    }
}

@BenchmarkMode(Mode.AverageTime)
open class StateInActorsVsLocksBenchmark {
    /**
     * Terminates after all producers finish.
     */
    private suspend inline fun produce(st: Maps, crossinline send: suspend (InsertIntoMap) -> Unit) {
        coroutineScope {
            (0 until st.numProducers).forEach { producer ->
                launch { st.insertsPerProducer[producer].forEach { send(it) } }
            }
        }
    }

    @Benchmark
    fun stateInSingleActor(st: Maps, bh: Blackhole) {
        runBlocking {
            val actorQueue = actor<InsertIntoMap> {
                val state = mutableMapOf<MapId, MutableMap<String, Int>>()
                st.mapIds.forEach { mapId -> state[mapId] = mutableMapOf() }
                try {
                    for (m in this.channel) {
                        for (item in m.items) {
                            state[m.mapId]!![item.key] = item.value
                        }
                    }
                } finally {
                    bh.consume(state)
                }
            }
            produce(st) { actorQueue.send(it) }
            actorQueue.close()
        }
    }

    @Benchmark
    fun stateInMultipleActors(st: Maps, bh: Blackhole) {
        runBlocking {
            val actorQueues = st.mapIds.map { mapId ->
                mapId to actor<InsertIntoMap> {
                    val map = mutableMapOf<String, Int>()
                    try {
                        for (m in this.channel) {
                            for (item in m.items) {
                                map[item.key] = item.value
                            }
                        }
                    } finally {
                        bh.consume(map)
                    }
                }
            }.toMap()
            produce(st) { actorQueues[it.mapId]!!.send(it) }
            actorQueues.forEach { (_, queue) -> queue.close() }
        }
    }

    @Benchmark
    fun stateBehindSingleLock(st: Maps, bh: Blackhole) {
        runBlocking {
            val state = mutableMapOf<MapId, MutableMap<String, Int>>()
            st.mapIds.forEach { mapId -> state[mapId] = mutableMapOf() }
            val lock = Any()
            produce(st) { m ->
                synchronized(lock) {
                    for (item in m.items) {
                        state[m.mapId]!![item.key] = item.value
                    }
                }
            }
            bh.consume(state)
        }
    }

    @Benchmark
    fun stateBehindMultipleLocks(st: Maps, bh: Blackhole) {
        data class LockAndMap(val lock: Any, val map: MutableMap<String, Int>)

        runBlocking {
            val state = mutableMapOf<MapId, LockAndMap>()
            st.mapIds.forEach { mapId -> state[mapId] = LockAndMap(Any(), mutableMapOf()) }
            produce(st) { m ->
                val (lock, map) = state[m.mapId]!!
                synchronized(lock) {
                    for (item in m.items) {
                        map[item.key] = item.value
                    }
                }
            }
            state.forEach { (_, map) -> bh.consume(map) }
        }
    }
}
