package com.valoser.futaburakari.cache

import com.valoser.futaburakari.DetailContent
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException

class DetailContentTypeAdapterFactory : TypeAdapterFactory {
    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (!DetailContent::class.java.isAssignableFrom(type.rawType)) {
            return null
        }

        @Suppress("UNCHECKED_CAST")
        return DetailContentTypeAdapter(gson, this) as TypeAdapter<T> // Pass the factory itself
    }

    private class DetailContentTypeAdapter(
        private val gson: Gson,
        private val skipPastFactory: TypeAdapterFactory // Factory to skip
    ) : TypeAdapter<DetailContent>() {
        companion object {
            private const val TYPE_FIELD = "contentType"
            private const val IMAGE_TYPE = "image"
            private const val TEXT_TYPE = "text"
            private const val VIDEO_TYPE = "video"
            private const val THREAD_END_TIME_TYPE = "thread_end_time" // Added
        }

        @Throws(IOException::class)
        override fun write(out: JsonWriter, value: DetailContent?) {
            if (value == null) {
                out.nullValue()
                return
            }

            // Get the delegate adapter for the specific subtype, skipping this factory
            val actualType = TypeToken.get(value.javaClass) // Get actual runtime type
            val delegate = gson.getDelegateAdapter(skipPastFactory, actualType) as TypeAdapter<DetailContent>

            val jsonObject = delegate.toJsonTree(value).asJsonObject

            // Add the type identifier field
            when (value) {
                is DetailContent.Image -> jsonObject.addProperty(TYPE_FIELD, IMAGE_TYPE)
                is DetailContent.Text -> jsonObject.addProperty(TYPE_FIELD, TEXT_TYPE)
                is DetailContent.Video -> jsonObject.addProperty(TYPE_FIELD, VIDEO_TYPE)
                is DetailContent.ThreadEndTime -> jsonObject.addProperty(TYPE_FIELD, THREAD_END_TIME_TYPE) // Added
            }
            // Use the JsonElement adapter to write the modified JsonObject
            val jsonElementAdapter = gson.getAdapter(JsonElement::class.java)
            jsonElementAdapter.write(out, jsonObject)
        }

        @Throws(IOException::class)
        override fun read(reader: JsonReader): DetailContent? {
            // Read the entire JSON structure as a JsonElement first
            val jsonElementAdapter = gson.getAdapter(JsonElement::class.java)
            val jsonElement = jsonElementAdapter.read(reader) ?: return null

            if (!jsonElement.isJsonObject) {
                throw JsonParseException("DetailContent must be a JSON object.")
            }
            val jsonObject = jsonElement.asJsonObject

            val typeElement = jsonObject.get(TYPE_FIELD)
            if (typeElement == null || !typeElement.isJsonPrimitive || !(typeElement as JsonPrimitive).isString) {
                throw JsonParseException("DetailContent JSON must have a '$TYPE_FIELD' string property.")
            }
            val typeValue = typeElement.asString

            // Determine the specific subtype based on the typeValue
            val specificType: Class<out DetailContent> = when (typeValue) {
                IMAGE_TYPE -> DetailContent.Image::class.java
                TEXT_TYPE -> DetailContent.Text::class.java
                VIDEO_TYPE -> DetailContent.Video::class.java
                THREAD_END_TIME_TYPE -> DetailContent.ThreadEndTime::class.java // Added
                else -> throw JsonParseException("Unknown DetailContent type: $typeValue")
            }

            // Get the delegate adapter for that specific subtype, skipping this factory
            val delegate = gson.getDelegateAdapter(skipPastFactory, TypeToken.get(specificType))
            // Use the delegate to deserialize the JsonObject (which still contains the TYPE_FIELD, but delegate should ignore it)
            return delegate.fromJsonTree(jsonObject)
        }
    }
}
