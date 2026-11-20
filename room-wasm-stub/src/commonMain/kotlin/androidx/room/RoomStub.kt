package androidx.room

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val inheritSuperIndices: Boolean = false,
    val primaryKeys: Array<String> = [],
    val foreignKeys: Array<ForeignKey> = [],
    val ignoredColumns: Array<String> = [],
)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class PrimaryKey(
    val autoGenerate: Boolean = false,
)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class ColumnInfo(
    val name: String = "",
    val defaultValue: String = "",
    val index: Boolean = false,
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Index(
    vararg val value: String,
    val orders: Array<Order> = [],
    val name: String = "",
    val unique: Boolean = false,
) {
    enum class Order {
        ASC,
        DESC,
    }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ForeignKey(
    val entity: KClass<*>,
    val parentColumns: Array<String>,
    val childColumns: Array<String>,
    val onDelete: Int = NO_ACTION,
    val onUpdate: Int = NO_ACTION,
    val deferred: Boolean = false,
) {
    companion object {
        const val NO_ACTION = 1
        const val RESTRICT = 2
        const val SET_NULL = 3
        const val SET_DEFAULT = 4
        const val CASCADE = 5
    }
}

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class Embedded(
    val prefix: String = "",
)

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class Relation(
    val entity: KClass<*> = Any::class,
    val parentColumn: String,
    val entityColumn: String,
    val associateBy: KClass<*> = Any::class,
    val projection: Array<String> = [],
)

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class TypeConverter

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ProvidedTypeConverter
