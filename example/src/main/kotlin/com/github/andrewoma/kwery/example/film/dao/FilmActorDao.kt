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

package com.github.andrewoma.kwery.example.film.dao

import com.github.andrewoma.kwery.core.Session
import com.github.andrewoma.kwery.example.film.model.FilmActor
import com.github.andrewoma.kwery.mapper.*
import com.github.andrewoma.kwery.example.film.model.FilmActor as FA


object filmActorTable : Table<FA, FA.Id>("film_actor", tableConfig), VersionedWithTimestamp {
    // @formatter:off
    val FilmId     by col(FA.Id::filmId,  path = { it.id }, id = true)
    val ActorId    by col(FA.Id::actorId, path = { it.id }, id = true)
    // @formatter:on

    override fun idColumns(id: FA.Id) = setOf(FilmId of id.filmId, ActorId of id.actorId)
    override fun create(value: Value<FA>) = FA(FA.Id(value of FilmId, value of ActorId))
}

class FilmActorDao(session: Session) : AbstractDao<FA, FA.Id>(session, filmActorTable, { it.id }, null, IdStrategy.Explicit) {

    fun findByFilmIds(ids: Collection<Int>): List<FilmActor> {
        val name = "findByFilmIds"
        val sql = sql(name) { "select $columns from ${table.name} where film_id in(unnest(:ids))" }
        val idsArray = session.connection.createArrayOf("int", ids.toTypedArray())
        return session.select(sql, mapOf("ids" to idsArray), options(name), table.rowMapper())
    }

    fun findByActorIds(ids: Collection<Int>): List<FilmActor> {
        val name = "findByActorIds"
        val sql = sql(name) { "select $columns from ${table.name} where actor_id in(unnest(:ids))" }
        val idsArray = session.connection.createArrayOf("int", ids.toTypedArray())
        return session.select(sql, mapOf("ids" to idsArray), options(name), table.rowMapper())
    }
}