package kplyr

import java.util.*
import kotlin.comparisons.nullsLast
import kotlin.comparisons.then


internal class SimpleDataFrame(val cols: List<DataCol>) : DataFrame {

    override val rows = object : Iterable<Map<String, Any?>> {
        override fun iterator() = object : Iterator<Map<String, Any?>> {
            var curRow = 0

            override fun hasNext(): Boolean = curRow < nrow

            override fun next(): Map<String, Any?> = row(curRow++)
        }
    }


    override fun select(which: List<Boolean>): DataFrame {
        require(which.size == ncol) { "selector array has different dimension than data-frame" }

        return SimpleDataFrame(cols.filterIndexed { index, dataCol -> which[index] })
    }

    // Utility methods

    override fun row(rowIndex: Int): Map<String, Any?> =
            cols.map {
                it.name to when (it) {
                    is DoubleCol -> it.values[rowIndex]
                    is IntCol -> it.values[rowIndex]
                    is BooleanCol -> it.values[rowIndex]
                    is StringCol -> it.values[rowIndex]
                    is AnyCol<*> -> it.values[rowIndex]
                    else -> throw UnsupportedOperationException()
                }
            }.toMap()

    override val ncol = cols.size

    override val nrow by lazy {
        val firstCol = cols.firstOrNull()
        when (firstCol) {
            null -> 0
            is DoubleCol -> firstCol.values.size
            is IntCol -> firstCol.values.size
            is BooleanCol -> firstCol.values.size
            is StringCol -> firstCol.values.size
            is AnyCol<*> -> firstCol.values.size
            else -> throw UnsupportedOperationException()
        }
    }


    /** This method is private to enforce use of mutate which is the primary way to add columns in kplyr. */
    private fun addColumn(newCol: DataCol): SimpleDataFrame {
        require(newCol.length == nrow) { "Column lengths of dataframe ($nrow) and new column (${newCol.length}) differ" }
        require(newCol.name !in names) { "Column '${newCol.name}' already exists in dataframe" }
        require(newCol.name != TMP_COLUMN) { "Internal temporary column name should not be expose to user" }

        val mutatedCols = cols.toMutableList().apply { add(newCol) }
        return SimpleDataFrame(mutatedCols.toList())
    }

    /** Returns the ordered list of column names of this data-frame. */
    override val names: List<String> = cols.map { it.name }


    override operator fun get(name: String): DataCol = try {
        cols.first { it.name == name }
    } catch(e: NoSuchElementException) {
        throw NoSuchElementException("Could not find column '${name}' in dataframe")
    }

    // Core Verbs

    override fun filter(predicate: DataFrame.(DataFrame) -> BooleanArray): DataFrame {
        val indexFilter = predicate(this)

        require(indexFilter.size == nrow) { "filter index has incompatible length" }

        return cols.map {
            // subset a colum by the predicate array
            when (it) {
                is DoubleCol -> DoubleCol(it.name, it.values.filterIndexed { index, value -> indexFilter[index] })
                is IntCol -> IntCol(it.name, it.values.filterIndexed { index, value -> indexFilter[index] })
                is StringCol -> StringCol(it.name, it.values.filterIndexed { index, value -> indexFilter[index] }.toList())
                else -> throw UnsupportedOperationException()
            }
        }.let { SimpleDataFrame(it) }
    }


    // also provide vararg constructor for convenience
    constructor(vararg cols: DataCol) : this(cols.asList())

    override fun summarize(vararg sumRules: TableFormula): DataFrame {
//        require(nrow > 0) { "Can not summarize empty data-frame" } // todocan dplyr?
        /**
        require(dplyr)
        data_frame() %>% summarize(test=1)
         */

        val sumCols = mutableListOf<DataCol>()
        for ((key, sumRule) in sumRules) {
            val sumValue = sumRule(this)
            when (sumValue) {
                is Int -> IntCol(key, listOf(sumValue))
                is Double -> DoubleCol(key, listOf(sumValue))
                is Boolean -> BooleanCol(key, listOf(sumValue))
                is String -> StringCol(key, Array(1, { sumValue.toString() }).toList())
            // todo does null-handling makes sense at all? Might be not-null in other groups for grouped operations // todo add unit-test
                null -> AnyCol(key, listOf(null))
                is DataCol -> throw UnsupportedOperationException("summarize() rules must evaluate to scalar expression but did as: ${sumValue}")
                else -> throw UnsupportedOperationException()
            }.let { sumCols.add(it) }
        }

        return SimpleDataFrame(sumCols)
    }


//    https://kotlinlang.org/docs/reference/multi-declarations.html
//    operator fun component1() = 1

    // todo enforce better typed API
    override fun mutate(tf: TableFormula): DataFrame {

        val mutation = tf.formula(this, this)
        val newCol = anyAsColumn(mutation, tf.resultName, nrow)


        require(newCol.values().size == nrow) { "new column has inconsistent length" }
        require(newCol.name != TMP_COLUMN) { "missing name in new columns" }

        return addColumn(newCol)
    }


    override fun arrange(vararg by: String): DataFrame {
        if (by.isEmpty()) {
            System.err.println("Calling arrange without arguments is not sensible")
            return this
        }

        // utility method to convert columns to comparators
        fun asComparator(by: String): Comparator<Int> {
            val dataCol = this[by]
//            return naturalOrder<*>()
            return when (dataCol) {
            // todo use nullsLast
                is DoubleCol -> Comparator { left, right -> nullsLast<Double>().compare(dataCol.values[left], dataCol.values[right]) }
                is IntCol -> Comparator { left, right -> nullsLast<Int>().compare(dataCol.values[left], dataCol.values[right]) }
                is BooleanCol -> Comparator { left, right -> nullsLast<Boolean>().compare(dataCol.values[left], dataCol.values[right]) }
                is StringCol -> Comparator { left, right -> nullsLast<String>().compare(dataCol.values[left], dataCol.values[right]) }
                else -> throw UnsupportedOperationException()
            }
        }

        // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.comparisons/java.util.-comparator/then-by-descending.html
        // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.comparisons/index.html
        val compChain = by.map { asComparator(it) }.let { it.drop(1).fold(it.first(), { a, b -> a.then(b) }) }


        // see http://stackoverflow.com/questions/11997326/how-to-find-the-permutation-of-a-sort-in-java
        val permutation = (0..(nrow - 1)).sortedWith(compChain).toIntArray()

        // apply permutation to all columns
        return cols.map {
            when (it) {
                is DoubleCol -> DoubleCol(it.name, Array(nrow, { index -> it.values[permutation[index]] }).toList())
                is IntCol -> IntCol(it.name, Array(nrow, { index -> it.values[permutation[index]] }).toList())
                is BooleanCol -> BooleanCol(it.name, Array(nrow, { index -> it.values[permutation[index]] }).toList())
                is StringCol -> StringCol(it.name, Array(nrow, { index -> it.values[permutation[index]] }).toList())
                else -> throw UnsupportedOperationException()
            }
        }.let { SimpleDataFrame(it) }

    }


    // use proper generics here
    override fun groupBy(vararg by: String): DataFrame {
        if (by.isEmpty()) System.err.print("Grouping with empty attribute list is unlikely to have meaningful semantics")

        //take all grouping columns
        val groupCols = cols.filter { by.contains(it.name) }
        require(groupCols.size == by.size) { "Could not find all grouping columns" }

        val NA_GROUP_HASH = Int.MAX_VALUE - 123

        // todo use more efficient scheme to avoid hashing of ints
        // extract the group value-tuple for each row and calculate row-hashes
        val rowHashes = rows.map { row ->
            groupCols.map {
                row[it.name]?.hashCode() ?: NA_GROUP_HASH
            }.hashCode()
        }

        // use filter index for each selector-index

        // and  split up original dataframe columns by selector index
        val groupIndices = rowHashes.
                mapIndexed { index, group -> Pair(group, index) }.
                groupBy { it.first }.
                map {
                    val groupRowIndices = it.value.map { it.second }.toIntArray()
                    GroupIndex(it.key, groupRowIndices)
                }


        fun extractGroup(col: DataCol, groupIndex: GroupIndex): DataCol = when (col) {
        // too inefficient since it requires full loop over all values
//            is DoubleCol -> DoubleCol(col.name, col.values.filterIndexed { index, d -> groupIndex.rowIndices.contains(index) })
        // reverse order here and create new array
            is DoubleCol -> DoubleCol(col.name, Array(groupIndex.rowIndices.size, { col.values[groupIndex.rowIndices[it]] }).toList())
            is IntCol -> IntCol(col.name, Array(groupIndex.rowIndices.size, { col.values[groupIndex.rowIndices[it]] }).toList())
            is BooleanCol -> BooleanCol(col.name, Array(groupIndex.rowIndices.size, { col.values[groupIndex.rowIndices[it]] }).toList())
            is StringCol -> StringCol(col.name, Array(groupIndex.rowIndices.size, { col.values[groupIndex.rowIndices[it]] }).toList())
            else -> throw UnsupportedOperationException()
        }

        fun extractGroupByIndex(groupIndex: GroupIndex, df: SimpleDataFrame): SimpleDataFrame {
            val grpSubCols = df.cols.map { extractGroup(it, groupIndex) }

            // todo change order so that group columns come first
            return SimpleDataFrame(grpSubCols)
        }


        return GroupedDataFrame(by.toList(), groupIndices.map { DataGroup(it.groupHash, extractGroupByIndex(it, this)) })
    }


    override fun ungroup(): DataFrame {
        throw UnsupportedOperationException()
    }

    // todo mimic dplyr.print better here (num observations, hide too many columns, etc.)
    override fun toString(): String = head(5).asString()
}


internal fun anyAsColumn(mutation: Any?, name: String, nrow: Int): DataCol {
    // expand scalar values to arrays/lists
    val arrifiedMutation: Any? = when (mutation) {
        is Int -> IntArray(nrow, { mutation })
        is Double -> DoubleArray(nrow, { mutation }).toList()
        is Boolean -> BooleanArray(nrow, { mutation })
        is Float -> FloatArray(nrow, { mutation })
        is String -> Array<String>(nrow) { mutation }.asList()
    // add/test NA support here
        else -> mutation
    }

    val newCol = when (arrifiedMutation) {
        is DataCol -> when (arrifiedMutation) {
            is DoubleCol -> DoubleCol(name, arrifiedMutation.values)
            is IntCol -> IntCol(name, arrifiedMutation.values)
            is StringCol -> StringCol(name, arrifiedMutation.values)
            is BooleanCol -> BooleanCol(name, arrifiedMutation.values)
            else -> throw UnsupportedOperationException()
        }

    // todo still needed
        is DoubleArray -> DoubleCol(name, arrifiedMutation.toList())
        is IntArray -> IntCol(name, arrifiedMutation.toList())
        is BooleanArray -> BooleanCol(name, arrifiedMutation.toList())

    // also handle lists here
        emptyList<Int>() -> AnyCol<Any>(name, emptyList())
        is List<*> -> handleListErasure(name, arrifiedMutation)

        else -> throw UnsupportedOperationException()
    }
    return newCol
}

@Suppress("UNCHECKED_CAST")
internal fun handleListErasure(name: String, mutation: List<*>): DataCol = when (mutation.first()) {
    is Double -> DoubleCol(name, mutation as List<Double>)
    is Int -> IntCol(name, mutation as List<Int>)
    is String -> StringCol(name, mutation as List<String>)
    is Boolean -> BooleanCol(name, mutation as List<Boolean>)
    else -> throw UnsupportedOperationException()
}