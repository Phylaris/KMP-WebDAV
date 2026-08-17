package com.phylaris.webdav.client.internal

import com.phylaris.webdav.client.model.LockScope
import com.phylaris.webdav.client.model.PropertyName

/**
 * Builds the XML request bodies used by WebDAV methods (PROPFIND, PROPPATCH, LOCK).
 * Bodies are small, fixed-structure documents, so they are generated from string
 * templates with proper escaping.
 */
internal object XmlBody {

    private const val XML_DECLARATION = """<?xml version="1.0" encoding="utf-8"?>"""
    private const val DAV_PREFIX = "d"
    private const val DAV_NS = "DAV:"

    /** Escapes XML special characters in text content and attribute values. */
    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /** PROPFIND requesting all properties (`<allprop/>`). */
    fun propfindAllProp(): String =
        """$XML_DECLARATION<$DAV_PREFIX:propfind xmlns:$DAV_PREFIX="$DAV_NS"><$DAV_PREFIX:allprop/></$DAV_PREFIX:propfind>"""

    /** PROPFIND requesting only property names (`<propname/>`). */
    fun propfindPropName(): String =
        """$XML_DECLARATION<$DAV_PREFIX:propfind xmlns:$DAV_PREFIX="$DAV_NS"><$DAV_PREFIX:propname/></$DAV_PREFIX:propfind>"""

    /** PROPFIND requesting a specific set of properties (`<prop>...</prop>`). */
    fun propfindProps(names: List<PropertyName>): String = buildString {
        append(XML_DECLARATION)
        append("<$DAV_PREFIX:propfind xmlns:$DAV_PREFIX=\"$DAV_NS\"><$DAV_PREFIX:prop>")
        for (name in names) {
            if (name.namespace == PropertyName.DAV_NAMESPACE) {
                append("<$DAV_PREFIX:${name.name}/>")
            } else {
                append("<x:${name.name} xmlns:x=\"${escape(name.namespace)}\"/>")
            }
        }
        append("</$DAV_PREFIX:prop></$DAV_PREFIX:propfind>")
    }

    /** PROPPATCH setting and/or removing properties. */
    fun proppatch(
        set: Map<PropertyName, String>,
        remove: Set<PropertyName>,
    ): String = buildString {
        append(XML_DECLARATION)
        append("<$DAV_PREFIX:propertyupdate xmlns:$DAV_PREFIX=\"$DAV_NS\">")
        if (set.isNotEmpty()) {
            append("<$DAV_PREFIX:set><$DAV_PREFIX:prop>")
            for ((name, value) in set) {
                propertyElement(name, escape(value))
            }
            append("</$DAV_PREFIX:prop></$DAV_PREFIX:set>")
        }
        if (remove.isNotEmpty()) {
            append("<$DAV_PREFIX:remove><$DAV_PREFIX:prop>")
            for (name in remove) {
                propertyElement(name)
            }
            append("</$DAV_PREFIX:prop></$DAV_PREFIX:remove>")
        }
        append("</$DAV_PREFIX:propertyupdate>")
    }

    /** LOCK request body with the given lock scope (exclusive by default). */
    fun lock(owner: String?, scope: LockScope = LockScope.EXCLUSIVE): String = buildString {
        append(XML_DECLARATION)
        append(
            "<$DAV_PREFIX:lockinfo xmlns:$DAV_PREFIX=\"$DAV_NS\">" +
                "<$DAV_PREFIX:lockscope>" +
                (if (scope == LockScope.SHARED) "<$DAV_PREFIX:shared/>" else "<$DAV_PREFIX:exclusive/>") +
                "</$DAV_PREFIX:lockscope>" +
                "<$DAV_PREFIX:locktype><$DAV_PREFIX:write/></$DAV_PREFIX:locktype>"
        )
        if (owner != null) {
            append("<$DAV_PREFIX:owner>${escape(owner)}</$DAV_PREFIX:owner>")
        }
        append("</$DAV_PREFIX:lockinfo>")
    }

    private fun StringBuilder.propertyElement(name: PropertyName, value: String? = null) {
        val prefix = if (name.namespace == PropertyName.DAV_NAMESPACE) DAV_PREFIX else "x"
        if (name.namespace != PropertyName.DAV_NAMESPACE) {
            append(" xmlns:x=\"${escape(name.namespace)}\"")
        }
        if (value == null) {
            append("<$prefix:${name.name}/>")
        } else {
            append("<$prefix:${name.name}>$value</$prefix:${name.name}>")
        }
    }
}
