package com.phylaris.webdav.client.internal

import com.phylaris.webdav.client.DavProtocolException
import com.phylaris.webdav.client.Depth
import com.phylaris.webdav.client.model.LockInfo
import com.phylaris.webdav.client.model.LockScope
import com.phylaris.webdav.client.model.LockType
import com.phylaris.webdav.client.model.PropValue
import com.phylaris.webdav.client.model.PropertyName
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.isElement
import nl.adaptivity.xmlutil.readSimpleElement
import nl.adaptivity.xmlutil.skipElement
import nl.adaptivity.xmlutil.skipPreamble
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * Parses WebDAV XML documents using an event-based reader. The parser is deliberately
 * lenient: unknown elements are skipped, missing namespaces are tolerated, and a single
 * malformed property never fails the whole listing.
 */
internal object MultiStatusParser {

    private const val DAV_NS = "DAV:"

    /** Parses a `DAV: multistatus` document (PROPFIND/PROPPATCH responses). */
    fun parseMultiStatus(xml: String): MultiStatus = parse(xml) { parseMultiStatusContent() }

    /**
     * Parses the body of a successful LOCK response (a `DAV: prop` containing
     * `lockdiscovery`). Returns null if no active lock could be found.
     */
    fun parseLockResponse(xml: String): LockInfo? = parse(xml) {
        var result: LockInfo? = null
        skipPreamble()
        if (eventType == EventType.START_ELEMENT) {
            while (next() != EventType.END_ELEMENT) {
                when {
                    eventType == EventType.START_ELEMENT && localName == "lockdiscovery" -> {
                        // <lockdiscovery><activelock>...</activelock></lockdiscovery>
                        while (next() != EventType.END_ELEMENT) {
                            when {
                                eventType == EventType.START_ELEMENT && localName == "activelock" -> {
                                    result = parseActiveLock() ?: result
                                }
                                eventType == EventType.START_ELEMENT -> skipElement()
                            }
                        }
                    }
                    eventType == EventType.START_ELEMENT -> skipElement()
                }
            }
        }
        result
    }

    private inline fun <T> parse(xml: String, block: XmlReader.() -> T): T {
        val reader = xmlStreaming.newReader(xml)
        return try {
            block(reader)
        } catch (e: DavProtocolException) {
            throw e
        } catch (e: Exception) {
            throw DavProtocolException("Failed to parse WebDAV XML response", e)
        } finally {
            reader.close()
        }
    }

    // --- multi-status ---

    private fun XmlReader.parseMultiStatusContent(): MultiStatus {
        skipPreamble()
        if (eventType != EventType.START_ELEMENT) {
            throw DavProtocolException("Expected root element, found $eventType")
        }
        val responses = mutableListOf<MultiStatusResponse>()
        while (next() != EventType.END_ELEMENT) {
            when {
                eventType == EventType.START_ELEMENT && localName == "response" -> responses.add(parseResponse())
                eventType == EventType.START_ELEMENT -> skipElement()
                else -> Unit // text, comments, ...
            }
        }
        return MultiStatus(responses)
    }

    private fun XmlReader.parseResponse(): MultiStatusResponse {
        var href: String? = null
        val propStats = mutableListOf<PropStat>()
        while (next() != EventType.END_ELEMENT) {
            when {
                eventType == EventType.START_ELEMENT && localName == "href" ->
                    href = readElementText()
                eventType == EventType.START_ELEMENT && localName == "propstat" ->
                    propStats.add(parsePropStat())
                eventType == EventType.START_ELEMENT -> skipElement()
                else -> Unit
            }
        }
        return MultiStatusResponse(href ?: "", propStats)
    }

    private fun XmlReader.parsePropStat(): PropStat {
        val props = mutableMapOf<PropertyName, PropValue>()
        var status: String? = null
        while (next() != EventType.END_ELEMENT) {
            when {
                eventType == EventType.START_ELEMENT && localName == "prop" -> parseProp(props)
                eventType == EventType.START_ELEMENT && localName == "status" -> status = readElementText()
                eventType == EventType.START_ELEMENT -> skipElement()
                else -> Unit
            }
        }
        return PropStat(props, status ?: "")
    }

    private fun XmlReader.parseProp(props: MutableMap<PropertyName, PropValue>) {
        while (next() != EventType.END_ELEMENT) {
            when (eventType) {
                EventType.START_ELEMENT -> {
                    val name = PropertyName(namespaceURI.ifEmpty { DAV_NS }, localName)
                    props[name] = parseNodeValue()
                }
                else -> Unit
            }
        }
    }

    /**
     * Parses the value of a property starting at its start element: a plain text value
     * or a nested element tree.
     *
     * Elements are always represented as [PropValue.Node] so their structure is
     * preserved (e.g. `<href>` inside `<locktoken>`); [PropValue.Text] appears as
     * child text content. Read text via `Node.text`, which concatenates child text.
     */
    private fun XmlReader.parseNodeValue(): PropValue {
        val name = PropertyName(namespaceURI.ifEmpty { DAV_NS }, localName)
        val attributes = mutableListOf<Pair<String, String>>()
        val children = mutableListOf<PropValue>()
        for (i in 0 until attributeCount) {
            val attrName = getAttributeLocalName(i).ifEmpty { getAttributePrefix(i) }
            if (attrName.isNotEmpty()) {
                attributes.add(attrName to getAttributeValue(i))
            }
        }
        while (next() != EventType.END_ELEMENT) {
            when (eventType) {
                EventType.START_ELEMENT -> children.add(parseNodeValue())
                EventType.TEXT, EventType.CDSECT -> children.add(PropValue.Text(text))
                EventType.ENTITY_REF -> children.add(PropValue.Text(resolveEntity(localName)))
                else -> Unit
            }
        }
        return PropValue.Node(name, attributes, children)
    }

    // --- lock discovery ---

    private fun XmlReader.parseActiveLock(): LockInfo? {
        var lockToken: String? = null
        var timeoutSeconds: Long? = null
        var owner: String? = null
        var lockRootHref: String? = null
        var scope = LockScope.EXCLUSIVE
        var lockType = LockType.WRITE
        var depth: Depth? = null
        while (next() != EventType.END_ELEMENT) {
            when {
                eventType == EventType.START_ELEMENT && localName == "locktoken" -> {
                    // <locktoken><href>opaquelocktoken:...</href></locktoken>
                    var href: String? = null
                    while (next() != EventType.END_ELEMENT) {
                        if (eventType == EventType.START_ELEMENT && localName == "href") {
                            href = readElementText()
                        } else if (eventType == EventType.START_ELEMENT) {
                            skipElement()
                        }
                    }
                    lockToken = href
                }
                eventType == EventType.START_ELEMENT && localName == "timeout" -> {
                    timeoutSeconds = readElementText()
                        .removePrefix("Second-")
                        .toLongOrNull()
                }
                eventType == EventType.START_ELEMENT && localName == "owner" -> {
                    // owner 可以是文本或任意元素
                    val value = parseNodeValue()
                    owner = when (value) {
                        is PropValue.Text -> value.value
                        is PropValue.Node -> value.text
                    }
                }
                eventType == EventType.START_ELEMENT && localName == "lockroot" -> {
                    while (next() != EventType.END_ELEMENT) {
                        if (eventType == EventType.START_ELEMENT && localName == "href") {
                            lockRootHref = readElementText()
                        } else if (eventType == EventType.START_ELEMENT) {
                            skipElement()
                        }
                    }
                }
                eventType == EventType.START_ELEMENT && localName == "lockscope" -> {
                    // Child elements may be self-closing (<exclusive/>); consume each
                    // child with skipElement so its end tag never terminates this loop.
                    while (true) {
                        when (next()) {
                            EventType.START_ELEMENT -> {
                                if (localName == "shared") scope = LockScope.SHARED
                                skipElement()
                            }
                            EventType.END_ELEMENT -> break
                            else -> Unit
                        }
                    }
                }
                eventType == EventType.START_ELEMENT && localName == "locktype" -> {
                    while (true) {
                        when (next()) {
                            EventType.START_ELEMENT -> {
                                if (localName == "write") lockType = LockType.WRITE
                                skipElement()
                            }
                            EventType.END_ELEMENT -> break
                            else -> Unit
                        }
                    }
                }
                eventType == EventType.START_ELEMENT && localName == "depth" -> {
                    depth = when (readElementText()) {
                        Depth.ZERO.headerValue -> Depth.ZERO
                        Depth.ONE.headerValue -> Depth.ONE
                        Depth.INFINITY.headerValue -> Depth.INFINITY
                        else -> null
                    }
                }
                eventType == EventType.START_ELEMENT -> skipElement()
                else -> Unit
            }
        }
        return if (lockToken != null) {
            LockInfo(
                token = lockToken,
                scope = scope,
                type = lockType,
                depth = depth,
                timeoutSeconds = timeoutSeconds,
                owner = owner,
                lockRootHref = lockRootHref,
            )
        } else {
            null
        }
    }

    /**
     * Reads the text content of the current element, resolving predefined and numeric
     * character entities. Some parsers (e.g. JVM StAX) do not expand entities in text
     * content and report them as ENTITY_REF events with empty text; this keeps the
     * behavior consistent across platforms.
     */
    private fun XmlReader.readElementText(): String {
        val sb = StringBuilder()
        while (next() != EventType.END_ELEMENT) {
            when (eventType) {
                EventType.TEXT, EventType.CDSECT -> sb.append(text)
                EventType.ENTITY_REF -> sb.append(resolveEntity(localName))
                EventType.COMMENT, EventType.PROCESSING_INSTRUCTION -> Unit
                else -> throw DavProtocolException("Unexpected event $eventType in text element")
            }
        }
        return sb.toString()
    }

    private fun resolveEntity(name: String): String = when {
        name == "amp" -> "&"
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "quot" -> "\""
        name == "apos" -> "'"
        name.startsWith("#x") || name.startsWith("#X") ->
            name.substring(2).toIntOrNull(16)?.toChar()?.toString() ?: "&$name;"
        name.startsWith("#") ->
            name.substring(1).toIntOrNull()?.toChar()?.toString() ?: "&$name;"
        else -> "&$name;"
    }
}
