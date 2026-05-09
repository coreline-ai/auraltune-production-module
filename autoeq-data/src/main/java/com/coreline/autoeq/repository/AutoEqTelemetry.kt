package com.coreline.autoeq.repository

/**
 * Minimal telemetry sink for AutoEQ data layer events.
 *
 * The data layer fires neutral, redacted events. PII enforcement (BT MAC, file names)
 * is the caller's responsibility. Common event names emitted by this module:
 *
 * - `search_executed` — fired by the search engine.
 * - `fetch_failed` — network or HTTP error during catalog/profile download.
 * - `parse_failed` — `ParametricEqParser` returned [com.coreline.autoeq.model.ParseResult.Failure].
 * - `cache_evicted` — LRU evicted at least one profile entry.
 * - `catalog_corruption_detected` — cached catalog JSON failed to decode.
 *
 * Implementations must be cheap and side-effect-light; they may be invoked from any
 * dispatcher except the audio thread.
 */
interface AutoEqTelemetry {
    /**
     * Record a single event.
     *
     * @param name event name (snake_case).
     * @param properties bag of context. Values must be primitive / String / Boolean / Number;
     *   implementations are free to drop unsupported types.
     */
    fun event(name: String, properties: Map<String, Any?> = emptyMap())

    /** No-op sink. Used as the default when the host app has not wired up analytics. */
    object NoOp : AutoEqTelemetry {
        override fun event(name: String, properties: Map<String, Any?>) = Unit
    }
}
