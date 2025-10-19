package Structs

/**
 * Pair of strings that can be serialized and deserialized. Useful for pair data being passed
 * that doesn't justify a special data class for it.
 */
@kotlinx.serialization.Serializable
data class StringPair(val stringA : String, val stringB : String)
