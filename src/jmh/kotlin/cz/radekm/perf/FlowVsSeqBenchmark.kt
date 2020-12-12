package cz.radekm.perf

import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.stream.Stream
import kotlin.random.Random

typealias Sentence = List<String>

@State(Scope.Benchmark)
open class Sentences {
    private val numSentences = 1_000_000

    @Param("10", "20", "30")
    var seed = 0

    /**
     * Sentence is a list of words.
     */
    var sentences = Array(0) { emptyList<String>() }

    val verbs = listOf("is", "likes")
    private val words = listOf("dog", "cat", "big", "pig", "zebra", "long", "neck", "deck", "of", "cards", "red") + verbs

    @Setup(Level.Trial)
    fun setUp() {
        val rnd = Random(seed)
        sentences = Array(numSentences) {
            val wordsInSentence = rnd.nextInt(1, 10)
            val sentence = (1..wordsInSentence).map { words[rnd.nextInt(words.size)] }
            sentence
        }
    }
}


@BenchmarkMode(Mode.AverageTime)
open class FlowVsSeqBenchmark {

    @Benchmark
    fun forLoop(st: Sentences, bh: Blackhole) {
        for (sentence in st.sentences) {
            for (word in sentence) {
                if (word !in st.verbs) {
                    bh.consume(word)
                }
            }
        }
    }

    @Benchmark
    fun sequenceOf(st: Sentences, bh: Blackhole) {
        sequenceOf(*st.sentences)
            .flatten()
            .filter { word -> word !in st.verbs }
            .forEach { word -> bh.consume(word) }
    }

    @Benchmark
    fun flowOf(st: Sentences, bh: Blackhole) {
        runBlocking(Dispatchers.IO) {
            flowOf(*st.sentences)
                .flatMapConcat { sentence -> sentence.asFlow() }
                .filter { word -> word !in st.verbs }
                .collect { word -> bh.consume(word) }
        }
    }

    @Benchmark
    fun consumeAsFlow(st: Sentences, bh: Blackhole) {
        val capacity = 2048
        runBlocking(Dispatchers.IO) {
            val channel = Channel<Sentence>(capacity)
            launch {
                st.sentences.forEach { channel.send(it) }
                channel.close()
            }
            channel.consumeAsFlow()
                .flatMapConcat { sentence -> sentence.asFlow() }
                .filter { word -> word !in st.verbs }
                .collect { word -> bh.consume(word) }
        }
    }

    @Benchmark
    fun stream(st: Sentences, bh: Blackhole) {
        Stream.of(*st.sentences)
            .flatMap { sentence -> sentence.stream() }
            .filter { word -> word !in st.verbs }
            .forEach { word -> bh.consume(word) }
    }

    @Benchmark
    fun observable(st: Sentences, bh: Blackhole) {
        Observable.fromArray(*st.sentences)
            .flatMap { sentence -> Observable.fromIterable(sentence) }
            .filter { word -> word !in st.verbs }
            .forEach { word -> bh.consume(word) }
    }
}
