package com.jsonviewer.models

import com.google.gson.JsonElement

enum class JsonNodeType { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

data class JsonTreeNodeData(
    val key: String,
    val value: String?,
    val type: JsonNodeType,
    val childCount: Int = 0,
    val element: JsonElement? = null,
)
