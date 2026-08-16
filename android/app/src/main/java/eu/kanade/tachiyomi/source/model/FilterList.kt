package eu.kanade.tachiyomi.source.model

/** Port of extensions-lib FilterList (Apache-2.0). */
data class FilterList(val list: List<Filter<*>>) : List<Filter<*>> by list {

    constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = list.hashCode()
}
