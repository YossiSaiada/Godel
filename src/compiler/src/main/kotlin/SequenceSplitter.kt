package com.godel.compiler


object SequenceSplitter {
    /**
     * The returned sub-sequences are generated by scanning the original sourceSequence,
     * and for each item yielding it as a part of the current sub-sourceSequence,
     * or as a new sub-sourceSequence with a single item (if it satisfies the predicate).
     *
     * @param sourceSequence the sourceSequence to split
     * @param predicate the predicate to split by it
     * @return Sequence of sub-sourceSequence generated by splitting the original sourceSequence around items that satisfy the predicate
     */
    fun <T : Any> splitAroundDelimiters(
        sourceSequence: Sequence<T>,
        predicate: (T) -> Boolean
    ): Sequence<Sequence<T>> {
        val iterator = sourceSequence.iterator()
        return sequence {
            for (item in iterator) {
                if (predicate(item)) {
                    yield(sequenceOf(item))
                } else {
                    var capturedDelimiter: T? = null
                    yield(sequence {
                        yield(item)
                        for (innerItem in iterator) {
                            if (predicate(innerItem)) {
                                capturedDelimiter = innerItem
                                break
                            } else yield(innerItem)
                        }
                    })
                    // We can iterate over the sourceSequence only once.
                    // So, if we captured an item that satisfy the predicate in the inner loop,
                    // we can't "go back" one step and retrieve it again from the iterator,
                    // we have to store in in some local variable.
                    capturedDelimiter?.let { yield(sequenceOf(it)) }
                }
            }
        }
    }

    /**
     * The returned sub-sequences are generated by scanning the original sequence,
     * and for each item yielding it as a part of the current sub-sequence.
     * If the current item satisfies the predicate, it would be yielded as a start of a new sub-sequence.
     *
     * @param sourceSequence the sequence to split
     * @param predicate the predicate to split by it
     * @return Sequence of sub-sourceSequence generated by splitting the original sourceSequence before each item that satisfies the predicate
     */
    fun <T : Any> splitBeforeDelimiters(
        sourceSequence: Sequence<T>,
        predicate: (T) -> Boolean
    ): Sequence<Sequence<T>> {
        val iterator = sourceSequence.iterator()
        return sequence {
            var isFirstItem = true
            var capturedDelimiter: T? = null
            while (iterator.hasNext()) {
                yield(sequence {
                    capturedDelimiter?.let { yield(it) }
                    capturedDelimiter = null

                    for (innerItem in iterator) {
                        if (isFirstItem) {
                            // We don't want to yield an empty sequence.
                            // It can be happen only in one situation: the first item satisfies the predicate.
                            // So, whether the first item satisfies the predicate on not,
                            // we yield it and don't break the sub-sequence.
                            yield(innerItem)
                            isFirstItem = false
                        } else if (predicate(innerItem)) {
                            capturedDelimiter = innerItem
                            break
                        } else yield(innerItem)
                    }
                })
            }
            // The case which the last item satisfies the predicate, won't be handled in the loop,
            // because in the next iteration, the iterator won't have any next items.
            capturedDelimiter?.let { yield(sequenceOf(it)) }
        }
    }

}