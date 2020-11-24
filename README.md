# Results of benchmarks

I'm running benchmark in VM in Azure Cloud:

- VM: Standard A2 v2 (2 vcpus, 4 GiB memory)
- Ubuntu 18.04
- OpenJDK: 11.0.9.1 (build 11.0.9.1+1-Ubuntu-0ubuntu1.18.04)

`build.gradle.kts` contains following JMH configuration

```
warmupIterations = 20
iterations = 20
fork = 3
threads = 1
```

and I'm using following bash command:

```
./gradlew jmh
```

# Latest results

## Kotlin Flow vs others:

For commit 3f9deeea:

Here we're comparing:
- Kotlin `Flow`,
- Kotlin `Sequence`,
- `Observable` from RxJava,
- `Stream` from Java standard library
- and a `for` loop (just for reference).

We have 1 million of sentences. Each sentence is a random list of 1 to 9 words.
We measure how long does it take to go through the words
and filter non-verbs. In the language of Kotlin `Sequence`:

```kotlin
sequenceOf(*sentences)
    .flatten()
    .filter { word -> word !in verbs }
    .forEach { word -> consume(word) }
```

Since the sentences are random we use multiple random seeds
to make the results representative:

```
Benchmark                         (seed)  Mode  Cnt  Score   Error  Units
FlowVsSeqBenchmark.consumeAsFlow      10  avgt   60  0.869 ± 0.011   s/op
FlowVsSeqBenchmark.consumeAsFlow      20  avgt   60  0.864 ± 0.005   s/op
FlowVsSeqBenchmark.consumeAsFlow      30  avgt   60  0.861 ± 0.007   s/op
FlowVsSeqBenchmark.flowOf             10  avgt   60  0.696 ± 0.007   s/op
FlowVsSeqBenchmark.flowOf             20  avgt   60  0.684 ± 0.009   s/op
FlowVsSeqBenchmark.flowOf             30  avgt   60  0.688 ± 0.007   s/op
FlowVsSeqBenchmark.forLoop            10  avgt   60  0.269 ± 0.001   s/op
FlowVsSeqBenchmark.forLoop            20  avgt   60  0.269 ± 0.001   s/op
FlowVsSeqBenchmark.forLoop            30  avgt   60  0.272 ± 0.002   s/op
FlowVsSeqBenchmark.observable         10  avgt   60  0.607 ± 0.001   s/op
FlowVsSeqBenchmark.observable         20  avgt   60  0.607 ± 0.002   s/op
FlowVsSeqBenchmark.observable         30  avgt   60  0.602 ± 0.002   s/op
FlowVsSeqBenchmark.sequenceOf         10  avgt   60  0.428 ± 0.002   s/op
FlowVsSeqBenchmark.sequenceOf         20  avgt   60  0.428 ± 0.002   s/op
FlowVsSeqBenchmark.sequenceOf         30  avgt   60  0.432 ± 0.004   s/op
FlowVsSeqBenchmark.stream             10  avgt   60  0.361 ± 0.003   s/op
FlowVsSeqBenchmark.stream             20  avgt   60  0.367 ± 0.002   s/op
FlowVsSeqBenchmark.stream             30  avgt   60  0.364 ± 0.001   s/op
```

### Commentary

It's no surprise that `for` loop is the fastest.
On the other hand it is surprising that
Java `Stream` is faster that Kotlin `Sequence` despite
the fact that Kotlin `Sequences` use coroutines which have
special support in Kotlin compiler itself.
And similarly among async primitives it's surprising
that `Observable` which again has no support in Kotlin
compile wins over Kotlin `Flow`.

### Overhead

`for` loop measures how much work is it to iterate through the array
of sentences and detect verbs. All benchmark have to do this work.
So if we subtract time taken by `for` loop we get the overhead:

Overhead for processing 1 million items (seed 10):

|Benchmark       | Time (ms) | Overhead (ms) |
|----------------|-----------|---------------|
|`consumeAsFlow` | 869       | 600           | 
|`flowOf`        | 696       | 427           | 
|`observable`    | 607       | 338           | 
|`sequenceOf`    | 428       | 159           | 
|`stream`        | 361       |  92           |

As you can see overhead is sometimes bigger than the work itself (269 ms).
