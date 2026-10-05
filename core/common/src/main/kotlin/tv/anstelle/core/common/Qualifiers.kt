package tv.anstelle.core.common

import javax.inject.Qualifier

enum class AnstelleDispatchers {
    Default,
    IO,
}

/** Qualifies an injected `CoroutineDispatcher`. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: AnstelleDispatchers)

/** Qualifies the process-wide `CoroutineScope` that outlives screens and services. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
