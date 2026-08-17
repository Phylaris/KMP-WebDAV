package com.phylaris.webdav.client.model

/**
 * The value of a WebDAV property. Property values can be plain text or arbitrary XML
 * subtrees (e.g. `resourcetype`, `lockdiscovery`), so a small XML tree model is used.
 */
sealed interface PropValue {

    /** The text content of this value, or null if it has no text. */
    val text: String?

    /** A plain text property value. */
    data class Text(val value: String) : PropValue {
        override val text: String get() = value
    }

    /** An element value with attributes and child nodes. */
    data class Node(
        val name: PropertyName,
        val attributes: List<Pair<String, String>> = emptyList(),
        val children: List<PropValue> = emptyList(),
    ) : PropValue {
        override val text: String?
            get() = children.filterIsInstance<Text>().joinToString("") { it.value }
                .ifBlank { null }

        /** First direct child element with the given [name], or null. */
        fun child(name: PropertyName): Node? =
            children.filterIsInstance<Node>().firstOrNull { it.name == name }

        /** All direct child elements with the given [name]. */
        fun children(name: PropertyName): List<Node> =
            children.filterIsInstance<Node>().filter { it.name == name }
    }
}
