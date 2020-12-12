package cz.radekm.perf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    @Param("1", "16")
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
        runBlocking(Dispatchers.Default) {
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
        runBlocking(Dispatchers.Default) {
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
    fun stateBehindSingleSyncObject(st: Maps, bh: Blackhole) {
        runBlocking(Dispatchers.Default) {
            val state = mutableMapOf<MapId, MutableMap<String, Int>>()
            st.mapIds.forEach { mapId -> state[mapId] = mutableMapOf() }
            val sync = Any()
            produce(st) { m ->
                val map = state[m.mapId]!!
                synchronized(sync) {
                    for (item in m.items) {
                        map[item.key] = item.value
                    }
                }
            }
            bh.consume(state)
        }
    }

    @Benchmark
    fun stateBehindMultipleSyncObjects(st: Maps, bh: Blackhole) {
        data class SyncAndMap(val sync: Any, val map: MutableMap<String, Int>)

        runBlocking(Dispatchers.Default) {
            val state = mutableMapOf<MapId, SyncAndMap>()
            st.mapIds.forEach { mapId -> state[mapId] = SyncAndMap(Any(), mutableMapOf()) }
            produce(st) { m ->
                val (sync, map) = state[m.mapId]!!
                synchronized(sync) {
                    for (item in m.items) {
                        map[item.key] = item.value
                    }
                }
            }
            state.forEach { (_, map) -> bh.consume(map) }
        }
    }

    @Benchmark
    fun stateBehindSingleMutex(st: Maps, bh: Blackhole) {
        runBlocking(Dispatchers.Default) {
            val state = mutableMapOf<MapId, MutableMap<String, Int>>()
            st.mapIds.forEach { mapId -> state[mapId] = mutableMapOf() }
            val mutex = Mutex()
            produce(st) { m ->
                val map = state[m.mapId]!!
                mutex.withLock {
                    for (item in m.items) {
                        map[item.key] = item.value
                    }
                }
            }
            bh.consume(state)
        }
    }

    @Benchmark
    fun stateBehindMultipleMutexes(st: Maps, bh: Blackhole) {
        data class MutexAndMap(val mutex: Mutex, val map: MutableMap<String, Int>)

        runBlocking(Dispatchers.Default) {
            val state = mutableMapOf<MapId, MutexAndMap>()
            st.mapIds.forEach { mapId -> state[mapId] = MutexAndMap(Mutex(), mutableMapOf()) }
            produce(st) { m ->
                val (mutex, map) = state[m.mapId]!!
                mutex.withLock {
                    for (item in m.items) {
                        map[item.key] = item.value
                    }
                }
            }
            state.forEach { (_, map) -> bh.consume(map) }
        }
    }
}
