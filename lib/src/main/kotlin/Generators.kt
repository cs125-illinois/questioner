@file:JvmName("Generators")

package edu.illinois.cs.cs125.questioner.lib

import kotlin.random.Random

@Suppress("MagicNumber")
@JvmOverloads
fun randomIntArray(size: Int = 1024, min: Int = -512, max: Int = 512, seed: Int = Random.nextInt()): IntArray =
    Random(seed).let { random ->
        IntArray(size).map {
            random.nextInt(min, max)
        }.toIntArray()
    }
