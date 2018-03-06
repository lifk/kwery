/*
 * Copyright (c) 2015 Andrew O'Malley
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.github.andrewoma.kwery.core

import com.github.andrewoma.kwery.core.dialect.Dialect
import com.github.andrewoma.kwery.core.interceptor.StatementInterceptor
import com.github.andrewoma.kwery.core.interceptor.noOpStatementInterceptor
import org.intellij.lang.annotations.Language
import java.io.InputStream
import java.io.Reader
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.*

/**
 * DefaultSession wraps an single underlying JDBC connection.
 *
 * DefaultSession is NOT thread safe. It seems the underlying JDBC drivers are patchy with thread safety
 * on a single connection, so it seems a little pointless to make this safe.
 *
 * Typically, use a ThreadLocalSession in server environments. Alternatively, use a connection pool
 * and create a new session per transaction using SessionFactory.
 */
class DefaultSession(override val connection: Connection,
                     override val dialect: Dialect,
                     val interceptor: StatementInterceptor = noOpStatementInterceptor,
                     override val defaultOptions: StatementOptions = StatementOptions()
) : Session {

    companion object {
        /**
         * Shared cache for named queries converted to SQL prepared statements.
         * By default, an unbounded ConcurrentHashMap is used. If overridden, it should be done during initialisation
         * before any sessions are used.
         */
        var namedQueryCache: Cache<StatementCacheKey, BoundQuery> = ConcurrentHashMapCache()
    }

    override val currentTransaction: Transaction?
        get() = transaction

    var transaction: Transaction? = null

    override fun <R> select(@Language("SQL") sql: String, parameters: Map<String, Any?>, options: StatementOptions, mapper: (Row) -> R): List<R> {
        return withPreparedStatement(sql, listOf(parameters), options) { statement, ps ->
            bindParameters(parameters, statement)

            val result = arrayListOf<R>()
            val rs = ps.executeQuery()
            rs.use {
                var count = 0
                interceptor.executed(statement)
                while (rs.next()) {
                    result.add(mapper(Row(rs)))
                    count++
                }
                statement.copy(rowsCount = listOf(count)) to result
            }
        }
    }

    override fun batchUpdate(@Language("SQL") sql: String, parametersList: List<Map<String, Any?>>, options: StatementOptions): List<Int> {
        require(!parametersList.isEmpty()) { "Parameters cannot be empty for batchUpdate" }

        return withPreparedStatement(sql, parametersList, options) { statement, ps ->
            for (parameters in parametersList) {
                bindParameters(parameters, statement)
                ps.addBatch()
            }
            val rowsAffected = ps.executeBatch().toList()
            interceptor.executed(statement)
            statement.copy(rowsCount = rowsAffected) to rowsAffected
        }
    }

    override fun <K> batchInsert(@Language("SQL") sql: String, parametersList: List<Map<String, Any?>>, options: StatementOptions, f: (Row) -> K): List<Pair<Int, K>> {
        require(!parametersList.isEmpty()) { "Parameters cannot be empty for batchUpdate" }

        return withPreparedStatement(sql, parametersList, options.copy(useGeneratedKeys = true)) { statement, ps ->
            for (parameters in parametersList) {
                bindParameters(parameters, statement)
                ps.addBatch()
            }
            val rowsAffected = ps.executeBatch().toList()
            interceptor.executed(statement)

            val rs = ps.generatedKeys
            rs.use {
                val keys = ArrayList<K>(parametersList.size)
                while (rs.next()) {
                    keys.add(f(Row(rs)))
                }
                require(keys.size == parametersList.size) { "Expected ${parametersList.size} keys but received ${keys.size}" }
                statement.copy(rowsCount = rowsAffected) to rowsAffected.zip(keys)
            }
        }
    }

    override fun update(@Language("SQL") sql: String, parameters: Map<String, Any?>, options: StatementOptions): Int {
        return withPreparedStatement(sql, listOf(parameters), options) { statement, ps ->
            bindParameters(parameters, statement)
            val rowsAffected = ps.executeUpdate()
            interceptor.executed(statement)
            statement.copy(rowsCount = listOf(rowsAffected)) to rowsAffected
        }
    }

    override fun <K> insert(@Language("SQL") sql: String, parameters: Map<String, Any?>, options: StatementOptions, f: (Row) -> K): Pair<Int, K> {
        return withPreparedStatement(sql, listOf(parameters), options.copy(useGeneratedKeys = true)) { statement, ps ->
            bindParameters(parameters, statement)
            val rowsAffected = ps.executeUpdate()
            interceptor.executed(statement)
            val rs = ps.generatedKeys
            rs.use {
                require(rs.next()) { "No generated key received" }
                val keys = f(Row(rs))
                statement.copy(rowsCount = listOf(rowsAffected)) to (rowsAffected to keys)
            }
        }
    }

    override fun <R> asSequence(@Language("SQL") sql: String, parameters: Map<String, Any?>, options: StatementOptions, f: (Sequence<Row>) -> R): R {

        return withPreparedStatement(sql, listOf(parameters), options) { statement, ps ->
            bindParameters(parameters, statement)
            val rs = ps.executeQuery()
            rs.use {
                interceptor.executed(statement)
                val rowSequence = RowSequence(rs)
                val result = f(rowSequence)
                statement.copy(rowsCount = listOf(rowSequence.count)) to result
            }
        }
    }

    private class RowSequence(val rs: ResultSet) : Sequence<Row> {
        var count: Int = 0

        override fun iterator(): Iterator<Row> {
            return object : AbstractIterator<Row>() {
                override fun computeNext() {
                    if (rs.next()) {
                        count++
                        setNext(Row(rs))
                    } else {
                        done()
                    }
                }
            }
        }
    }

    override fun forEach(@Language("SQL") sql: String, parameters: Map<String, Any?>, options: StatementOptions, f: (Row) -> Unit) {
        withPreparedStatement(sql, listOf(parameters), options) { statement, ps ->
            bindParameters(parameters, statement)
            val rs = ps.executeQuery()
            rs.use {
                interceptor.executed(statement)
                var count = 0
                while (rs.next()) {
                    count++
                    f(Row(rs))
                }
                statement.copy(rowsCount = listOf(count)) to 1
            }
        }
    }

    override fun bindParameters(@Language("SQL") sql: String, parameters: Map<String, Any?>, closeParameters: Boolean, limit: Int, consumeStreams: Boolean): String {

        return replaceBindings(sql) { key ->
            val value = parameters[key]
            when (value) {
                null -> "null"
                is InputStream -> if (consumeStreams) dialect.bind(value, limit) else "<InputStream>"
                is Reader -> if (consumeStreams) dialect.bind(value, limit) else "<Reader>"
                is Collection<*> -> {
                    value.joinToString(",") { if (it == null) "null" else dialect.bind(it, limit) }
                }
                else -> dialect.bind(value, limit)
            }
        }
    }

    override fun <R> transaction(f: (Transaction) -> R): R {
        return if (transaction == null) DefaultTransaction(this).withTransaction(f) else f(transaction!!)
    }

    override fun manualTransaction(): ManualTransaction {
        return DefaultTransaction(this)
    }

    private fun <R> withPreparedStatement(sql: String,
                                          parameters: List<Map<String, Any?>> = listOf(),
                                          options: StatementOptions,
                                          f: (ExecutingStatement, PreparedStatement) -> Pair<ExecutingStatement, R>): R {
        var statement = ExecutingStatement(this, hashMapOf(), sql, parameters, options)
        try {
            statement = interceptor.construct(statement)

            statement = statement.copy(inClauseSizes = inClauseSizes(parameters))

            val namedQuery = namedQueryCache.getOrPut(createStatementCacheKey(options, sql, statement)) {
                val transformedSql = dialect.applyLimitAndOffset(options.limit, options.offset, sql.trimIndent())
                createBoundQuery(transformedSql, statement.inClauseSizes)
            }

            statement = statement.copy(sql = namedQuery.originalQuery)

            statement = interceptor.preparing(statement.copy(preparedSql = namedQuery.query, preparedParameters = namedQuery.bindings))

            statement = statement.copy(statement = prepareStatement(statement.preparedSql!!, options))
            interceptor.prepared(statement)

            val (s, result) = f(statement, statement.statement as PreparedStatement)
            statement = s
            return result
        } catch (e: Exception) {
            throw interceptor.exception(statement, e)
        } finally {
            try {
                interceptor.closed(statement)
            } finally {
                statement.statement?.close()
            }
        }
    }

    private fun createStatementCacheKey(options: StatementOptions, sql: String, statement: ExecutingStatement): StatementCacheKey {
        return StatementCacheKey(sql, statement.inClauseSizes, if (options.applyNameToQuery) options.name else null,
            options.limit != null, options.offset != null)
    }

    private fun prepareStatement(sql: String, options: StatementOptions): PreparedStatement {
        val statement = if (options.useGeneratedKeys || options.generatedKeyColumns.isNotEmpty()) {
            if (options.generatedKeyColumns.isNotEmpty()) {
                connection.prepareStatement(sql, options.generatedKeyColumns.toTypedArray())
            } else {
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
            }
        } else if (options.resultSetHoldability == ResultSetHoldability.ConnectionDefault) {
            connection.prepareStatement(sql, options.resultSetType.value, options.resultSetConcurrency.value)
        } else {
            connection.prepareStatement(sql, options.resultSetType.value, options.resultSetConcurrency.value, options.resultSetHoldability.value!!)
        }

        statement.fetchSize = options.fetchSize
        if (options.fetchDirection != ResultSetFetchDirection.Forward) {
            // Only override if not the default as sqlite won't accept it
            statement.fetchDirection = options.fetchDirection.value
        }
        if (options.poolable != options.usePreparedStatement) {
            // Only override if not the default value (true for PreparedStatements and false for Statements)
            // as some drivers (e.g. FileMaker) don't support setting of poolable
            statement.isPoolable = options.poolable
        }
        if (options.maxFieldSize != DefaultOptions.MAX_FIELD_SIZE) {
            //apache hive driver doesn't support MAX_FIELD_SIZE
            statement.maxFieldSize = options.maxFieldSize
        }

        statement.queryTimeout = options.queryTimeout

        if (options.maxRows <= Integer.MAX_VALUE) {
            statement.maxRows = options.maxRows.toInt()
        } else {
            statement.largeMaxRows = options.maxRows
        }

        options.beforeExecution(statement)

        return statement
    }

    private fun bindParameters(parameters: Map<String, Any?>, statement: ExecutingStatement): PreparedStatement {
        val ps = statement.statement as PreparedStatement
        var i = 0
        for (key in statement.preparedParameters) {
            val value = when (key) {
                Dialect.OffsetParam -> statement.options.offset
                Dialect.LimitParam -> statement.options.limit
                else -> parameters[key]
            }
            require(value != null || parameters.containsKey(key)) { "Unknown query parameter: '$key'" }
            when (value) {
                is TypedParameter -> ps.setObject(++i, value.value, value.sqlType)
                is InputStream -> ps.setBinaryStream(++i, value)
                is Reader -> ps.setCharacterStream(++i, value)
                is Collection<*> -> {
                    val inClauseSize = statement.inClauseSizes[key]!!
                    setInClause(ps, value, i, inClauseSize)
                    i += inClauseSize
                }
                else -> ps.setObject(++i, value)
            }
        }
        return ps
    }

    private fun setInClause(ps: PreparedStatement, values: Collection<*>, offset: Int, size: Int) {
        for ((i, value) in values.withIndex()) {
            ps.setObject(offset + i + 1, value)
        }
        for (i in values.size until size) {
            ps.setObject(offset + i + 1, null)
        }
    }
}

class TypedParameter(val value: Any?, val sqlType: Int)

data class ExecutingStatement(
    val session: Session,
    val contexts: MutableMap<String, Any?>,
    val sql: String,
    val parametersList: List<Map<String, Any?>>,
    val options: StatementOptions,
    val preparedSql: String? = null,
    val preparedParameters: List<String> = arrayListOf(),
    val statement: Statement? = null,
    val inClauseSizes: Map<String, Int> = mapOf(),
    val rowsCount: List<Int> = listOf()
)

/**
 * StatementCacheKey contains the sql and any options that modify the generated prepared statement
 */
data class StatementCacheKey(val sql: String, val collections: Map<String, Int>, val name: String?,
                             val limit: Boolean, val offset: Boolean)
