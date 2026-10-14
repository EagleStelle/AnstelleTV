package tv.anstelle.core.common

import javax.inject.Qualifier

enum class AnstelleDispatchers {
    Default,
    IO,

    /** Lowest-priority threads for long work nobody waits on (library channels, schedules), so the screen and playback keep the CPU. */
    Background,
}

/** Qualifies an injected `CoroutineDispatcher`. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: AnstelleDispatchers)

/** Qualifies the process-wide `CoroutineScope` that outlives screens and services. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
