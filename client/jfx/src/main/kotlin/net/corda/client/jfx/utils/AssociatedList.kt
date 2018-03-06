/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.client.jfx.utils

import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import javafx.collections.ObservableMap
import java.util.*

/**
 * [AssociatedList] creates an [ObservableMap] from an [ObservableList] by associating each list element with a unique key.
 * It is *not* allowed to have several elements map to the same value!
 *
 * @param sourceList The source list.
 * @param toKey Function returning the key.
 * @param assemble The function to assemble the final map element from the list element and the associated key.
 */
class AssociatedList<K, out A, B>(
        val sourceList: ObservableList<out A>,
        toKey: (A) -> K,
        assemble: (K, A) -> B
) : ReadOnlyBackedObservableMapBase<K, B, Unit>() {
    init {
        sourceList.forEach {
            val key = toKey(it)
            backingMap.set(key, Pair(assemble(key, it), Unit))
        }
        sourceList.addListener { change: ListChangeListener.Change<out A> ->
            while (change.next()) {
                if (change.wasPermutated()) {
                } else if (change.wasUpdated()) {
                } else {
                    val removedSourceMap = change.removed.associateBy(toKey)
                    val addedSourceMap = change.addedSubList.associateBy(toKey)
                    val removedMap = HashMap<K, B>()
                    val addedMap = HashMap<K, B>()
                    removedSourceMap.forEach {
                        val removed = backingMap.remove(it.key)?.first
                        removed ?: throw IllegalStateException("Removed list does not associate")
                        removedMap.put(it.key, removed)
                    }
                    addedSourceMap.forEach {
                        val oldValue = backingMap.get(it.key)
                        val newValue = if (oldValue == null) {
                            assemble(it.key, it.value)
                        } else {
                            throw IllegalStateException("Several elements associated with same key")
                        }
                        backingMap.put(it.key, Pair(newValue, Unit))
                        addedMap.put(it.key, newValue)
                    }
                    val keys = removedMap.keys + addedMap.keys
                    keys.forEach { key ->
                        fireChange(createMapChange(key, removedMap.get(key), addedMap.get(key)))
                    }
                }
            }
        }
    }
}
