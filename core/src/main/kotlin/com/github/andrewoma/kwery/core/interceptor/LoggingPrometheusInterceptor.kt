package com.github.andrewoma.kwery.core.interceptor

import com.github.andrewoma.kommon.util.StopWatch
import com.github.andrewoma.kwery.core.ExecutingStatement
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary
import java.util.concurrent.TimeUnit

class LoggingPrometheusInterceptor : StatementInterceptor {
    companion object {
        val queries: Counter = Counter.build().name("queries_total").help("Total number of queries executed").register()
        val inProgressQueries: Gauge = Gauge.build().name("in_progress_queries").help("Queries in progress").register()
        val durations: Summary = Summary.build().name("query_duration").help("Duration of the queries in seconds").register()
    }

    private data class Context(val stopWatch: StopWatch, val executedTiming: Long = 0, val exception: Exception? = null)

    private var ExecutingStatement.context: LoggingPrometheusInterceptor.Context
        get() = this.contexts[LoggingPrometheusInterceptor::class.java.name]!! as LoggingPrometheusInterceptor.Context
        set(value) {
            this.contexts[LoggingPrometheusInterceptor::class.java.name] = value
        }

    override fun prepared(statement: ExecutingStatement) {
        LoggingPrometheusInterceptor.inProgressQueries.inc()
    }

    override fun executed(statement: ExecutingStatement) {
        statement.context = statement.context.copy(executedTiming = statement.context.stopWatch.elapsed(TimeUnit.SECONDS))
    }

    override fun construct(statement: ExecutingStatement): ExecutingStatement {
        statement.context = LoggingPrometheusInterceptor.Context(StopWatch().start())
        return statement
    }

    override fun closed(statement: ExecutingStatement) {
        LoggingPrometheusInterceptor.queries.inc()
        LoggingPrometheusInterceptor.inProgressQueries.dec()
        LoggingPrometheusInterceptor.durations.observe(statement.context.executedTiming.toDouble())
    }
}