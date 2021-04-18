@file:Suppress("unused")

package edu.illinois.cs.cs125.jsp.server.moshi

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import edu.illinois.cs.cs125.jeed.core.moshi.InstantAdapter
import java.time.Instant

val Adapters = setOf(
    InstantAdapter()
)

class InstantAdapter {
    @FromJson
    fun instantFromJson(timestamp: String): Instant {
        return Instant.parse(timestamp)
    }

    @ToJson
    fun instantToJson(instant: Instant): String {
        return instant.toString()
    }
}
