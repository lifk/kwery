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

package com.github.andrewoma.kwery.fetcher

import java.util.concurrent.ConcurrentHashMap

class Value<T>(val get: () -> T, val set: (T) -> Unit) {
    override fun toString(): String {
        return get().toString()
    }
}

class GraphFetcher(val types: Set<Type<*, *>>) {
    private val typeCache: MutableMap<Class<*>, Type<*, *>> = ConcurrentHashMap()
    private val noType: Type<*, *> = Type({ it }, { mapOf() })

    fun <T> fetch(objects: Collection<T>, root: Node): List<T> {
        if (objects.isEmpty()) return listOf()

        val fetched: MutableMap<Type<Any?, Any?>, MutableMap<Any?, Any?>> = hashMapOf()
        val result = objects.toArrayList()
        val mutableObjects = result.indices.map { i ->
            Value({ result[i] }, { result[i] = it })
        }

        fetch("", mutableObjects, root, fetched)

        return result
    }

    class ChildKey(val type: Type<Any?, Any?>, val node: Node) {
        override fun equals(other: Any?) = other is ChildKey && this.type == other.type && this.node.children == other.node.children
        override fun hashCode() = this.type.hashCode() * 31 + this.node.children.hashCode()
    }

    class Children(val valuesByTypeAndNode: MutableMap<ChildKey, MutableMap<Any?, Value<Any?>>> = hashMapOf(),
                   val idsByType: MutableMap<Type<Any?, Any?>, MutableSet<Any?>> = hashMapOf()
    )

    suppress("UNCHECKED_CAST")
    private fun <T> fetch(indent: String, values: Iterable<Value<T>>, root: Node, fetched: MutableMap<Type<Any?, Any?>, MutableMap<Any?, Any?>>) {
        //        println("${indent}fetch(root=$root, values=$values)")

        val type = findMatchingType(values.first().get())
        val properties = findMatchingProperties(type, root)

        fun fetchChildren(children: Children) {
            for ((childType, ids) in children.idsByType) {
                fetched.getOrPut(childType) { hashMapOf() }.putAll(childType.fetch(ids))
            }

            val newIndent = indent + "    "
            for ((key, childValues) in children.valuesByTypeAndNode) {
                fetch(newIndent, childValues.values(), key.node, fetched)
            }
        }

        fun fetchProperties(properties: List<Pair<Property<Any?, Any?, Any?>, Node>>) {

            fun collectRequiredObjectIdsByType(): Map<Type<Any?, Any?>, Set<Any?>> {
                val required: MutableMap<Type<Any?, Any?>, MutableSet<Any?>> = hashMapOf()
                for (value in values) {
                    for ((property, node) in properties) {
                        val id = property.id(value.get())
                        if (id != null && !(fetched[property.type]?.containsKey(id) ?: false)) {
                            required.getOrPut(property.type) { hashSetOf() }.add(id)
                        }
                    }
                }
                return required
            }

            fun fetchRequired(required: Map<Type<Any?, Any?>, Set<Any?>>) {
                for ((requiredType, ids) in required) {
                    fetched.getOrPut(requiredType) { hashMapOf() }.putAll(requiredType.fetch(ids))
                }
            }

            fun applyFetchedObjects(): Children {
                val children = Children()

                // TODO ... invert looping (values within properties)
                // Apply the fetched objects
                for (value in values) {
                    for ((property, node) in properties) {
                        val id = property.id(value.get())
                        val existing = fetched[property.type]?.get(id)
                        if (existing == null) continue // May happen if the object was deleted.

                        // Apply the fetched object to it's containing object
                        value.set(property.apply(value.get(), existing) as T)

                        // Collect children
                        if (!node.children.isEmpty() || node == Node.allDescendants) {
                            val child = Value({ property.property.get(value.get()) }, { value.set(property.apply(value.get(), it) as T) })
                            children.valuesByTypeAndNode.getOrPut(ChildKey(property.type, node)) { hashMapOf() }.put(id, child)

                            if (!(fetched[property.type]?.containsKey(id) ?: false)) {
                                children.idsByType.getOrPut(property.type) { hashSetOf() }.add(id)
                            }
                        }
                    }
                }
                return children
            }

            val required = collectRequiredObjectIdsByType()

            fetchRequired(required)

            val children = applyFetchedObjects()

            fetchChildren(children)
        }

        fun fetchCollectionProperties(properties: List<Pair<CollectionProperty<Any?, Any?, Any?>, Node>>) {
            class Deferred(val property: CollectionProperty<Any?, Any?, Any?>, val value: Value<T>, val children: List<Value<Any?>>)

            val children = Children()
            val deferredList: MutableList<Deferred> = arrayListOf()

            for ((property, node) in properties) {

                // Collect all the ids
                val required: MutableSet<Any?> = hashSetOf()
                for (value in values) {
                    val id = property.id(value.get())
                    if (id != null) {
                        required.add(id)
                    }
                }

                val fetchedById = property.fetch(required)
                val fetchedByType = fetched.getOrPut(property.type) { hashMapOf() }
                for (collection in fetchedById.values()) {
                    for (obj in collection) {
                        val id = property.type.id(obj)
                        fetchedByType.put(id, obj)
                    }
                }

                for (value in values) {
                    val id = property.id(value.get())
                    val existing = (fetchedById.get(id) ?: listOf()).toArrayList()

                    if (node.children.isEmpty() && node != Node.allDescendants) {
                        // Apply the fetched object to it's containing object
                        value.set(property.apply(value.get(), existing as Collection<T>) as T)
                    } else {
                        val childValues = existing.indices.map { i -> Value({ existing[i] }, { existing[i] = it }) }
                        deferredList.add(Deferred(property, value, childValues))

                        for (child in childValues) {
                            children.valuesByTypeAndNode.getOrPut(ChildKey(property.type, node)) { hashMapOf() }.put(id, child)
                            if (!(fetched[property.type]?.containsKey(id) ?: false)) {
                                children.idsByType.getOrPut(property.type) { hashSetOf() }.add(id)
                            }
                        }
                    }
                }
            }

            fetchChildren(children)

            // Apply the sets now that children have been fetched
            for (deferred in deferredList) {
                val existing = deferred.children.map { it.get() }
                deferred.value.set(deferred.property.apply(deferred.value.get(), existing as Collection<T>) as T)
            }
        }

        fetchProperties(properties.filter { it.first is Property<*, *, *> } as List<Pair<Property<Any?, Any?, Any?>, Node>>)
        fetchCollectionProperties(properties.filter { it.first is CollectionProperty<*, *, *> } as List<Pair<CollectionProperty<Any?, Any?, Any?>, Node>>)

    }

    fun findMatchingProperties(type: Type<*, *>, node: Node): Set<Pair<BaseProperty<*, *, *>, Node>> {
        val matches = hashSetOf<Pair<BaseProperty<*, *, *>, Node>>()

        for (property in type.properties) {
            if (node == Node.all || node == Node.allDescendants) {
                matches.add(property to node)
            } else {
                val match = node[property.property.name]
                if (match != null) {
                    matches.add(property to match)
                }
            }
        }

        return matches
    }

    fun findMatchingType(obj: Any): Type<*, *> {
        val type = typeCache.getOrPut(obj.javaClass) { types.firstOrNull { it.supports(obj) } ?: noType }
        require(type != noType, "Unknown type: ${obj.javaClass.getName()}")
        return type
    }
}