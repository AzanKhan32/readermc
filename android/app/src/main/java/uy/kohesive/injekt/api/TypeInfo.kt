/*
 * Embedded copy of kohesive/injekt (Apache-2.0), api/TypeInfo.kt.
 * Embedded because the JitPack artifact (com.github.inorichi.injekt) is no
 * longer resolvable. Package names and binary signatures must stay EXACTLY
 * as the original — extension APKs link against these classes at runtime.
 */
package uy.kohesive.injekt.api

import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

@Suppress("UNCHECKED_CAST")
fun <T : Any> Type.erasedType(): Class<T> {
    return when (this) {
        is Class<*> -> this as Class<T>
        is ParameterizedType -> this.rawType.erasedType()
        is GenericArrayType -> {
            val elementType = this.genericComponentType.erasedType<Any>()
            val testArray = java.lang.reflect.Array.newInstance(elementType, 0)
            testArray.javaClass as Class<T>
        }
        is TypeVariable<*> -> throw IllegalStateException("Not sure what to do here yet")
        is WildcardType -> this.upperBounds[0].erasedType()
        else -> throw IllegalStateException("Should not get here.")
    }
}

inline fun <reified T : Any> typeRef(): FullTypeReference<T> = object : FullTypeReference<T>() {}
inline fun <reified T : Any> fullType(): FullTypeReference<T> = object : FullTypeReference<T>() {}

interface TypeReference<T> {
    val type: Type
}

abstract class FullTypeReference<T> protected constructor() : TypeReference<T> {
    override val type: Type = javaClass.genericSuperclass.let { superClass ->
        if (superClass is Class<*>) {
            throw IllegalArgumentException("Internal error: TypeReference constructed without actual type information")
        }
        (superClass as ParameterizedType).actualTypeArguments[0]
    }
}
