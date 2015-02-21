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

import java.sql.Connection
import com.github.andrewoma.kwery.core.dialect.Dialect
import com.github.andrewoma.kwery.core.interceptor.StatementInterceptor
import kotlin.properties.Delegates
import javax.sql.DataSource

/**
 * ThreadLocalSession creates sessions on a per thread basis. It allows services
 * using sessions to be defined with static references to sessions without worrying about
 * managing connections (or transactions).
 *
 * Typically, a single instance of ThreadLocalSession per data source would
 * be shared amongst services. In a container environment a per thread filter or similar mechanism
 * would then initialise and finalise a session before use.
 *
 * Sessions are allocated lazily (on first use of the session). So there is no overhead in terms of
 * allocating connections if the thread doesn't actually use a session.
 */
private val defaultThreadLocalSessionName = "default"

public class ThreadLocalSession(val dataSource: DataSource,
                                val dialect: Dialect,
                                val interceptors: List<StatementInterceptor> = listOf(),
                                val name: String = defaultThreadLocalSessionName,
                                override val defaultSelectOptions: SelectOptions = SelectOptions(),
                                override val defaultUpdateOptions: UpdateOptions = UpdateOptions()) : Session {

    class SessionConfig(val startTransaction: Boolean, val session: DefaultSession?, val transaction: ManualTransaction?)

    class object {
        private val threadLocalSession = object : ThreadLocal<MutableMap<String, SessionConfig>>() {
            override fun initialValue(): MutableMap<String, SessionConfig> {
                return hashMapOf()
            }
        }

        public fun initialise(name: String = defaultThreadLocalSessionName, startTransaction: Boolean = true) {
            val configs = threadLocalSession.get()
            check(!configs.containsKey(name), "A session is already initialised for this thread")
            configs.put(name, SessionConfig(startTransaction, null, null))
        }

        public fun finalise(name: String, commitTransaction: Boolean) {
            val configs = threadLocalSession.get()
            val config = configs.get(name) ?: error("A session has not been initialised for this thread")
            try {
                closeSession(commitTransaction, config)
            } finally {
                configs.remove(name)
            }
        }

        private fun closeSession(commitTransaction: Boolean, config: SessionConfig) {
            if (config.session == null) return // A session was never created in this thread

            try {
                if (config.transaction != null) {
                    check(config.session.currentTransaction == config.transaction, "Unexpected transaction in session")
                    if (commitTransaction && !config.transaction.rollbackOnly) {
                        config.transaction.commit()
                    } else {
                        config.transaction.rollback()
                    }
                }
            } finally {
                config.session.connection.close()
            }
        }
    }

    override val currentTransaction: Transaction? by Delegates.lazy { session.currentTransaction }
    override val connection: Connection by Delegates.lazy { session.connection }

    private val session: DefaultSession
        get() {
            val configs = threadLocalSession.get()
            val config = configs.get(name) ?: error("A session has not been initialised for this thread")
            return if (config.session == null) {
                val session = DefaultSession(dataSource.getConnection(), dialect, interceptors, defaultSelectOptions, defaultUpdateOptions)
                val transaction = if (!config.startTransaction) null else session.manualTransaction()
                configs.put(name, SessionConfig(config.startTransaction, session, transaction))
                session
            } else {
                config.session
            }
        }

    override fun <R> select(sql: String, parameters: Map<String, Any?>, options: SelectOptions, mapper: (Row) -> R): List<R> {
        return session.select(sql, parameters, options, mapper)
    }

    override fun update(sql: String, parameters: Map<String, Any?>, options: UpdateOptions): Int {
        return session.update(sql, parameters, options)
    }

    override fun batchUpdate(sql: String, parametersList: List<Map<String, Any?>>, options: UpdateOptions): List<Int> {
        return session.batchUpdate(sql, parametersList, options)
    }

    override fun <K> batchUpdate(sql: String, parametersList: List<Map<String, Any?>>, options: UpdateOptions, f: (Row) -> K): List<Pair<Int, K>> {
        return session.batchUpdate(sql, parametersList, options, f)
    }

    override fun <K> update(sql: String, parameters: Map<String, Any?>, options: UpdateOptions, f: (Row) -> K): Pair<Int, K> {
        return session.update(sql, parameters, options, f)
    }

    override fun stream(sql: String, parameters: Map<String, Any?>, options: SelectOptions, f: (Row) -> Unit) {
        return session.stream(sql, parameters, options, f)
    }

    override fun bindParameters(sql: String, parameters: Map<String, Any?>): String {
        return session.bindParameters(sql, parameters)
    }

    override fun <R> transaction(f: (Transaction) -> R): R {
        return session.transaction(f)
    }

    override fun manualTransaction(): ManualTransaction {
        return session.manualTransaction()
    }
}