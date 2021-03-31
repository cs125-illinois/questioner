@file:JvmName("Generators")
@file:Suppress("unused", "MagicNumber", "SpellCheckingInspection")

package edu.illinois.cs.cs125.questioner.lib

import java.util.Random

private fun Random.nextInt(min: Int, max: Int) = let {
    require(min < max)
    nextInt(max - min) + min
}

private const val CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz "

@JvmOverloads
fun randomAlphanumericString(random: Random, maxLength: Int, characters: String = CHARACTERS, minLength: Int = 0) =
    String(CharArray(random.nextInt(minLength, maxLength)) { characters[random.nextInt(characters.length)] })

@JvmOverloads
fun randomAlphanumericStringWithLength(random: Random, length: Int, characters: String = CHARACTERS) =
    String(CharArray(length) { characters[random.nextInt(characters.length)] })

private const val DNA_CHARACTERS = "ATCG"
fun randomDNAString(random: Random, maxLength: Int) =
    randomAlphanumericString(random, maxLength, DNA_CHARACTERS)

fun randomDNAStringWithLength(random: Random, length: Int) =
    randomAlphanumericStringWithLength(random, length, DNA_CHARACTERS)

private const val NONWHITESPACE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
fun randomAlphanumericStringNoSpaces(random: Random, maxLength: Int) =
    randomAlphanumericString(random, maxLength, NONWHITESPACE_CHARACTERS, 1)

fun randomAlphanumericStringNoSpacesWithLength(random: Random, length: Int) =
    randomAlphanumericStringWithLength(random, length, NONWHITESPACE_CHARACTERS)

fun randomIntArray(size: Int = 1024, min: Int = -512, max: Int = 512, random: Random) =
    IntArray(size) { random.nextInt(min, max) }

fun randomIntArray(maxSize: Int, random: Random) = randomIntArray(random.nextInt(maxSize), -16, 16, random)

fun randomIntIntArray(maxSize: Int, rectangular: Boolean, random: Random): Array<IntArray> {
    val secondSize = random.nextInt(maxSize)
    return Array(random.nextInt(maxSize)) {
        when (rectangular) {
            true -> IntArray(secondSize) { random.nextInt(-16, 16) }
            false -> IntArray(random.nextInt(maxSize)) { random.nextInt(-16, 16) }
        }
    }
}

fun randomIntIntArray(random: Random, dimensions: Array<IntArray>) = Array(dimensions.size) { i ->
    IntArray(dimensions[i].size) { random.nextInt(-16, 16) }
}
