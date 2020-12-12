package cz.radekm.perf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import kotlin.math.max
import kotlin.random.Random

interface Aggregator<out T> {
    fun updateWith(i: Int)
    val state: T
}

private class MaxAggregator : Aggregator<Int> {
    private var st = Int.MAX_VALUE
    override fun updateWith(i: Int) {
        st = max(st, i)
    }
    override val state: Int
        get() = st
}

private class MutableSetAggregator : Aggregator<MutableSet<Int>> {
    private var st = mutableSetOf<Int>()
    override fun updateWith(i: Int) {
        st.add(i)
    }
    override val state: MutableSet<Int>
        get() = st
}

@State(Scope.Benchmark)
open class MessagesToBroadcast {
    private val numMessagesTotal = 1_000_000

    @Param("60", "70")
    var seed = 0

    var messages = Array<Int>(0) { error("Absurd") }

    @Param("1", "2", "4", "6", "8", "16", "32")
    var consumers = 0

    @Param("max", "mut-set")
    var aggregator = ""

    val aggregatorFactories = mapOf<String, () -> Aggregator<*>>(
        "max" to { MaxAggregator() },
        "mut-set" to { MutableSetAggregator() },
    )

    @Setup(Level.Trial)
    fun setUp() {
        val rnd = Random(seed)
        messages = Array(numMessagesTotal) { rnd.nextInt() }
    }
}


private const val BufferSize = 64

@BenchmarkMode(Mode.AverageTime)
open class BroadcastBenchmark {
    @Benchmark
    fun broadcastInSingleThread(st: MessagesToBroadcast, bh: Blackhole) {
        val aggregatorFactory = st.aggregatorFactories[st.aggregator] ?: error("Unknown aggregator")

        val aggregatorPerConsumer = Array(st.consumers) { aggregatorFactory() }

        // Feed each message to each consumer (or more precisely to its aggregator).
        for (m in st.messages) {
            for (a in aggregatorPerConsumer) {
                a.updateWith(m)
                bh.consume(a.state)
            }
        }
    }

    @Benchmark
    fun broadcastViaChannels(st: MessagesToBroadcast, bh: Blackhole) {
        runBlocking(Dispatchers.Default) {
            val aggregatorFactory = st.aggregatorFactories[st.aggregator] ?: error("Unknown aggregator")

            // Each consumer has its channel (its queue).
            val channelPerConsumer = Array(st.consumers) { Channel<Int>(BufferSize) }

            // Start consumers.
            for (channel in channelPerConsumer) {
                launch {
                    val a = aggregatorFactory()
                    for (m in channel) {
                        a.updateWith(m)
                        bh.consume(a.state)
                    }
                }
            }

            // Publish messages (ie. send each message to each consumer).
            for (m in st.messages) {
                for (channel in channelPerConsumer) {
                    channel.send(m)
                }
            }
            // Close channels so consumers stop after consuming the last message.
            for (channel in channelPerConsumer) {
                channel.close()
            }
        }
    }

    @Benchmark
    fun broadcastViaMutableSharedFlow(st: MessagesToBroadcast, bh: Blackhole) {
        runBlocking(Dispatchers.Default) {
            val aggregatorFactory = st.aggregatorFactories[st.aggregator] ?: error("Unknown aggregator")

            // Mutable shared flow is used to distribute messages to consumers.
            // `null` signals that all messages were processed and consumers should unsubscribe.
            val mutableSharedFlow = MutableSharedFlow<Int?>(extraBufferCapacity = BufferSize)

            // Semaphore used to delay message publishing until all consumers are subscribed.
            val subscribedConsumersSemaphore = Semaphore(st.consumers, st.consumers)

            // Start consumers.
            (0 until st.consumers).forEach {
                launch {
                    val a = aggregatorFactory()
                    mutableSharedFlow
                        .onSubscription { subscribedConsumersSemaphore.release() }
                        .takeWhile { it != null }
                        .collect { m ->
                            a.updateWith(m!!)
                            bh.consume(a.state)
                        }
                }
            }

            // Wait until all consumers are subscribed.
            (0 until st.consumers).forEach { subscribedConsumersSemaphore.acquire() }

            // Publish messages.
            for (m in st.messages) {
                mutableSharedFlow.emit(m)
            }
            // Signal that all messages were published and consumers can stop.
            mutableSharedFlow.emit(null)
        }
    }
}
