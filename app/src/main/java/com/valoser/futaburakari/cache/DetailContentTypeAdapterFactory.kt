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

/**
 * Gson factory for polymorphic (de)serialization of `DetailContent` sealed types.
 * Adds a discriminator field `contentType` for non-null values when writing, and expects the same field when reading;
 * unknown or missing discriminator values result in a `JsonParseException`, while JSON nulls pass through unchanged.
 */
class DetailContentTypeAdapterFactory : TypeAdapterFactory {
    /**
     * Returns a type adapter for `DetailContent` (and its subtypes). For other types, returns null.
     */
    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (!DetailContent::class.java.isAssignableFrom(type.rawType)) {
            return null
        }

        @Suppress("UNCHECKED_CAST")
        return DetailContentTypeAdapter(gson, this) as TypeAdapter<T> // Provide this factory to allow delegate lookup to skip it
    }

    /**
     * Adapter that injects/extracts the `contentType` discriminator and delegates
     * actual field (de)serialization to subtype adapters.
     */
    private class DetailContentTypeAdapter(
        private val gson: Gson,
        private val skipPastFactory: TypeAdapterFactory // Factory to skip when retrieving delegate adapters to avoid recursion
    ) : TypeAdapter<DetailContent>() {
        companion object {
            /** JSON discriminator field name added during serialization. */
            private const val TYPE_FIELD = "contentType"
            /** Discriminator value for `DetailContent.Image`. */
            private const val IMAGE_TYPE = "image"
            /** Discriminator value for `DetailContent.Text`. */
            private const val TEXT_TYPE = "text"
            /** Discriminator value for `DetailContent.Video`. */
            private const val VIDEO_TYPE = "video"
            /** Discriminator value for `DetailContent.ThreadEndTime`. */
            private const val THREAD_END_TIME_TYPE = "thread_end_time"
        }

        @Throws(IOException::class)
        override fun write(out: JsonWriter, value: DetailContent?) {
            if (value == null) {
                out.nullValue()
                return
            }

            // Obtain the delegate adapter for the runtime subtype, skipping this factory
            val actualType = TypeToken.get(value.javaClass) // Get actual runtime type
            val delegate = gson.getDelegateAdapter(skipPastFactory, actualType) as TypeAdapter<DetailContent>

            val jsonObject = delegate.toJsonTree(value).asJsonObject

            // Add the discriminator field so it can be deserialized later
            when (value) {
                is DetailContent.Image -> jsonObject.addProperty(TYPE_FIELD, IMAGE_TYPE)
                is DetailContent.Text -> jsonObject.addProperty(TYPE_FIELD, TEXT_TYPE)
                is DetailContent.Video -> jsonObject.addProperty(TYPE_FIELD, VIDEO_TYPE)
                is DetailContent.ThreadEndTime -> jsonObject.addProperty(TYPE_FIELD, THREAD_END_TIME_TYPE)
            }
            // Write the modified object using the generic JsonElement adapter
            val jsonElementAdapter = gson.getAdapter(JsonElement::class.java)
            jsonElementAdapter.write(out, jsonObject)
        }

        @Throws(IOException::class)
        override fun read(reader: JsonReader): DetailContent? {
            // Read the entire structure as a JsonElement first, then inspect the discriminator
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

            // Determine the concrete subtype from the discriminator value
            val specificType: Class<out DetailContent> = when (typeValue) {
                IMAGE_TYPE -> DetailContent.Image::class.java
                TEXT_TYPE -> DetailContent.Text::class.java
                VIDEO_TYPE -> DetailContent.Video::class.java
                THREAD_END_TIME_TYPE -> DetailContent.ThreadEndTime::class.java
                else -> throw JsonParseException("Unknown DetailContent type: $typeValue")
            }

            // Delegate to the subtype adapter (which ignores the discriminator field)
            val delegate = gson.getDelegateAdapter(skipPastFactory, TypeToken.get(specificType))
            // Use the delegate to deserialize the JsonObject (it may still contain TYPE_FIELD, which the delegate ignores)
            return delegate.fromJsonTree(jsonObject)
        }
    }
}
