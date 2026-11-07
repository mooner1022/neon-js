package dev.mooner.neonpython

import dev.mooner.neonpython.ExpatTok.XML_TOK_BOM
import dev.mooner.neonpython.ExpatTok.XML_TOK_CDATA_SECT_CLOSE
import dev.mooner.neonpython.ExpatTok.XML_TOK_CDATA_SECT_OPEN
import dev.mooner.neonpython.ExpatTok.XML_TOK_CHAR_REF
import dev.mooner.neonpython.ExpatTok.XML_TOK_COMMENT
import dev.mooner.neonpython.ExpatTok.XML_TOK_DATA_CHARS
import dev.mooner.neonpython.ExpatTok.XML_TOK_DATA_NEWLINE
import dev.mooner.neonpython.ExpatTok.XML_TOK_EMPTY_ELEMENT_NO_ATTS
import dev.mooner.neonpython.ExpatTok.XML_TOK_EMPTY_ELEMENT_WITH_ATTS
import dev.mooner.neonpython.ExpatTok.XML_TOK_END_TAG
import dev.mooner.neonpython.ExpatTok.XML_TOK_ENTITY_REF
import dev.mooner.neonpython.ExpatTok.XML_TOK_IGNORE_SECT
import dev.mooner.neonpython.ExpatTok.XML_TOK_INVALID
import dev.mooner.neonpython.ExpatTok.XML_TOK_NONE
import dev.mooner.neonpython.ExpatTok.XML_TOK_PARTIAL
import dev.mooner.neonpython.ExpatTok.XML_TOK_PARTIAL_CHAR
import dev.mooner.neonpython.ExpatTok.XML_TOK_PI
import dev.mooner.neonpython.ExpatTok.XML_TOK_PROLOG_S
import dev.mooner.neonpython.ExpatTok.XML_TOK_START_TAG_NO_ATTS
import dev.mooner.neonpython.ExpatTok.XML_TOK_START_TAG_WITH_ATTS
import dev.mooner.neonpython.ExpatTok.XML_TOK_TRAILING_CR
import dev.mooner.neonpython.ExpatTok.XML_TOK_TRAILING_RSQB
import dev.mooner.neonpython.ExpatTok.XML_TOK_XML_DECL
import dev.mooner.neonpython.Expat.XML_ACCOUNT_DIRECT
import dev.mooner.neonpython.Expat.XML_ACCOUNT_ENTITY_EXPANSION
import dev.mooner.neonpython.Expat.XML_ACCOUNT_NONE
import dev.mooner.neonpython.Expat.XML_ERROR_ABORTED
import dev.mooner.neonpython.Expat.XML_ERROR_AMPLIFICATION_LIMIT_BREACH
import dev.mooner.neonpython.Expat.XML_ERROR_ASYNC_ENTITY
import dev.mooner.neonpython.Expat.XML_ERROR_ATTRIBUTE_EXTERNAL_ENTITY_REF
import dev.mooner.neonpython.Expat.XML_ERROR_BAD_CHAR_REF
import dev.mooner.neonpython.Expat.XML_ERROR_BINARY_ENTITY_REF
import dev.mooner.neonpython.Expat.XML_ERROR_DUPLICATE_ATTRIBUTE
import dev.mooner.neonpython.Expat.XML_ERROR_ENTITY_DECLARED_IN_PE
import dev.mooner.neonpython.Expat.XML_ERROR_EXTERNAL_ENTITY_HANDLING
import dev.mooner.neonpython.Expat.XML_ERROR_FINISHED
import dev.mooner.neonpython.Expat.XML_ERROR_INVALID_ARGUMENT
import dev.mooner.neonpython.Expat.XML_ERROR_INVALID_TOKEN
import dev.mooner.neonpython.Expat.XML_ERROR_JUNK_AFTER_DOC_ELEMENT
import dev.mooner.neonpython.Expat.XML_ERROR_MISPLACED_XML_PI
import dev.mooner.neonpython.Expat.XML_ERROR_NONE
import dev.mooner.neonpython.Expat.XML_ERROR_NOT_STARTED
import dev.mooner.neonpython.Expat.XML_ERROR_NO_BUFFER
import dev.mooner.neonpython.Expat.XML_ERROR_NO_ELEMENTS
import dev.mooner.neonpython.Expat.XML_ERROR_NO_MEMORY
import dev.mooner.neonpython.Expat.XML_ERROR_PARTIAL_CHAR
import dev.mooner.neonpython.Expat.XML_ERROR_RECURSIVE_ENTITY_REF
import dev.mooner.neonpython.Expat.XML_ERROR_RESERVED_NAMESPACE_URI
import dev.mooner.neonpython.Expat.XML_ERROR_RESERVED_PREFIX_XML
import dev.mooner.neonpython.Expat.XML_ERROR_RESERVED_PREFIX_XMLNS
import dev.mooner.neonpython.Expat.XML_ERROR_SUSPENDED
import dev.mooner.neonpython.Expat.XML_ERROR_SUSPEND_PE
import dev.mooner.neonpython.Expat.XML_ERROR_SYNTAX
import dev.mooner.neonpython.Expat.XML_ERROR_TAG_MISMATCH
import dev.mooner.neonpython.Expat.XML_ERROR_UNBOUND_PREFIX
import dev.mooner.neonpython.Expat.XML_ERROR_UNCLOSED_CDATA_SECTION
import dev.mooner.neonpython.Expat.XML_ERROR_UNCLOSED_TOKEN
import dev.mooner.neonpython.Expat.XML_ERROR_UNDECLARING_PREFIX
import dev.mooner.neonpython.Expat.XML_ERROR_UNDEFINED_ENTITY
import dev.mooner.neonpython.Expat.XML_ERROR_UNEXPECTED_STATE
import dev.mooner.neonpython.Expat.XML_FINISHED
import dev.mooner.neonpython.Expat.XML_INITIALIZED
import dev.mooner.neonpython.Expat.XML_PARSING
import dev.mooner.neonpython.Expat.XML_STATUS_ERROR
import dev.mooner.neonpython.Expat.XML_STATUS_OK
import dev.mooner.neonpython.Expat.XML_STATUS_SUSPENDED
import dev.mooner.neonpython.Expat.XML_SUSPENDED

/** Expat's enums and XML_ErrorString (Modules/expat/expat.h, Expat 2.8.1). */
object Expat {
    const val XML_ERROR_NONE = 0
    const val XML_ERROR_NO_MEMORY = 1
    const val XML_ERROR_SYNTAX = 2
    const val XML_ERROR_NO_ELEMENTS = 3
    const val XML_ERROR_INVALID_TOKEN = 4
    const val XML_ERROR_UNCLOSED_TOKEN = 5
    const val XML_ERROR_PARTIAL_CHAR = 6
    const val XML_ERROR_TAG_MISMATCH = 7
    const val XML_ERROR_DUPLICATE_ATTRIBUTE = 8
    const val XML_ERROR_JUNK_AFTER_DOC_ELEMENT = 9
    const val XML_ERROR_PARAM_ENTITY_REF = 10
    const val XML_ERROR_UNDEFINED_ENTITY = 11
    const val XML_ERROR_RECURSIVE_ENTITY_REF = 12
    const val XML_ERROR_ASYNC_ENTITY = 13
    const val XML_ERROR_BAD_CHAR_REF = 14
    const val XML_ERROR_BINARY_ENTITY_REF = 15
    const val XML_ERROR_ATTRIBUTE_EXTERNAL_ENTITY_REF = 16
    const val XML_ERROR_MISPLACED_XML_PI = 17
    const val XML_ERROR_UNKNOWN_ENCODING = 18
    const val XML_ERROR_INCORRECT_ENCODING = 19
    const val XML_ERROR_UNCLOSED_CDATA_SECTION = 20
    const val XML_ERROR_EXTERNAL_ENTITY_HANDLING = 21
    const val XML_ERROR_NOT_STANDALONE = 22
    const val XML_ERROR_UNEXPECTED_STATE = 23
    const val XML_ERROR_ENTITY_DECLARED_IN_PE = 24
    const val XML_ERROR_FEATURE_REQUIRES_XML_DTD = 25
    const val XML_ERROR_CANT_CHANGE_FEATURE_ONCE_PARSING = 26
    const val XML_ERROR_UNBOUND_PREFIX = 27
    const val XML_ERROR_UNDECLARING_PREFIX = 28
    const val XML_ERROR_INCOMPLETE_PE = 29
    const val XML_ERROR_XML_DECL = 30
    const val XML_ERROR_TEXT_DECL = 31
    const val XML_ERROR_PUBLICID = 32
    const val XML_ERROR_SUSPENDED = 33
    const val XML_ERROR_NOT_SUSPENDED = 34
    const val XML_ERROR_ABORTED = 35
    const val XML_ERROR_FINISHED = 36
    const val XML_ERROR_SUSPEND_PE = 37
    const val XML_ERROR_RESERVED_PREFIX_XML = 38
    const val XML_ERROR_RESERVED_PREFIX_XMLNS = 39
    const val XML_ERROR_RESERVED_NAMESPACE_URI = 40
    const val XML_ERROR_INVALID_ARGUMENT = 41
    const val XML_ERROR_NO_BUFFER = 42
    const val XML_ERROR_AMPLIFICATION_LIMIT_BREACH = 43
    const val XML_ERROR_NOT_STARTED = 44

    /** XML_ErrorString's messages, by code (0, XML_ERROR_NONE, has none). */
    @JvmField val ERROR_STRINGS = arrayOf(null, "out of memory", "syntax error", "no element found",
        "not well-formed (invalid token)", "unclosed token", "partial character", "mismatched tag", "duplicate attribute",
        "junk after document element", "illegal parameter entity reference", "undefined entity",
        "recursive entity reference", "asynchronous entity", "reference to invalid character number",
        "reference to binary entity", "reference to external entity in attribute",
        "XML or text declaration not at start of entity", "unknown encoding",
        "encoding specified in XML declaration is incorrect", "unclosed CDATA section",
        "error in processing external entity reference", "document is not standalone",
        "unexpected parser state - please send a bug report", "entity declared in parameter entity",
        "requested feature requires XML_DTD support in Expat", "cannot change setting once parsing has begun",
        "unbound prefix", "must not undeclare prefix", "incomplete markup in parameter entity",
        "XML declaration not well-formed", "text declaration not well-formed", "illegal character(s) in public id",
        "parser suspended", "parser not suspended", "parsing aborted", "parsing finished",
        "cannot suspend in external parameter entity",
        "reserved prefix (xml) must not be undeclared or bound to another namespace name",
        "reserved prefix (xmlns) must not be declared or undeclared",
        "prefix must not be bound to one of the reserved namespace names", "invalid argument",
        "a successful prior call to function XML_GetBuffer is required",
        "limit on input amplification factor (from DTD and entities) breached", "parser not started")

    /** XML_ErrorString */
    @JvmStatic
    fun errorString(code: Long): String? = if (code < 0 || code >= ERROR_STRINGS.size) null else ERROR_STRINGS[code.toInt()]

    const val XML_STATUS_ERROR = 0
    const val XML_STATUS_OK = 1
    const val XML_STATUS_SUSPENDED = 2

    const val XML_INITIALIZED = 0
    const val XML_PARSING = 1
    const val XML_FINISHED = 2
    const val XML_SUSPENDED = 3

    const val XML_PARAM_ENTITY_PARSING_NEVER = 0
    const val XML_PARAM_ENTITY_PARSING_UNLESS_STANDALONE = 1
    const val XML_PARAM_ENTITY_PARSING_ALWAYS = 2

    const val XML_CTYPE_EMPTY = 1
    const val XML_CTYPE_ANY = 2
    const val XML_CTYPE_MIXED = 3
    const val XML_CTYPE_NAME = 4
    const val XML_CTYPE_CHOICE = 5
    const val XML_CTYPE_SEQ = 6
    const val XML_CQUANT_NONE = 0
    const val XML_CQUANT_OPT = 1
    const val XML_CQUANT_REP = 2
    const val XML_CQUANT_PLUS = 3

    const val XML_ACCOUNT_DIRECT = 0
    const val XML_ACCOUNT_ENTITY_EXPANSION = 1
    const val XML_ACCOUNT_NONE = 2

    const val MAJOR_VERSION = 2
    const val MINOR_VERSION = 8
    const val MICRO_VERSION = 1
    const val XML_CONTEXT_BYTES = 1024
    const val INIT_BUFFER_SIZE = 1024
    const val INIT_DATA_BUF_SIZE = 1024
    const val INIT_ATTS_SIZE = 16
    const val EXPAND_SPARE = 24
    const val BLAP_MAXIMUM_AMPLIFICATION_DEFAULT = 100.0f
    const val BLAP_ACTIVATION_THRESHOLD_DEFAULT = 8388608L
    const val ALLOC_TRACKER_MAXIMUM_AMPLIFICATION_DEFAULT = 100.0f
    const val ALLOC_TRACKER_ACTIVATION_THRESHOLD_DEFAULT = 67108864L

    /** XML_GetFeatureList, as CPython 3.14.6 builds Expat (XML_DTD, XML_NS, XML_GE, XML_CONTEXT_BYTES 1024). */
    @JvmField val FEATURES = listOf("sizeof(XML_Char)" to 1L, "sizeof(XML_LChar)" to 1L, "XML_DTD" to 0L,
        "XML_CONTEXT_BYTES" to 1024L, "XML_NS" to 0L, "XML_BLAP_MAX_AMP" to 100L, "XML_BLAP_ACT_THRES" to 8388608L,
        "XML_GE" to 0L, "XML_AT_MAX_AMP" to 100L, "XML_AT_ACT_THRES" to 67108864L)

    /** A C string's bytes ([s] its bytes as ISO-8859-1 characters) up to its first NUL. */
    @JvmStatic
    fun cstr(s: String): String {
        val i = s.indexOf('\u0000')
        return if (i < 0) s else s.substring(0, i)
    }

    /** A byte string (each character a byte) of [b][from, to). */
    @JvmStatic
    fun bstr(b: ByteArray, from: Int, to: Int): String = String(b, from, to - from, Charsets.ISO_8859_1)

    /** The bytes of a byte string. */
    @JvmStatic
    fun bytes(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
}

/** ENTITY: a general or parameter entity; [text] its replacement text (internal UTF-8), null for an external one. */
class XEntity(@JvmField val name: String) {
    @JvmField var text: ByteArray? = null
    @JvmField var processed = 0
    @JvmField var systemId: String? = null
    @JvmField var base: String? = null
    @JvmField var publicId: String? = null
    @JvmField var notation: String? = null
    @JvmField var open = false
    @JvmField var hasMore = false
    @JvmField var isParam = false
    @JvmField var isInternal = false
}

/** PREFIX: a namespace prefix (the default namespace's [name] null), its current binding. */
class XPrefix(@JvmField val name: String?) {
    @JvmField var binding: XBinding? = null
}

/** BINDING: a prefix bound to [uri] (the namespace separator after it, where there is one). */
class XBinding {
    @JvmField var prefix: XPrefix? = null
    @JvmField var nextTagBinding: XBinding? = null
    @JvmField var prevPrefixBinding: XBinding? = null
    @JvmField var attId: XAttributeId? = null
    @JvmField var uri = ""
    @JvmField var uriAlloc = 0
}

/** ATTRIBUTE_ID: an attribute's name; [flag] is the byte C keeps before the name (seen, or prefixed). */
class XAttributeId(@JvmField val name: String) {
    @JvmField var prefix: XPrefix? = null
    @JvmField var maybeTokenized = false
    @JvmField var xmlns = false
    @JvmField var flag = 0
}

/** DEFAULT_ATTRIBUTE */
class XDefaultAttribute(@JvmField val id: XAttributeId, @JvmField val isCdata: Boolean, @JvmField val value: String?)

/** ELEMENT_TYPE: an element type the DTD or the content named, its default attributes. */
class XElementType(@JvmField val name: String) {
    @JvmField var prefix: XPrefix? = null
    @JvmField var idAtt: XAttributeId? = null
    @JvmField val defaultAtts = ArrayList<XDefaultAttribute>()
    @JvmField val defaultAttsNames = HashSet<String>()
}

/** TAG: an open element, its raw name (as in the document) and its name as reported. */
class XTag {
    @JvmField var parent: XTag? = null
    @JvmField var rawName = ByteArray(32)
    @JvmField var rawNameLength = 0
    @JvmField var name = ""
    @JvmField var localPart: String? = null
    @JvmField var prefix: String? = null
    @JvmField var bindings: XBinding? = null
}

/** m_eventPtr and m_eventEndPtr, or an open internal entity's (-1: NULL). */
open class XEvents {
    @JvmField var ptr = -1
    @JvmField var endPtr = -1
}

/** OPEN_INTERNAL_ENTITY */
class XOpenEntity : XEvents() {
    @JvmField var next: XOpenEntity? = null
    @JvmField var entity: XEntity? = null
    @JvmField var startTagLevel = 0
    @JvmField var betweenDecl = false
    @JvmField var type = 0
}

/** XML_Content: a content model. */
class XContent(@JvmField val type: Int, @JvmField val quant: Int, @JvmField val name: String?, @JvmField val children: Array<XContent>)

/** CONTENT_SCAFFOLD */
class XScaffold {
    @JvmField var type = 0
    @JvmField var quant = 0
    @JvmField var name: String? = null
    @JvmField var firstchild = 0
    @JvmField var lastchild = 0
    @JvmField var childcnt = 0
    @JvmField var nextsib = 0
}

/**
 * STRING_POOL: where strings are built (internal UTF-8 bytes). A string finished is taken out of it as a byte string
 * ([string]), so what a finished string's bytes held is let go when the pool grows.
 */
class XPool(@JvmField val parser: ExpatParser) {
    @JvmField var buf = EMPTY
    @JvmField var start = 0
    @JvmField var ptr = 0
    /** Whether it has a block (C's pool->blocks, start non-NULL). */
    @JvmField var hasBlock = false

    fun length() = ptr - start
    fun lastChar() = buf[ptr - 1].toInt() and 0xFF
    fun chop() {
        ptr--
    }
    fun discard() {
        ptr = start
    }
    fun finish() {
        start = ptr
    }
    /** poolClear: the strings let go; the storage kept for the next (C's free blocks). */
    fun clear() {
        start = 0
        ptr = 0
        hasBlock = false
    }

    /**
     * poolGrow: room for more, the string being built moved to the start (its storage reused, as C reuses a free
     * block, else doubled); false where the allocation tracker refuses it.
     */
    fun grow(): Boolean {
        if (!hasBlock && buf.isNotEmpty()) {
            hasBlock = true
            if (ptr < buf.size) return true
        }
        val used = ptr - start
        if (start > 0 && used < buf.size / 2) {
            System.arraycopy(buf, start, buf, 0, used)
            start = 0
            ptr = used
            hasBlock = true
            return true
        }
        val size = maxOf(1024, buf.size * 2)
        if (!parser.trackAlloc((size - buf.size).toLong())) return false
        val nb = ByteArray(size)
        System.arraycopy(buf, start, nb, 0, used)
        buf = nb
        start = 0
        ptr = used
        hasBlock = true
        return true
    }

    fun appendChar(c: Int): Boolean {
        if (ptr == buf.size && !grow()) return false
        buf[ptr++] = c.toByte()
        return true
    }

    /** poolAppend: [b][p, end) converted from [enc]. */
    fun append(enc: ExpatEnc, b: ByteArray, p: Int, end: Int): Boolean {
        if (!hasBlock && !grow()) return false
        val from = XPtr().also { it.p = p }
        val to = XPtr()
        while (true) {
            to.p = ptr
            val r = enc.toUtf8(b, from, end, buf, to, buf.size)
            ptr = to.p
            if (r == ExpatTok.XML_CONVERT_COMPLETED || r == ExpatTok.XML_CONVERT_INPUT_INCOMPLETE) break
            if (!grow()) return false
        }
        return true
    }

    /** poolStoreString: [b][p, end) converted and NUL-terminated; the string (not finished). */
    fun storeString(enc: ExpatEnc, b: ByteArray, p: Int, end: Int): String? {
        if (!append(enc, b, p, end)) return null
        if (!appendChar(0)) return null
        return string()
    }

    fun appendString(s: String): Boolean {
        for (c in s) if (!appendChar(c.code)) return false
        return true
    }

    /** poolCopyString: [s] stored and finished. */
    fun copyString(s: String): String? {
        if (!appendString(s) || !appendChar(0)) return null
        finish()
        return s
    }

    /** The string being built, up to its NUL. */
    fun string(): String {
        var e = start
        while (e < ptr && buf[e].toInt() != 0) e++
        return Expat.bstr(buf, start, e)
    }

    /** The bytes being built (NULs too). */
    fun bytes(): ByteArray = buf.copyOfRange(start, ptr)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/** DTD: what the prolog declared (shared by an external parameter entity's parser). */
class XDtd(parser: ExpatParser) {
    @JvmField val generalEntities = LinkedHashMap<String, XEntity>()
    @JvmField val elementTypes = LinkedHashMap<String, XElementType>()
    @JvmField val attributeIds = LinkedHashMap<String, XAttributeId>()
    @JvmField val prefixes = LinkedHashMap<String, XPrefix>()
    @JvmField val paramEntities = LinkedHashMap<String, XEntity>()
    @JvmField val pool = XPool(parser)
    @JvmField val entityValuePool = XPool(parser)
    @JvmField var keepProcessing = true
    @JvmField var hasParamEntityRefs = false
    @JvmField var standalone = false
    @JvmField var paramEntityRead = false
    @JvmField val defaultPrefix = XPrefix(null)
    @JvmField var inEldecl = false
    @JvmField var scaffold: Array<XScaffold?> = arrayOf()
    @JvmField var contentStringLen = 0
    @JvmField var scaffSize = 0
    @JvmField var scaffCount = 0
    @JvmField var scaffLevel = 0
    @JvmField var scaffIndex: IntArray? = null
}

/**
 * XML_Parser (Modules/expat/xmlparse.c, Expat 2.8.1 as CPython 3.14.6 bundles it: XML_DTD, XML_NS, XML_GE,
 * XML_CONTEXT_BYTES 1024, internal UTF-8): xmlparse.c's parser in Kotlin, its processors, its parse buffer and its
 * pointers (offsets into the buffer each scans; -1 for NULL), its billion laughs accounting. What C keeps in hash
 * tables keyed by name is in maps (the order of iteration, randomized in C, is insertion order); its string pools
 * give byte strings (each character a byte of internal UTF-8, see [Expat.bstr]). The prolog's side is in
 * PyExpatDtd.kt. Handlers are Kotlin functions, called as C calls them; [ExpatParser] knows nothing of Python.
 */
class ExpatParser(encodingName: String?, nsSep: Int, dtd: XDtd?, @JvmField val parentParser: ExpatParser?) {
    // ------------------------------------------------------------------ handlers
    @JvmField var startElementHandler: ((String, Array<String?>) -> Unit)? = null
    @JvmField var endElementHandler: ((String) -> Unit)? = null
    @JvmField var characterDataHandler: ((ByteArray, Int, Int) -> Unit)? = null
    @JvmField var processingInstructionHandler: ((String, String) -> Unit)? = null
    @JvmField var commentHandler: ((String) -> Unit)? = null
    @JvmField var startCdataSectionHandler: (() -> Unit)? = null
    @JvmField var endCdataSectionHandler: (() -> Unit)? = null
    @JvmField var defaultHandler: ((ByteArray, Int, Int) -> Unit)? = null
    @JvmField var startDoctypeDeclHandler: ((String?, String?, String?, Int) -> Unit)? = null
    @JvmField var endDoctypeDeclHandler: (() -> Unit)? = null
    @JvmField var unparsedEntityDeclHandler: ((String, String?, String?, String?, String?) -> Unit)? = null
    @JvmField var notationDeclHandler: ((String?, String?, String?, String?) -> Unit)? = null
    @JvmField var startNamespaceDeclHandler: ((String?, String?) -> Unit)? = null
    @JvmField var endNamespaceDeclHandler: ((String?) -> Unit)? = null
    @JvmField var notStandaloneHandler: (() -> Int)? = null
    @JvmField var externalEntityRefHandler: ((String?, String?, String?, String?) -> Int)? = null
    @JvmField var skippedEntityHandler: ((String, Int) -> Unit)? = null
    /** An unknown encoding's byte map (as XML_Encoding.map) for its name, null where there is none. */
    @JvmField var unknownEncodingHandler: ((String) -> IntArray?)? = null
    @JvmField var elementDeclHandler: ((String, XContent) -> Unit)? = null
    @JvmField var attlistDeclHandler: ((String, String, String?, String?, Int) -> Unit)? = null
    @JvmField var entityDeclHandler: ((String, Int, ByteArray?, String?, String?, String?, String?) -> Unit)? = null
    @JvmField var xmlDeclHandler: ((String?, String?, Int) -> Unit)? = null
    @JvmField var defaultExpandInternalEntities = true

    // ------------------------------------------------------------------ state
    @JvmField var buffer: ByteArray? = null
    @JvmField var bufferPtr = 0
    @JvmField var bufferEnd = 0
    @JvmField var parseEndByteIndex = 0L
    @JvmField var parseEndPtr = -1
    @JvmField var partialTokenBytesBefore = 0L
    @JvmField var reparseDeferralEnabled = true
    @JvmField var lastBufferRequestSize = 0
    @JvmField val dataBuf = ByteArray(Expat.INIT_DATA_BUF_SIZE)
    @JvmField var ns = false
    @JvmField var nsTriplets = false
    @JvmField var namespaceSeparator = '!'.code
    @JvmField val initEncoding: InitEnc
    @JvmField var encoding: ExpatEnc
    @JvmField var internalEncoding: ExpatEnc
    @JvmField var protocolEncodingName: String? = null
    @JvmField val prologState = ExpatProlog()
    @JvmField var processor = PROC_PROLOG_INIT
    @JvmField var errorCode = XML_ERROR_NONE
    @JvmField val events = XEvents()
    @JvmField var positionPtr = -1
    @JvmField var openInternalEntities: XOpenEntity? = null
    @JvmField var freeInternalEntities: XOpenEntity? = null
    @JvmField var openAttributeEntities: XOpenEntity? = null
    @JvmField var freeAttributeEntities: XOpenEntity? = null
    @JvmField var openValueEntities: XOpenEntity? = null
    @JvmField var freeValueEntities: XOpenEntity? = null
    @JvmField var tagLevel = 0
    @JvmField var declEntity: XEntity? = null
    @JvmField var doctypeName: String? = null
    @JvmField var doctypeSysid: String? = null
    @JvmField var doctypePubid: String? = null
    @JvmField var declAttributeType: String? = null
    @JvmField var declNotationName: String? = null
    @JvmField var declNotationPublicId: String? = null
    @JvmField var declElementType: XElementType? = null
    @JvmField var declAttributeId: XAttributeId? = null
    @JvmField var declAttributeIsCdata = false
    @JvmField var declAttributeIsId = false
    @JvmField var dtd: XDtd
    @JvmField var curBase: String? = null
    @JvmField var tagStack: XTag? = null
    @JvmField var freeTagList: XTag? = null
    @JvmField var inheritedBindings: XBinding? = null
    @JvmField var freeBindingList: XBinding? = null
    @JvmField var atts = Array(Expat.INIT_ATTS_SIZE) { XAttribute() }
    /** appAtts: the attribute names and values for the start handler, a null after the last. */
    @JvmField var appAtts = arrayOfNulls<String>(2 * Expat.INIT_ATTS_SIZE + 1)
    @JvmField var nSpecifiedAtts = 0
    @JvmField var idAttIndex = -1
    @JvmField val position = XPosition()
    @JvmField val tempPool = XPool(this)
    @JvmField val temp2Pool = XPool(this)
    @JvmField var groupConnector = ByteArray(0)
    @JvmField var parsing = XML_INITIALIZED
    @JvmField var finalBuffer = false
    @JvmField var isParamEntity = false
    @JvmField var useForeignDTD = false
    @JvmField var paramEntityParsing = Expat.XML_PARAM_ENTITY_PARSING_NEVER
    @JvmField var reenter = false
    // (ACCOUNTING)
    @JvmField var countBytesDirect = 0L
    @JvmField var countBytesIndirect = 0L
    @JvmField var maximumAmplificationFactor = Expat.BLAP_MAXIMUM_AMPLIFICATION_DEFAULT
    @JvmField var activationThresholdBytes = Expat.BLAP_ACTIVATION_THRESHOLD_DEFAULT
    // (MALLOC_TRACKER)
    @JvmField var bytesAllocated = 0L
    @JvmField var allocMaximumAmplificationFactor = Expat.ALLOC_TRACKER_MAXIMUM_AMPLIFICATION_DEFAULT
    @JvmField var allocActivationThresholdBytes = Expat.ALLOC_TRACKER_ACTIVATION_THRESHOLD_DEFAULT

    init {
        // (parserCreate: the parser struct and its first allocations, as the tracker counts them)
        val root = rootParser()
        root.bytesAllocated += PARSER_STRUCT + 8 + Expat.INIT_ATTS_SIZE * 32L + 8 + Expat.INIT_DATA_BUF_SIZE + 8
        if (dtd == null) root.bytesAllocated += DTD_STRUCT + 8
        initEncoding = InitEnc(nsSep >= 0) { encoding = it }
        encoding = initEncoding
        this.dtd = dtd ?: XDtd(this)
        parserInit(encodingName)
        if (nsSep >= 0) {
            ns = true
            internalEncoding = ExpatTok.internalEncoding(true)
            namespaceSeparator = nsSep
        } else internalEncoding = ExpatTok.internalEncoding(false)
    }

    /** parserInit */
    private fun parserInit(encodingName: String?) {
        processor = PROC_PROLOG_INIT
        prologState.init()
        if (encodingName != null) protocolEncodingName = encodingName
        curBase = null
        ExpatTok.initEncoding(initEncoding, null)
        encoding = initEncoding
        bufferPtr = 0
        bufferEnd = 0
        parseEndByteIndex = 0
        parseEndPtr = -1
        partialTokenBytesBefore = 0
        reparseDeferralEnabled = true
        lastBufferRequestSize = 0
        position.lineNumber = 0
        position.columnNumber = 0
        errorCode = XML_ERROR_NONE
        events.ptr = -1
        events.endPtr = -1
        positionPtr = -1
        defaultExpandInternalEntities = true
        parsing = XML_INITIALIZED
    }

    /** getRootParserOf */
    fun rootParser(): ExpatParser {
        var p = this
        while (true) p = p.parentParser ?: return p
    }

    private fun parserBusy() = parsing == XML_PARSING || parsing == XML_SUSPENDED

    // ------------------------------------------------------------------ the API

    /** XML_SetEncoding: false once parsing has begun. */
    fun setEncoding(name: String?): Boolean {
        if (parserBusy()) return false
        protocolEncodingName = name
        return true
    }

    /** XML_UseForeignDTD */
    fun useForeignDTD(use: Boolean): Int {
        if (parserBusy()) return Expat.XML_ERROR_CANT_CHANGE_FEATURE_ONCE_PARSING
        useForeignDTD = use
        return XML_ERROR_NONE
    }

    /** XML_SetReturnNSTriplet */
    fun setReturnNSTriplet(doNst: Boolean) {
        if (parserBusy()) return
        nsTriplets = doNst
    }

    /** XML_SetBase */
    fun setBase(p: String?) {
        curBase = if (p != null) dtd.pool.copyString(p) else null
    }

    /** XML_SetParamEntityParsing: 0 once parsing has begun. */
    fun setParamEntityParsing(pe: Int): Int {
        if (parserBusy()) return 0
        paramEntityParsing = pe
        return 1
    }

    /** XML_SetDefaultHandler, XML_SetDefaultHandlerExpand */
    fun setDefaultHandler(h: ((ByteArray, Int, Int) -> Unit)?, expand: Boolean) {
        defaultHandler = h
        defaultExpandInternalEntities = expand
    }

    /** XML_SetBillionLaughsAttackProtectionMaximumAmplification */
    fun setBlapMaximumAmplification(f: Float): Boolean {
        if (parentParser != null || f.isNaN() || f < 1.0f) return false
        maximumAmplificationFactor = f
        return true
    }

    /** XML_SetBillionLaughsAttackProtectionActivationThreshold */
    fun setBlapActivationThreshold(t: Long): Boolean {
        if (parentParser != null) return false
        activationThresholdBytes = t
        return true
    }

    /** XML_SetAllocTrackerMaximumAmplification */
    fun setAllocMaximumAmplification(f: Float): Boolean {
        if (parentParser != null || f.isNaN() || f < 1.0f) return false
        allocMaximumAmplificationFactor = f
        return true
    }

    /** XML_SetAllocTrackerActivationThreshold */
    fun setAllocActivationThreshold(t: Long): Boolean {
        if (parentParser != null) return false
        allocActivationThresholdBytes = t
        return true
    }

    /** startParsing: the implicit context (the xml prefix) for a root parser processing namespaces. */
    private fun startParsing(): Boolean = if (ns) setContext(IMPLICIT_CONTEXT) else true

    /** XML_Parse: [len] bytes of [s] from [off]. */
    fun parse(s: ByteArray, off: Int, len: Int, isFinal: Boolean): Int {
        when (parsing) {
            XML_SUSPENDED -> {
                errorCode = XML_ERROR_SUSPENDED
                return XML_STATUS_ERROR
            }
            XML_FINISHED -> {
                errorCode = XML_ERROR_FINISHED
                return XML_STATUS_ERROR
            }
            XML_INITIALIZED -> {
                if (parentParser == null && !startParsing()) {
                    errorCode = XML_ERROR_NO_MEMORY
                    return XML_STATUS_ERROR
                }
                parsing = XML_PARSING
            }
            else -> parsing = XML_PARSING
        }
        val at = getBuffer(len)
        if (at < 0) return XML_STATUS_ERROR
        if (len > 0) System.arraycopy(s, off, buffer!!, at, len)
        return parseBuffer(len, isFinal)
    }

    /** XML_ParseBuffer: the [len] bytes put at the buffer's end parsed. */
    fun parseBuffer(len: Int, isFinal: Boolean): Int {
        if (len < 0) {
            errorCode = XML_ERROR_INVALID_ARGUMENT
            return XML_STATUS_ERROR
        }
        when (parsing) {
            XML_SUSPENDED -> {
                errorCode = XML_ERROR_SUSPENDED
                return XML_STATUS_ERROR
            }
            XML_FINISHED -> {
                errorCode = XML_ERROR_FINISHED
                return XML_STATUS_ERROR
            }
            XML_INITIALIZED -> {
                // (has someone called XML_GetBuffer successfully before?)
                if (buffer == null) {
                    errorCode = XML_ERROR_NO_BUFFER
                    return XML_STATUS_ERROR
                }
                if (parentParser == null && !startParsing()) {
                    errorCode = XML_ERROR_NO_MEMORY
                    return XML_STATUS_ERROR
                }
                parsing = XML_PARSING
            }
            else -> parsing = XML_PARSING
        }
        val start = bufferPtr
        positionPtr = start
        bufferEnd += len
        parseEndPtr = bufferEnd
        parseEndByteIndex += len
        finalBuffer = isFinal
        val endPtr = XPtr().also { it.p = bufferPtr }
        errorCode = callProcessor(start, parseEndPtr, endPtr)
        bufferPtr = endPtr.p
        if (errorCode != XML_ERROR_NONE) {
            events.endPtr = events.ptr
            processor = PROC_ERROR
            return XML_STATUS_ERROR
        }
        var result = XML_STATUS_OK
        when (parsing) {
            XML_SUSPENDED -> result = XML_STATUS_SUSPENDED
            XML_INITIALIZED, XML_PARSING -> if (isFinal) {
                parsing = XML_FINISHED
                return result
            }
        }
        encoding.updatePosition(buffer!!, positionPtr, bufferPtr, position)
        positionPtr = bufferPtr
        return result
    }

    /** XML_GetBuffer: where [len] bytes may be put in the buffer (its end), -1 where it cannot be had. */
    fun getBuffer(len: Int): Int {
        if (len < 0) {
            errorCode = XML_ERROR_NO_MEMORY
            return -1
        }
        when (parsing) {
            XML_SUSPENDED -> {
                errorCode = XML_ERROR_SUSPENDED
                return -1
            }
            XML_FINISHED -> {
                errorCode = XML_ERROR_FINISHED
                return -1
            }
        }
        lastBufferRequestSize = len
        val buf = buffer
        val lim = buf?.size ?: 0
        if (buf == null || len > lim - bufferEnd) {
            var neededSize = len + (bufferEnd - bufferPtr)
            if (neededSize < 0) {
                errorCode = XML_ERROR_NO_MEMORY
                return -1
            }
            val keep = minOf(bufferPtr, Expat.XML_CONTEXT_BYTES)
            if (keep > Int.MAX_VALUE - neededSize) {
                errorCode = XML_ERROR_NO_MEMORY
                return -1
            }
            neededSize += keep
            if (buf != null && neededSize <= lim) {
                if (keep < bufferPtr) {
                    val offset = bufferPtr - keep
                    System.arraycopy(buf, offset, buf, 0, bufferEnd - bufferPtr + keep)
                    bufferEnd -= offset
                    bufferPtr -= offset
                }
            } else {
                var bufferSize = if (lim == 0) Expat.INIT_BUFFER_SIZE else lim
                do {
                    bufferSize = (2L * bufferSize).toInt()
                } while (bufferSize in 1 until neededSize)
                if (bufferSize <= 0) {
                    errorCode = XML_ERROR_NO_MEMORY
                    return -1
                }
                val nb = try {
                    ByteArray(bufferSize)
                } catch (e: OutOfMemoryError) {
                    errorCode = XML_ERROR_NO_MEMORY
                    return -1
                }
                if (buf != null) {
                    System.arraycopy(buf, bufferPtr - keep, nb, 0, bufferEnd - bufferPtr + keep)
                    bufferEnd = bufferEnd - bufferPtr + keep
                    bufferPtr = keep
                } else {
                    bufferEnd = 0
                    bufferPtr = 0
                }
                buffer = nb
            }
            events.ptr = -1
            events.endPtr = -1
            positionPtr = -1
        }
        return bufferEnd
    }

    /** XML_StopParser */
    fun stopParser(resumable: Boolean): Int {
        when (parsing) {
            XML_INITIALIZED -> {
                errorCode = XML_ERROR_NOT_STARTED
                return XML_STATUS_ERROR
            }
            XML_SUSPENDED -> {
                if (resumable) {
                    errorCode = XML_ERROR_SUSPENDED
                    return XML_STATUS_ERROR
                }
                parsing = XML_FINISHED
            }
            XML_FINISHED -> {
                errorCode = XML_ERROR_FINISHED
                return XML_STATUS_ERROR
            }
            XML_PARSING -> if (resumable) {
                if (isParamEntity) {
                    errorCode = XML_ERROR_SUSPEND_PE
                    return XML_STATUS_ERROR
                }
                parsing = XML_SUSPENDED
            } else parsing = XML_FINISHED
        }
        return XML_STATUS_OK
    }

    /** XML_GetCurrentByteIndex */
    fun currentByteIndex(): Long = if (events.ptr >= 0) parseEndByteIndex - (parseEndPtr - events.ptr) else -1

    /** XML_GetInputContext: the buffer from the event to the end, or null. */
    fun inputContext(): ByteArray? {
        val b = buffer ?: return null
        if (events.ptr < 0) return null
        return b.copyOfRange(events.ptr, bufferEnd)
    }

    private fun updatePositionToEvent() {
        if (events.ptr >= 0 && positionPtr >= 0 && events.ptr >= positionPtr) {
            encoding.updatePosition(buffer!!, positionPtr, events.ptr, position)
            positionPtr = events.ptr
        }
    }

    /** XML_GetCurrentLineNumber */
    fun currentLineNumber(): Long {
        updatePositionToEvent()
        return position.lineNumber + 1
    }

    /** XML_GetCurrentColumnNumber */
    fun currentColumnNumber(): Long {
        updatePositionToEvent()
        return position.columnNumber
    }

    /** XML_ExternalEntityParserCreate: a parser for an external entity, its context [context] (null: a parameter entity). */
    fun externalEntityParserCreate(context: String?, encodingName: String?): ExpatParser? {
        val oldDtd = dtd
        val newDtd = if (context == null) oldDtd else null
        val root = rootParser()
        if (!root.allocTolerable(PARSER_STRUCT + 8)) return null
        val p = ExpatParser(encodingName, if (ns) namespaceSeparator else -1, newDtd, this)
        p.startElementHandler = startElementHandler
        p.endElementHandler = endElementHandler
        p.characterDataHandler = characterDataHandler
        p.processingInstructionHandler = processingInstructionHandler
        p.commentHandler = commentHandler
        p.startCdataSectionHandler = startCdataSectionHandler
        p.endCdataSectionHandler = endCdataSectionHandler
        p.defaultHandler = defaultHandler
        p.unparsedEntityDeclHandler = unparsedEntityDeclHandler
        p.notationDeclHandler = notationDeclHandler
        p.startNamespaceDeclHandler = startNamespaceDeclHandler
        p.endNamespaceDeclHandler = endNamespaceDeclHandler
        p.notStandaloneHandler = notStandaloneHandler
        p.externalEntityRefHandler = externalEntityRefHandler
        p.skippedEntityHandler = skippedEntityHandler
        p.unknownEncodingHandler = unknownEncodingHandler
        p.elementDeclHandler = elementDeclHandler
        p.attlistDeclHandler = attlistDeclHandler
        p.entityDeclHandler = entityDeclHandler
        p.xmlDeclHandler = xmlDeclHandler
        p.startDoctypeDeclHandler = null
        p.endDoctypeDeclHandler = null
        p.declElementType = declElementType
        p.defaultExpandInternalEntities = defaultExpandInternalEntities
        p.nsTriplets = nsTriplets
        p.reparseDeferralEnabled = reparseDeferralEnabled
        p.paramEntityParsing = paramEntityParsing
        p.prologState.inEntityValue = prologState.inEntityValue
        if (context != null) {
            if (!p.dtdCopy(oldDtd) || !p.setContext(context)) return null
            p.processor = PROC_EXTERNAL_ENTITY_INIT
        } else {
            // (the DTD is shared between the root parser and external PE parsers, so setContext must not be called)
            p.isParamEntity = true
            p.prologState.initExternalEntity()
            p.processor = PROC_EXTERNAL_PAR_ENT_INIT
        }
        return p
    }

    // ------------------------------------------------------------------ processors

    /** callProcessor: the processor run over [start, end) (reparse deferral and re-entry as Expat has them). */
    private fun callProcessor(start: Int, end: Int, endPtr: XPtr): Int {
        val haveNow = (end - start).toLong()
        if (reparseDeferralEnabled && !finalBuffer) {
            val hadBefore = partialTokenBytesBefore
            var availableBuffer = bufferPtr.toLong()
            availableBuffer -= minOf(availableBuffer, Expat.XML_CONTEXT_BYTES.toLong())
            availableBuffer += (buffer?.size ?: 0) - bufferEnd
            val enough = haveNow >= 2 * hadBefore || lastBufferRequestSize.toLong() > availableBuffer
            if (!enough) {
                endPtr.p = start
                return XML_ERROR_NONE
            }
        }
        var ret: Int
        endPtr.p = start
        while (true) {
            ret = runProcessor(endPtr.p, end, endPtr)
            if (parsing != XML_PARSING) reenter = false
            if (!reenter) break
            reenter = false
            if (ret != XML_ERROR_NONE) return ret
        }
        if (ret == XML_ERROR_NONE) partialTokenBytesBefore = if (endPtr.p == start) haveNow else 0
        return ret
    }

    /** parser->m_processor(parser, s, end, nextPtr) */
    fun runProcessor(s: Int, end: Int, nextPtr: XPtr): Int = when (processor) {
        PROC_PROLOG_INIT -> {
            val r = initializeEncoding()
            if (r != XML_ERROR_NONE) r
            else {
                processor = PROC_PROLOG
                prologProcessor(s, end, nextPtr)
            }
        }
        PROC_PROLOG -> prologProcessor(s, end, nextPtr)
        PROC_CONTENT -> contentProcessor(s, end, nextPtr)
        PROC_CDATA_SECTION -> cdataSectionProcessor(s, end, nextPtr)
        PROC_IGNORE_SECTION -> ignoreSectionProcessor(s, end, nextPtr)
        PROC_EXTERNAL_PAR_ENT_INIT -> externalParEntInitProcessor(s, end, nextPtr)
        PROC_EXTERNAL_PAR_ENT -> externalParEntProcessor(s, end, nextPtr)
        PROC_ENTITY_VALUE_INIT -> entityValueInitProcessor(s, end, nextPtr)
        PROC_ENTITY_VALUE -> entityValueProcessor(s, end, nextPtr)
        PROC_EPILOG -> epilogProcessor(s, end, nextPtr)
        PROC_EXTERNAL_ENTITY_INIT -> {
            val r = initializeEncoding()
            if (r != XML_ERROR_NONE) r
            else {
                processor = PROC_EXTERNAL_ENTITY_INIT2
                externalEntityInitProcessor2(s, end, nextPtr)
            }
        }
        PROC_EXTERNAL_ENTITY_INIT2 -> externalEntityInitProcessor2(s, end, nextPtr)
        PROC_EXTERNAL_ENTITY_INIT3 -> externalEntityInitProcessor3(s, end, nextPtr)
        PROC_EXTERNAL_ENTITY_CONTENT -> externalEntityContentProcessor(s, end, nextPtr)
        PROC_INTERNAL_ENTITY -> internalEntityProcessor()
        else -> errorCode
    }

    fun prologProcessor(s: Int, end: Int, nextPtr: XPtr): Int {
        val nt = XPtr().also { it.p = s }
        val b = buffer!!
        val tok = encoding.prologTok(b, s, end, nt)
        return doProlog(encoding, b, s, end, tok, nt.p, nextPtr, !finalBuffer, true, XML_ACCOUNT_DIRECT)
    }

    fun contentProcessor(start: Int, end: Int, endPtr: XPtr): Int =
        doContent(if (parentParser != null) 1 else 0, encoding, buffer!!, start, end, endPtr, !finalBuffer, XML_ACCOUNT_DIRECT)

    private fun externalEntityInitProcessor2(start0: Int, end: Int, endPtr: XPtr): Int {
        var start = start0
        val b = buffer!!
        val nt = XPtr().also { it.p = start }
        val tok = encoding.contentTok(b, start, end, nt)
        when (tok) {
            XML_TOK_BOM -> {
                if (!accountingDiffTolerated(tok, start, nt.p, XML_ACCOUNT_DIRECT)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
                // (at the end of the buffer, the next stage would pass control directly to doContent without
                // processing any text declaration)
                if (nt.p == end && !finalBuffer) {
                    endPtr.p = nt.p
                    return XML_ERROR_NONE
                }
                start = nt.p
            }
            XML_TOK_PARTIAL -> {
                if (!finalBuffer) {
                    endPtr.p = start
                    return XML_ERROR_NONE
                }
                events.ptr = start
                return XML_ERROR_UNCLOSED_TOKEN
            }
            XML_TOK_PARTIAL_CHAR -> {
                if (!finalBuffer) {
                    endPtr.p = start
                    return XML_ERROR_NONE
                }
                events.ptr = start
                return XML_ERROR_PARTIAL_CHAR
            }
        }
        processor = PROC_EXTERNAL_ENTITY_INIT3
        return externalEntityInitProcessor3(start, end, endPtr)
    }

    private fun externalEntityInitProcessor3(start0: Int, end: Int, endPtr: XPtr): Int {
        var start = start0
        val b = buffer!!
        val nt = XPtr().also { it.p = start }
        events.ptr = start
        val tok = encoding.contentTok(b, start, end, nt)
        events.endPtr = nt.p
        when (tok) {
            XML_TOK_XML_DECL -> {
                val result = processXmlDecl(true, start, nt.p)
                if (result != XML_ERROR_NONE) return result
                when (parsing) {
                    XML_SUSPENDED -> {
                        endPtr.p = nt.p
                        return XML_ERROR_NONE
                    }
                    XML_FINISHED -> return XML_ERROR_ABORTED
                    XML_PARSING -> {
                        if (reenter) return XML_ERROR_UNEXPECTED_STATE
                        start = nt.p
                    }
                    else -> start = nt.p
                }
            }
            XML_TOK_PARTIAL -> {
                if (!finalBuffer) {
                    endPtr.p = start
                    return XML_ERROR_NONE
                }
                return XML_ERROR_UNCLOSED_TOKEN
            }
            XML_TOK_PARTIAL_CHAR -> {
                if (!finalBuffer) {
                    endPtr.p = start
                    return XML_ERROR_NONE
                }
                return XML_ERROR_PARTIAL_CHAR
            }
        }
        processor = PROC_EXTERNAL_ENTITY_CONTENT
        tagLevel = 1
        return externalEntityContentProcessor(start, end, endPtr)
    }

    fun externalEntityContentProcessor(start: Int, end: Int, endPtr: XPtr): Int =
        doContent(1, encoding, buffer!!, start, end, endPtr, !finalBuffer, XML_ACCOUNT_ENTITY_EXPANSION)

    /** The event pointers for [enc]: the parser's, or (the internal encoding) the open internal entity's. */
    fun eventsFor(enc: ExpatEnc): XEvents = if (enc === encoding) events else openInternalEntities!!

    // ------------------------------------------------------------------ content

    /** doContent: the content [b][s, end) in [enc]. */
    fun doContent(startTagLevel: Int, enc: ExpatEnc, b: ByteArray, s0: Int, end: Int, nextPtr: XPtr, haveMore: Boolean, account: Int): Int {
        var s = s0
        val ev = eventsFor(enc)
        ev.ptr = s
        val nt = XPtr()
        while (true) {
            nt.p = s
            val tok = enc.contentTok(b, s, end, nt)
            val next = nt.p
            val accountAfter = if (tok == XML_TOK_TRAILING_RSQB || tok == XML_TOK_TRAILING_CR) (if (haveMore) s else end) else next
            if (!accountingDiffTolerated(tok, s, accountAfter, account)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
            ev.endPtr = next
            when (tok) {
                XML_TOK_TRAILING_CR -> {
                    if (haveMore) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    ev.endPtr = end
                    val cd = characterDataHandler
                    if (cd != null) cd(NEWLINE, 0, 1)
                    else if (defaultHandler != null) reportDefault(enc, b, s, end)
                    if (startTagLevel == 0) return XML_ERROR_NO_ELEMENTS
                    if (tagLevel != startTagLevel) return XML_ERROR_ASYNC_ENTITY
                    nextPtr.p = end
                    return XML_ERROR_NONE
                }
                XML_TOK_NONE -> {
                    if (haveMore) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    if (startTagLevel > 0) {
                        if (tagLevel != startTagLevel) return XML_ERROR_ASYNC_ENTITY
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    return XML_ERROR_NO_ELEMENTS
                }
                XML_TOK_INVALID -> {
                    ev.ptr = next
                    return XML_ERROR_INVALID_TOKEN
                }
                XML_TOK_PARTIAL -> {
                    if (haveMore) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    return XML_ERROR_UNCLOSED_TOKEN
                }
                XML_TOK_PARTIAL_CHAR -> {
                    if (haveMore) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    return XML_ERROR_PARTIAL_CHAR
                }
                XML_TOK_ENTITY_REF -> {
                    val r = contentEntityRef(enc, b, s, next, tok)
                    if (r != XML_ERROR_NONE) return r
                }
                XML_TOK_START_TAG_NO_ATTS, XML_TOK_START_TAG_WITH_ATTS -> {
                    val r = startTag(enc, b, s, next, account)
                    if (r != XML_ERROR_NONE) return r
                }
                XML_TOK_EMPTY_ELEMENT_NO_ATTS, XML_TOK_EMPTY_ELEMENT_WITH_ATTS -> {
                    val r = emptyElement(enc, b, s, next, ev)
                    if (r != XML_ERROR_NONE) return r
                    if (tagLevel == 0 && parsing != XML_FINISHED) {
                        if (parsing == XML_SUSPENDED || (parsing == XML_PARSING && reenter)) processor = PROC_EPILOG
                        else return epilogProcessor(next, end, nextPtr)
                    }
                }
                XML_TOK_END_TAG -> {
                    if (tagLevel == startTagLevel) return XML_ERROR_ASYNC_ENTITY
                    val r = endTag(enc, b, s, next, ev)
                    if (r != XML_ERROR_NONE) return r
                    if (tagLevel == 0 && parsing != XML_FINISHED) {
                        if (parsing == XML_SUSPENDED || (parsing == XML_PARSING && reenter)) processor = PROC_EPILOG
                        else return epilogProcessor(next, end, nextPtr)
                    }
                }
                XML_TOK_CHAR_REF -> {
                    val n = enc.charRefNumber(b, s)
                    if (n < 0) return XML_ERROR_BAD_CHAR_REF
                    val cd = characterDataHandler
                    if (cd != null) {
                        val buf = ByteArray(ExpatTok.XML_UTF8_ENCODE_MAX)
                        cd(buf, 0, ExpatTok.utf8Encode(n, buf, 0))
                    } else if (defaultHandler != null) reportDefault(enc, b, s, next)
                }
                XML_TOK_XML_DECL -> return XML_ERROR_MISPLACED_XML_PI
                XML_TOK_DATA_NEWLINE -> {
                    val cd = characterDataHandler
                    if (cd != null) cd(NEWLINE, 0, 1)
                    else if (defaultHandler != null) reportDefault(enc, b, s, next)
                }
                XML_TOK_CDATA_SECT_OPEN -> {
                    val sc = startCdataSectionHandler
                    if (sc != null) sc()
                    else if (defaultHandler != null) reportDefault(enc, b, s, next)
                    nt.p = next
                    val result = doCdataSection(enc, b, nt, end, nextPtr, haveMore, account)
                    if (result != XML_ERROR_NONE) return result
                    if (nt.p < 0) {
                        processor = PROC_CDATA_SECTION
                        return result
                    }
                }
                XML_TOK_TRAILING_RSQB -> {
                    if (haveMore) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    val cd = characterDataHandler
                    if (cd != null) {
                        if (!enc.isUtf8) {
                            val from = XPtr().also { it.p = s }
                            val to = XPtr()
                            enc.toUtf8(b, from, end, dataBuf, to, dataBuf.size)
                            s = from.p
                            cd(dataBuf, 0, to.p)
                        } else cd(b, s, end - s)
                    } else if (defaultHandler != null) reportDefault(enc, b, s, end)
                    if (startTagLevel == 0) {
                        ev.ptr = end
                        return XML_ERROR_NO_ELEMENTS
                    }
                    if (tagLevel != startTagLevel) {
                        ev.ptr = end
                        return XML_ERROR_ASYNC_ENTITY
                    }
                    nextPtr.p = end
                    return XML_ERROR_NONE
                }
                XML_TOK_DATA_CHARS -> {
                    val cd = characterDataHandler
                    if (cd != null) {
                        if (!enc.isUtf8) {
                            val from = XPtr().also { it.p = s }
                            val to = XPtr()
                            while (true) {
                                to.p = 0
                                val r = enc.toUtf8(b, from, next, dataBuf, to, dataBuf.size)
                                ev.endPtr = from.p
                                cd(dataBuf, 0, to.p)
                                if (r == ExpatTok.XML_CONVERT_COMPLETED || r == ExpatTok.XML_CONVERT_INPUT_INCOMPLETE) break
                                ev.ptr = from.p
                            }
                            s = from.p
                        } else cd(b, s, next - s)
                    } else if (defaultHandler != null) reportDefault(enc, b, s, next)
                }
                XML_TOK_PI -> if (!reportProcessingInstruction(enc, b, s, next)) return XML_ERROR_NO_MEMORY
                XML_TOK_COMMENT -> if (!reportComment(enc, b, s, next)) return XML_ERROR_NO_MEMORY
                else -> if (defaultHandler != null) reportDefault(enc, b, s, next)
            }
            val next2 = if (tok == XML_TOK_CDATA_SECT_OPEN) nt.p else next
            when (parsing) {
                XML_SUSPENDED -> {
                    ev.ptr = next2
                    nextPtr.p = next2
                    return XML_ERROR_NONE
                }
                XML_FINISHED -> {
                    ev.ptr = next2
                    return XML_ERROR_ABORTED
                }
                XML_PARSING -> if (reenter) {
                    nextPtr.p = next2
                    return XML_ERROR_NONE
                }
            }
            s = next2
            ev.ptr = s
        }
    }

    /** doContent's XML_TOK_ENTITY_REF: [s, next) the reference. */
    private fun contentEntityRef(enc: ExpatEnc, b: ByteArray, s: Int, next: Int, tok: Int): Int {
        val mb = enc.minBytesPerChar
        val ch = enc.predefinedEntityName(b, s + mb, next - mb)
        if (ch != 0) {
            // (4-6 characters of input replaced by one: no amplification, recorded without protection)
            accountingDiffTolerated(tok, 0, 1, XML_ACCOUNT_ENTITY_EXPANSION)
            val cd = characterDataHandler
            if (cd != null) cd(byteArrayOf(ch.toByte()), 0, 1)
            else if (defaultHandler != null) reportDefault(enc, b, s, next)
            return XML_ERROR_NONE
        }
        val name = dtd.pool.storeString(enc, b, s + mb, next - mb) ?: return XML_ERROR_NO_MEMORY
        val entity = dtd.generalEntities[name]
        dtd.pool.discard()
        // (first, determine if a check for an existing declaration is needed; if yes, check that the entity exists,
        // and that it is internal, otherwise call the skipped entity or default handler)
        if (!dtd.hasParamEntityRefs || dtd.standalone) {
            if (entity == null) return XML_ERROR_UNDEFINED_ENTITY
            else if (!entity.isInternal) return XML_ERROR_ENTITY_DECLARED_IN_PE
        } else if (entity == null) {
            val sk = skippedEntityHandler
            if (sk != null) sk(name, 0)
            else if (defaultHandler != null) reportDefault(enc, b, s, next)
            return XML_ERROR_NONE
        }
        entity!!
        if (entity.open) return XML_ERROR_RECURSIVE_ENTITY_REF
        if (entity.notation != null) return XML_ERROR_BINARY_ENTITY_REF
        if (entity.text != null) {
            if (!defaultExpandInternalEntities) {
                val sk = skippedEntityHandler
                if (sk != null) sk(entity.name, 0)
                else if (defaultHandler != null) reportDefault(enc, b, s, next)
                return XML_ERROR_NONE
            }
            return processEntity(entity, false, ENTITY_INTERNAL)
        }
        val ext = externalEntityRefHandler
        if (ext != null) {
            entity.open = true
            val context = getContext()
            entity.open = false
            if (context == null) return XML_ERROR_NO_MEMORY
            if (ext(context, entity.base, entity.systemId, entity.publicId) == 0) return XML_ERROR_EXTERNAL_ENTITY_HANDLING
            tempPool.discard()
        } else if (defaultHandler != null) reportDefault(enc, b, s, next)
        return XML_ERROR_NONE
    }

    /** doContent's start tag: a TAG pushed, its attributes stored, the start handler called. */
    private fun startTag(enc: ExpatEnc, b: ByteArray, s: Int, next: Int, account: Int): Int {
        val tag = freeTagList?.also { freeTagList = it.parent } ?: run {
            if (!trackAlloc(TAG_STRUCT + 32)) return XML_ERROR_NO_MEMORY
            XTag()
        }
        tag.bindings = null
        tag.parent = tagStack
        tagStack = tag
        tag.localPart = null
        tag.prefix = null
        val rawName = s + enc.minBytesPerChar
        val rawLen = enc.nameLength(b, rawName)
        if (tag.rawName.size < rawLen) tag.rawName = ByteArray(rawLen)
        System.arraycopy(b, rawName, tag.rawName, 0, rawLen)
        tag.rawNameLength = rawLen
        ++tagLevel
        tag.name = convertName(enc, b, rawName, rawName + rawLen)
        val result = storeAtts(enc, b, s, tag, account)
        if (result != XML_ERROR_NONE) return result
        val se = startElementHandler
        if (se != null) se(tag.name, appAtts)
        else if (defaultHandler != null) reportDefault(enc, b, s, next)
        tempPool.clear()
        return XML_ERROR_NONE
    }

    /** A name converted to internal UTF-8 (as the tag's buffer holds it, cut at an incomplete character). */
    private fun convertName(enc: ExpatEnc, b: ByteArray, from0: Int, end: Int): String {
        if (enc.isUtf8) return Expat.bstr(b, from0, end)
        var out = ByteArray(32)
        val from = XPtr().also { it.p = from0 }
        val to = XPtr()
        while (true) {
            val r = enc.toUtf8(b, from, end, out, to, out.size - 1)
            if (from.p >= end || r == ExpatTok.XML_CONVERT_INPUT_INCOMPLETE) break
            out = out.copyOf(out.size * 2)
        }
        return Expat.bstr(out, 0, to.p)
    }

    /** doContent's empty element: the start and end handlers called. */
    private fun emptyElement(enc: ExpatEnc, b: ByteArray, s: Int, next: Int, ev: XEvents): Int {
        val rawName = s + enc.minBytesPerChar
        val name = tempPool.storeString(enc, b, rawName, rawName + enc.nameLength(b, rawName)) ?: return XML_ERROR_NO_MEMORY
        tempPool.finish()
        val tag = XTag()
        tag.name = name
        val result = storeAtts(enc, b, s, tag, XML_ACCOUNT_NONE)
        if (result != XML_ERROR_NONE) {
            freeBindings(tag.bindings)
            return result
        }
        tempPool.finish()
        var noElmHandlers = true
        val se = startElementHandler
        if (se != null) {
            se(tag.name, appAtts)
            noElmHandlers = false
        }
        val ee = endElementHandler
        if (ee != null) {
            if (startElementHandler != null) ev.ptr = ev.endPtr
            ee(tag.name)
            noElmHandlers = false
        }
        if (noElmHandlers && defaultHandler != null) reportDefault(enc, b, s, next)
        tempPool.clear()
        freeBindings(tag.bindings)
        return XML_ERROR_NONE
    }

    /** doContent's end tag: checked against the open element, the end handler called, its bindings ended. */
    private fun endTag(enc: ExpatEnc, b: ByteArray, s: Int, next: Int, ev: XEvents): Int {
        val tag = tagStack!!
        val rawName = s + enc.minBytesPerChar * 2
        val len = enc.nameLength(b, rawName)
        if (len != tag.rawNameLength || !sameBytes(tag.rawName, b, rawName, len)) {
            ev.ptr = rawName
            return XML_ERROR_TAG_MISMATCH
        }
        tagStack = tag.parent
        tag.parent = freeTagList
        freeTagList = tag
        --tagLevel
        val ee = endElementHandler
        if (ee != null) ee(tag.name)
        else if (defaultHandler != null) reportDefault(enc, b, s, next)
        while (true) {
            val bd = tag.bindings ?: break
            val en = endNamespaceDeclHandler
            if (en != null) en(bd.prefix!!.name)
            tag.bindings = bd.nextTagBinding
            bd.nextTagBinding = freeBindingList
            freeBindingList = bd
            bd.prefix!!.binding = bd.prevPrefixBinding
        }
        return XML_ERROR_NONE
    }

    private fun sameBytes(a: ByteArray, b: ByteArray, off: Int, len: Int): Boolean {
        for (i in 0 until len) if (a[i] != b[off + i]) return false
        return true
    }

    /** freeBindings: [bindings] ended (to the free list), their end namespace handler called. */
    fun freeBindings(bindings0: XBinding?) {
        var bindings = bindings0
        while (bindings != null) {
            val b = bindings
            val en = endNamespaceDeclHandler
            if (en != null) en(b.prefix!!.name)
            bindings = bindings.nextTagBinding
            b.nextTagBinding = freeBindingList
            freeBindingList = b
            b.prefix!!.binding = b.prevPrefixBinding
        }
    }

    /**
     * storeAtts: the start tag at [attStr]'s attributes normalized, checked, defaulted and put in [appAtts]; namespace
     * declarations bound (into [tag]'s bindings); prefixed names and the element's name expanded.
     */
    private fun storeAtts(enc: ExpatEnc, b: ByteArray, attStr: Int, tag: XTag, account: Int): Int {
        val dtd = dtd
        var elementType = dtd.elementTypes[tag.name]
        if (elementType == null) {
            if (dtd.pool.copyString(tag.name) == null) return XML_ERROR_NO_MEMORY
            elementType = XElementType(tag.name)
            dtd.elementTypes[tag.name] = elementType
            if (ns && !setElementTypePrefix(elementType)) return XML_ERROR_NO_MEMORY
        }
        val nDefaultAtts = elementType.defaultAtts.size
        // (get the attributes from the tokenizer)
        val n = enc.getAtts(b, attStr, atts)
        if (n + nDefaultAtts > atts.size) {
            val old = atts.size
            val size = n + nDefaultAtts + Expat.INIT_ATTS_SIZE
            if (!trackAlloc((size - old) * 32L)) return XML_ERROR_NO_MEMORY
            atts = Array(size) { if (it < old) atts[it] else XAttribute() }
            if (n > old) enc.getAtts(b, attStr, atts)
        }
        if (appAtts.size < 2 * (n + nDefaultAtts) + 1) appAtts = arrayOfNulls(2 * (n + nDefaultAtts) + 1 + 2 * Expat.INIT_ATTS_SIZE)
        val appAtts = appAtts
        var attIndex = 0
        var nPrefixes = 0
        for (i in 0 until n) {
            val currAtt = atts[i]
            // (add the name and value to the attribute list)
            val attId = getAttributeId(enc, b, currAtt.name, currAtt.name + enc.nameLength(b, currAtt.name)) ?: return XML_ERROR_NO_MEMORY
            // (detect duplicate attributes by their QNames; namespaces are checked further down)
            if (attId.flag != 0) {
                if (enc === encoding) events.ptr = currAtt.name
                return XML_ERROR_DUPLICATE_ATTRIBUTE
            }
            attId.flag = 1
            appAtts[attIndex++] = attId.name
            if (!currAtt.normalized) {
                var isCdata = true
                // (figure out whether declared as other than CDATA)
                if (attId.maybeTokenized) {
                    for (da in elementType.defaultAtts) if (da.id === attId) {
                        isCdata = da.isCdata
                        break
                    }
                }
                val result = storeAttributeValue(enc, b, isCdata, currAtt.valuePtr, currAtt.valueEnd, tempPool, account)
                if (result != XML_ERROR_NONE) return result
                appAtts[attIndex] = tempPool.string()
                tempPool.finish()
            } else {
                // (the value did not need normalizing)
                appAtts[attIndex] = tempPool.storeString(enc, b, currAtt.valuePtr, currAtt.valueEnd) ?: return XML_ERROR_NO_MEMORY
                tempPool.finish()
            }
            // (handle prefixed attribute names)
            val prefix = attId.prefix
            if (prefix != null) {
                if (attId.xmlns) {
                    // (deal with namespace declarations here)
                    val result = addBinding(prefix, attId, appAtts[attIndex]!!, tag)
                    if (result != XML_ERROR_NONE) return result
                    --attIndex
                } else {
                    // (deal with other prefixed names later)
                    attIndex++
                    nPrefixes++
                    attId.flag = 2
                }
            } else attIndex++
        }
        // (set up for XML_GetSpecifiedAttributeCount and XML_GetIdAttributeIndex)
        nSpecifiedAtts = attIndex
        val idAtt = elementType.idAtt
        idAttIndex = -1
        if (idAtt != null && idAtt.flag != 0) {
            var i = 0
            while (i < attIndex) {
                if (appAtts[i] === idAtt.name) {
                    idAttIndex = i
                    break
                }
                i += 2
            }
        }
        // (do attribute defaulting)
        for (da in elementType.defaultAtts) {
            if (da.id.flag == 0 && da.value != null) {
                val p = da.id.prefix
                if (p != null) {
                    if (da.id.xmlns) {
                        val result = addBinding(p, da.id, da.value, tag)
                        if (result != XML_ERROR_NONE) return result
                    } else {
                        da.id.flag = 2
                        nPrefixes++
                        appAtts[attIndex++] = da.id.name
                        appAtts[attIndex++] = da.value
                    }
                } else {
                    da.id.flag = 1
                    appAtts[attIndex++] = da.id.name
                    appAtts[attIndex++] = da.value
                }
            }
        }
        appAtts[attIndex] = null
        // (expand prefixed attribute names, check for duplicates, and clear flags that say whether attributes were
        // specified)
        var i = 0
        if (nPrefixes != 0) {
            val seen = HashSet<String>()
            while (i < attIndex) {
                val s = appAtts[i]!!
                val id = dtd.attributeIds[s]!!
                if (id.flag == 2) {
                    id.flag = 0
                    val bd = id.prefix!!.binding ?: return XML_ERROR_UNBOUND_PREFIX
                    val local = s.substring(s.indexOf(':') + 1)
                    // (the expanded name: uri, then the local part; a duplicate of another's is an error)
                    val uriName = bd.uri + local
                    if (!seen.add(uriName)) return XML_ERROR_DUPLICATE_ATTRIBUTE
                    appAtts[i] = if (nsTriplets) bd.uri + local + Char(namespaceSeparator) + (bd.prefix!!.name ?: "") else uriName
                    if (--nPrefixes == 0) {
                        i += 2
                        break
                    }
                } else id.flag = 0
                i += 2
            }
        }
        // (clear flags for the remaining attributes)
        while (i < attIndex) {
            dtd.attributeIds[appAtts[i]!!]?.flag = 0
            i += 2
        }
        var bd = tag.bindings
        while (bd != null) {
            bd.attId!!.flag = 0
            bd = bd.nextTagBinding
        }
        if (!ns) return XML_ERROR_NONE
        // (expand the element type name)
        val binding: XBinding
        val localPart: String
        val ep = elementType.prefix
        if (ep != null) {
            binding = ep.binding ?: return XML_ERROR_UNBOUND_PREFIX
            localPart = tag.name.substring(tag.name.indexOf(':') + 1)
        } else if (dtd.defaultPrefix.binding != null) {
            binding = dtd.defaultPrefix.binding!!
            localPart = tag.name
        } else return XML_ERROR_NONE
        val bp = binding.prefix!!.name
        tag.localPart = localPart
        tag.prefix = bp
        val name = StringBuilder(binding.uri).append(localPart)
        // (we always have a namespace separator between localPart and prefix)
        if (nsTriplets && bp != null) name.append(Char(namespaceSeparator)).append(bp)
        val tagName = name.toString()
        if (tagName.length + Expat.EXPAND_SPARE > binding.uriAlloc) {
            if (!trackAlloc((tagName.length + Expat.EXPAND_SPARE - binding.uriAlloc).toLong())) return XML_ERROR_NO_MEMORY
            binding.uriAlloc = tagName.length + Expat.EXPAND_SPARE
        }
        tag.name = tagName
        return XML_ERROR_NONE
    }

    /** addBinding: [prefix] bound to [uri] (into [tag]'s bindings); the start namespace handler called. */
    fun addBinding(prefix: XPrefix, attId: XAttributeId?, uri: String, tag: XTag): Int {
        val r = addBindingTo(prefix, attId, uri) { b ->
            b.nextTagBinding = tag.bindings
            tag.bindings = b
        }
        return r
    }

    /** addBinding, the new binding given to [link] (a tag's bindings, or the inherited bindings). */
    fun addBindingTo(prefix: XPrefix, attId: XAttributeId?, uri: String, link: (XBinding) -> Unit): Int {
        val pn = prefix.name
        // (empty URI is only valid for default namespace per XML NS 1.0, not 1.1)
        if (uri.isEmpty() && pn != null) return XML_ERROR_UNDECLARING_PREFIX
        var mustBeXML = false
        if (pn != null && pn.startsWith("xml")) {
            // (not allowed to bind xmlns)
            if (pn == "xmlns") return XML_ERROR_RESERVED_PREFIX_XMLNS
            if (pn.length == 3) mustBeXML = true
        }
        var isXML = true
        var isXMLNS = true
        val len0 = uri.length
        for (len in 0 until len0) {
            val c = uri[len]
            if (isXML && (len >= XML_NAMESPACE.length || c != XML_NAMESPACE[len])) isXML = false
            if (!mustBeXML && isXMLNS && (len >= XMLNS_NAMESPACE.length || c != XMLNS_NAMESPACE[len])) isXMLNS = false
            // (a namespace URI must not hold the separator, unless the separator is a URI character, RFC 3986's)
            if (ns && c.code == namespaceSeparator && !isRfc3986UriChar(c)) return XML_ERROR_SYNTAX
        }
        isXML = isXML && len0 == XML_NAMESPACE.length
        isXMLNS = isXMLNS && len0 == XMLNS_NAMESPACE.length
        if (mustBeXML != isXML) return if (mustBeXML) XML_ERROR_RESERVED_PREFIX_XML else XML_ERROR_RESERVED_NAMESPACE_URI
        if (isXMLNS) return XML_ERROR_RESERVED_NAMESPACE_URI
        var len = len0
        if (namespaceSeparator != 0) len++
        val b = freeBindingList?.also {
            if (len > it.uriAlloc) {
                if (!trackAlloc((len + Expat.EXPAND_SPARE - it.uriAlloc).toLong())) return XML_ERROR_NO_MEMORY
                it.uriAlloc = len + Expat.EXPAND_SPARE
            }
            freeBindingList = it.nextTagBinding
        } ?: run {
            if (!trackAlloc(BINDING_STRUCT + 8 + len + Expat.EXPAND_SPARE + 8)) return XML_ERROR_NO_MEMORY
            XBinding().also { it.uriAlloc = len + Expat.EXPAND_SPARE }
        }
        b.uri = if (namespaceSeparator != 0) uri + Char(namespaceSeparator) else uri
        b.prefix = prefix
        b.attId = attId
        b.prevPrefixBinding = prefix.binding
        // (NULL binding when default namespace undeclared)
        prefix.binding = if (uri.isEmpty() && prefix === dtd.defaultPrefix) null else b
        link(b)
        // (if attId == NULL then we are not starting a namespace scope)
        val sn = startNamespaceDeclHandler
        if (attId != null && sn != null) sn(prefix.name, if (prefix.binding != null) uri else null)
        return XML_ERROR_NONE
    }

    // ------------------------------------------------------------------ CDATA and IGNORE sections

    private fun cdataSectionProcessor(start: Int, end: Int, endPtr: XPtr): Int {
        val sp = XPtr().also { it.p = start }
        val result = doCdataSection(encoding, buffer!!, sp, end, endPtr, !finalBuffer, XML_ACCOUNT_DIRECT)
        if (result != XML_ERROR_NONE) return result
        if (sp.p >= 0) {
            if (parentParser != null) {
                processor = PROC_EXTERNAL_ENTITY_CONTENT
                return externalEntityContentProcessor(sp.p, end, endPtr)
            }
            processor = PROC_CONTENT
            return contentProcessor(sp.p, end, endPtr)
        }
        return result
    }

    /** doCdataSection: [startPtr] set past the section where it closes, else to -1. */
    fun doCdataSection(enc: ExpatEnc, b: ByteArray, startPtr: XPtr, end: Int, nextPtr: XPtr, haveMore: Boolean, account: Int): Int {
        var s = startPtr.p
        val ev = eventsFor(enc)
        ev.ptr = s
        startPtr.p = -1
        val nt = XPtr()
        while (true) {
            nt.p = s
            val tok = enc.cdataSectionTok(b, s, end, nt)
            val next = nt.p
            if (!accountingDiffTolerated(tok, s, next, account)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
            ev.endPtr = next
            when (tok) {
                XML_TOK_CDATA_SECT_CLOSE -> {
                    val ec = endCdataSectionHandler
                    if (ec != null) ec()
                    else if (defaultHandler != null) reportDefault(enc, b, s, next)
                    startPtr.p = next
                    nextPtr.p = next
                    return if (parsing == XML_FINISHED) XML_ERROR_ABORTED else XML_ERROR_NONE
                }
                XML_TOK_DATA_NEWLINE -> {
                    val cd = characterDataHandler
                    if (cd != null) cd(NEWLINE, 0, 1)
                    else if (defaultHandler != null) reportDefault(enc, b, s, next)
                }
                XML_TOK_DATA_CHARS -> {
                    val cd = characterDataHandler
                    if (cd != null) {
                        if (!enc.isUtf8) {
                            val from = XPtr().also { it.p = s }
                            val to = XPtr()
                            while (true) {
                                to.p = 0
                                val r = enc.toUtf8(b, from, next, dataBuf, to, dataBuf.size)
                                ev.endPtr = next
                                cd(dataBuf, 0, to.p)
                                if (r == ExpatTok.XML_CONVERT_COMPLETED || r == ExpatTok.XML_CONVERT_INPUT_INCOMPLETE) break
                                ev.ptr = from.p
                            }
                            s = from.p
                        } else cd(b, s, next - s)
                    } else if (defaultHandler != null) reportDefault(enc, b, s, next)
                }
                XML_TOK_INVALID -> {
                    ev.ptr = next
                    return XML_ERROR_INVALID_TOKEN
                }
                XML_TOK_PARTIAL_CHAR -> {
                    if (haveMore) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    return XML_ERROR_PARTIAL_CHAR
                }
                XML_TOK_PARTIAL, XML_TOK_NONE -> {
                    if (haveMore) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    return XML_ERROR_UNCLOSED_CDATA_SECTION
                }
                else -> {
                    ev.ptr = next
                    return XML_ERROR_UNEXPECTED_STATE
                }
            }
            when (parsing) {
                XML_SUSPENDED -> {
                    ev.ptr = next
                    nextPtr.p = next
                    return XML_ERROR_NONE
                }
                XML_FINISHED -> {
                    ev.ptr = next
                    return XML_ERROR_ABORTED
                }
                XML_PARSING -> if (reenter) return XML_ERROR_UNEXPECTED_STATE
            }
            s = next
            ev.ptr = s
        }
    }

    private fun ignoreSectionProcessor(start: Int, end: Int, endPtr: XPtr): Int {
        val sp = XPtr().also { it.p = start }
        val result = doIgnoreSection(encoding, buffer!!, sp, end, endPtr, !finalBuffer)
        if (result != XML_ERROR_NONE) return result
        if (sp.p >= 0) {
            processor = PROC_PROLOG
            return prologProcessor(sp.p, end, endPtr)
        }
        return result
    }

    /** doIgnoreSection: [startPtr] set past the section where it closes, else to -1. */
    fun doIgnoreSection(enc: ExpatEnc, b: ByteArray, startPtr: XPtr, end: Int, nextPtr: XPtr, haveMore: Boolean): Int {
        val s = startPtr.p
        val nt = XPtr().also { it.p = s }
        val ev = eventsFor(enc)
        ev.ptr = s
        startPtr.p = -1
        val tok = enc.ignoreSectionTok(b, s, end, nt)
        val next = nt.p
        if (!accountingDiffTolerated(tok, s, next, XML_ACCOUNT_DIRECT)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
        ev.endPtr = next
        when (tok) {
            XML_TOK_IGNORE_SECT -> {
                if (defaultHandler != null) reportDefault(enc, b, s, next)
                startPtr.p = next
                nextPtr.p = next
                return if (parsing == XML_FINISHED) XML_ERROR_ABORTED else XML_ERROR_NONE
            }
            XML_TOK_INVALID -> {
                ev.ptr = next
                return XML_ERROR_INVALID_TOKEN
            }
            XML_TOK_PARTIAL_CHAR -> {
                if (haveMore) {
                    nextPtr.p = s
                    return XML_ERROR_NONE
                }
                return XML_ERROR_PARTIAL_CHAR
            }
            XML_TOK_PARTIAL, XML_TOK_NONE -> {
                if (haveMore) {
                    nextPtr.p = s
                    return XML_ERROR_NONE
                }
                // (XML_ERROR_UNCLOSED_IGNORE_SECTION)
                return XML_ERROR_SYNTAX
            }
            else -> {
                ev.ptr = next
                return XML_ERROR_UNEXPECTED_STATE
            }
        }
    }

    // ------------------------------------------------------------------ the epilog and entities

    fun epilogProcessor(s0: Int, end: Int, nextPtr: XPtr): Int {
        var s = s0
        val b = buffer!!
        processor = PROC_EPILOG
        events.ptr = s
        val nt = XPtr()
        while (true) {
            nt.p = -1
            val tok = encoding.prologTok(b, s, end, nt)
            val next = nt.p
            if (!accountingDiffTolerated(tok, s, next, XML_ACCOUNT_DIRECT)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
            events.endPtr = next
            when (tok) {
                // (report partial linebreak - it might be the last token)
                -XML_TOK_PROLOG_S -> {
                    if (defaultHandler != null) {
                        reportDefault(encoding, b, s, next)
                        if (parsing == XML_FINISHED) return XML_ERROR_ABORTED
                    }
                    nextPtr.p = next
                    return XML_ERROR_NONE
                }
                XML_TOK_NONE -> {
                    nextPtr.p = s
                    return XML_ERROR_NONE
                }
                XML_TOK_PROLOG_S -> if (defaultHandler != null) reportDefault(encoding, b, s, next)
                XML_TOK_PI -> if (!reportProcessingInstruction(encoding, b, s, next)) return XML_ERROR_NO_MEMORY
                XML_TOK_COMMENT -> if (!reportComment(encoding, b, s, next)) return XML_ERROR_NO_MEMORY
                XML_TOK_INVALID -> {
                    events.ptr = next
                    return XML_ERROR_INVALID_TOKEN
                }
                XML_TOK_PARTIAL -> {
                    if (!finalBuffer) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    return XML_ERROR_UNCLOSED_TOKEN
                }
                XML_TOK_PARTIAL_CHAR -> {
                    if (!finalBuffer) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    return XML_ERROR_PARTIAL_CHAR
                }
                else -> return XML_ERROR_JUNK_AFTER_DOC_ELEMENT
            }
            when (parsing) {
                XML_SUSPENDED -> {
                    events.ptr = next
                    nextPtr.p = next
                    return XML_ERROR_NONE
                }
                XML_FINISHED -> {
                    events.ptr = next
                    return XML_ERROR_ABORTED
                }
                XML_PARSING -> if (reenter) return XML_ERROR_UNEXPECTED_STATE
            }
            s = next
            events.ptr = s
        }
    }

    /** processEntity: [entity] opened (onto the internal, attribute or value list as [type] says). */
    fun processEntity(entity: XEntity, betweenDecl: Boolean, type: Int): Int {
        val openEntity: XOpenEntity
        when (type) {
            ENTITY_INTERNAL -> {
                processor = PROC_INTERNAL_ENTITY
                openEntity = freeInternalEntities?.also { freeInternalEntities = it.next } ?: newOpenEntity() ?: return XML_ERROR_NO_MEMORY
                openEntity.next = openInternalEntities
                openInternalEntities = openEntity
            }
            ENTITY_ATTRIBUTE -> {
                openEntity = freeAttributeEntities?.also { freeAttributeEntities = it.next } ?: newOpenEntity() ?: return XML_ERROR_NO_MEMORY
                openEntity.next = openAttributeEntities
                openAttributeEntities = openEntity
            }
            else -> {
                openEntity = freeValueEntities?.also { freeValueEntities = it.next } ?: newOpenEntity() ?: return XML_ERROR_NO_MEMORY
                openEntity.next = openValueEntities
                openValueEntities = openEntity
            }
        }
        entity.open = true
        entity.hasMore = true
        entity.processed = 0
        openEntity.entity = entity
        openEntity.type = type
        openEntity.startTagLevel = tagLevel
        openEntity.betweenDecl = betweenDecl
        openEntity.ptr = -1
        openEntity.endPtr = -1
        // (only internal entities make use of the reenter flag)
        if (type == ENTITY_INTERNAL) reenter = true
        return XML_ERROR_NONE
    }

    private fun newOpenEntity(): XOpenEntity? = if (trackAlloc(OPEN_ENTITY_STRUCT)) XOpenEntity() else null

    private fun internalEntityProcessor(): Int {
        val openEntity = openInternalEntities ?: return XML_ERROR_UNEXPECTED_STATE
        val entity = openEntity.entity!!
        if (entity.hasMore) {
            val text = entity.text!!
            val textStart = entity.processed
            val textEnd = text.size
            val next = XPtr().also { it.p = textStart }
            val result = if (entity.isParam) {
                val nt = XPtr().also { it.p = textStart }
                val tok = internalEncoding.prologTok(text, textStart, textEnd, nt)
                doProlog(internalEncoding, text, textStart, textEnd, tok, nt.p, next, false, false, XML_ACCOUNT_ENTITY_EXPANSION)
            } else doContent(openEntity.startTagLevel, internalEncoding, text, textStart, textEnd, next, false, XML_ACCOUNT_ENTITY_EXPANSION)
            if (result != XML_ERROR_NONE) return result
            // (the entity not complete: how much of it is processed)
            if (textEnd != next.p && (parsing == XML_SUSPENDED || (parsing == XML_PARSING && reenter))) {
                entity.processed = next.p
                return result
            }
            // (complete; it closes after its inner entities, which doProlog or doContent opened)
            entity.hasMore = false
            if (!entity.isParam && openEntity.startTagLevel != tagLevel) return XML_ERROR_ASYNC_ENTITY
            reenter = true
            return result
        }
        // (the fully processed entity off the list, its head)
        entity.open = false
        openInternalEntities = openEntity.next
        openEntity.next = freeInternalEntities
        freeInternalEntities = openEntity
        if (openInternalEntities == null) processor = if (entity.isParam) PROC_PROLOG else PROC_CONTENT
        reenter = true
        return XML_ERROR_NONE
    }

    // ------------------------------------------------------------------ attribute values

    /** storeAttributeValue: an attribute value [b][ptr, end) normalized into [pool], NUL-terminated. */
    fun storeAttributeValue(enc: ExpatEnc, b: ByteArray, isCdata: Boolean, ptr: Int, end: Int, pool: XPool, account: Int): Int {
        val next = XPtr().also { it.p = ptr }
        var result: Int
        while (true) {
            val openEntity = openAttributeEntities
            if (openEntity == null) {
                result = appendAttributeValue(enc, b, isCdata, next.p, end, pool, account, next)
            } else {
                val entity = openEntity.entity!!
                val text = entity.text!!
                val nextInEntity = XPtr().also { it.p = entity.processed }
                if (entity.hasMore) {
                    result = appendAttributeValue(internalEncoding, text, isCdata, entity.processed, text.size, pool, XML_ACCOUNT_ENTITY_EXPANSION, nextInEntity)
                    if (result != XML_ERROR_NONE) break
                    // (not complete: how much of it is processed; appendAttributeValue never suspends the parser)
                    if (text.size != nextInEntity.p) {
                        entity.processed = nextInEntity.p
                        continue
                    }
                    // (complete; it closes after its inner entities)
                    entity.hasMore = false
                    continue
                }
                entity.open = false
                openAttributeEntities = openEntity.next
                openEntity.next = freeAttributeEntities
                freeAttributeEntities = openEntity
                result = XML_ERROR_NONE
            }
            // (break if an error occurred or there is nothing left to process)
            if (result != XML_ERROR_NONE || (openAttributeEntities == null && end == next.p)) break
        }
        if (result != XML_ERROR_NONE) return result
        if (!isCdata && pool.length() != 0 && pool.lastChar() == 0x20) pool.chop()
        if (!pool.appendChar(0)) return XML_ERROR_NO_MEMORY
        return XML_ERROR_NONE
    }

    /** appendAttributeValue: [b][ptr, end) appended to [pool]; [nextPtr] where it stopped (an entity opened). */
    private fun appendAttributeValue(enc: ExpatEnc, b: ByteArray, isCdata: Boolean, ptr0: Int, end: Int, pool: XPool, account: Int, nextPtr: XPtr?): Int {
        var ptr = ptr0
        val nt = XPtr()
        val mb = enc.minBytesPerChar
        while (true) {
            nt.p = ptr
            val tok = enc.attributeValueTok(b, ptr, end, nt)
            var next = nt.p
            if (!accountingDiffTolerated(tok, ptr, next, account)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
            when (tok) {
                XML_TOK_NONE -> {
                    nextPtr?.p = next
                    return XML_ERROR_NONE
                }
                XML_TOK_INVALID -> {
                    if (enc === encoding) events.ptr = next
                    return XML_ERROR_INVALID_TOKEN
                }
                XML_TOK_PARTIAL -> {
                    if (enc === encoding) events.ptr = ptr
                    return XML_ERROR_INVALID_TOKEN
                }
                XML_TOK_CHAR_REF -> {
                    val n = enc.charRefNumber(b, ptr)
                    if (n < 0) {
                        if (enc === encoding) events.ptr = ptr
                        return XML_ERROR_BAD_CHAR_REF
                    }
                    if (!(!isCdata && n == 0x20 && (pool.length() == 0 || pool.lastChar() == 0x20))) {
                        val buf = ByteArray(4)
                        val k = ExpatTok.utf8Encode(n, buf, 0)
                        for (i in 0 until k) if (!pool.appendChar(buf[i].toInt())) return XML_ERROR_NO_MEMORY
                    }
                }
                XML_TOK_DATA_CHARS -> if (!pool.append(enc, b, ptr, next)) return XML_ERROR_NO_MEMORY
                ExpatTok.XML_TOK_TRAILING_CR, ExpatTok.XML_TOK_ATTRIBUTE_VALUE_S, XML_TOK_DATA_NEWLINE -> {
                    if (tok == ExpatTok.XML_TOK_TRAILING_CR) next = ptr + mb
                    if (!(!isCdata && (pool.length() == 0 || pool.lastChar() == 0x20))) {
                        if (!pool.appendChar(0x20)) return XML_ERROR_NO_MEMORY
                    }
                }
                XML_TOK_ENTITY_REF -> {
                    val ch = enc.predefinedEntityName(b, ptr + mb, next - mb)
                    if (ch != 0) {
                        // (4-6 characters for one: no amplification, recorded without protection)
                        accountingDiffTolerated(tok, 0, 1, XML_ACCOUNT_ENTITY_EXPANSION)
                        if (!pool.appendChar(ch)) return XML_ERROR_NO_MEMORY
                    } else {
                        val r = attributeEntityRef(enc, b, ptr, next, pool, nextPtr)
                        if (r != CONTINUE) return r
                    }
                }
                else -> {
                    if (enc === encoding) events.ptr = ptr
                    return XML_ERROR_UNEXPECTED_STATE
                }
            }
            ptr = next
        }
    }

    /** appendAttributeValue's entity reference [ptr, next): CONTINUE (skipped), or what it returns. */
    private fun attributeEntityRef(enc: ExpatEnc, b: ByteArray, ptr: Int, next: Int, pool: XPool, nextPtr: XPtr?): Int {
        val mb = enc.minBytesPerChar
        val name = temp2Pool.storeString(enc, b, ptr + mb, next - mb) ?: return XML_ERROR_NO_MEMORY
        val entity = dtd.generalEntities[name]
        temp2Pool.discard()
        // (first, determine if a check for an existing declaration is needed; if yes, check that the entity exists,
        // and that it is internal)
        val checkEntityDecl = if (pool === dtd.pool) {
            prologState.documentEntity && (if (dtd.standalone) openInternalEntities == null else !dtd.hasParamEntityRefs)
        } else !dtd.hasParamEntityRefs || dtd.standalone
        if (checkEntityDecl) {
            if (entity == null) return XML_ERROR_UNDEFINED_ENTITY
            else if (!entity.isInternal) return XML_ERROR_ENTITY_DECLARED_IN_PE
        } else if (entity == null) {
            // (a skipped entity cannot be reported here, nor the default handler called)
            return CONTINUE
        }
        entity!!
        if (entity.open) {
            if (enc === encoding) events.ptr = ptr
            return XML_ERROR_RECURSIVE_ENTITY_REF
        }
        if (entity.notation != null) {
            if (enc === encoding) events.ptr = ptr
            return XML_ERROR_BINARY_ENTITY_REF
        }
        if (entity.text == null) {
            if (enc === encoding) events.ptr = ptr
            return XML_ERROR_ATTRIBUTE_EXTERNAL_ENTITY_REF
        }
        val result = processEntity(entity, false, ENTITY_ATTRIBUTE)
        if (result == XML_ERROR_NONE) nextPtr?.p = next
        return result
    }

    // ------------------------------------------------------------------ reporting

    /** normalizeLines: CR and CR LF as LF. */
    private fun normalizeLines(s: String): String {
        if (s.indexOf('\r') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c == '\r') {
                sb.append('\n')
                if (i < s.length && s[i] == '\n') i++
            } else sb.append(c)
        }
        return sb.toString()
    }

    fun reportProcessingInstruction(enc: ExpatEnc, b: ByteArray, start0: Int, end: Int): Boolean {
        val pi = processingInstructionHandler
        if (pi == null) {
            if (defaultHandler != null) reportDefault(enc, b, start0, end)
            return true
        }
        val start = start0 + enc.minBytesPerChar * 2
        val tem = start + enc.nameLength(b, start)
        val target = tempPool.storeString(enc, b, start, tem) ?: return false
        tempPool.finish()
        val data = tempPool.storeString(enc, b, enc.skipS(b, tem), end - enc.minBytesPerChar * 2) ?: return false
        pi(target, normalizeLines(data))
        tempPool.clear()
        return true
    }

    fun reportComment(enc: ExpatEnc, b: ByteArray, start: Int, end: Int): Boolean {
        val ch = commentHandler
        if (ch == null) {
            if (defaultHandler != null) reportDefault(enc, b, start, end)
            return true
        }
        val data = tempPool.storeString(enc, b, start + enc.minBytesPerChar * 4, end - enc.minBytesPerChar * 3) ?: return false
        ch(normalizeLines(data))
        tempPool.clear()
        return true
    }

    /** reportDefault: [b][s, end) to the default handler (converted through the data buffer, in pieces). */
    fun reportDefault(enc: ExpatEnc, b: ByteArray, s: Int, end: Int) {
        var dh = defaultHandler ?: return
        if (!enc.isUtf8) {
            val ev = eventsFor(enc)
            val from = XPtr().also { it.p = s }
            val to = XPtr()
            do {
                to.p = 0
                val r = enc.toUtf8(b, from, end, dataBuf, to, dataBuf.size)
                ev.endPtr = from.p
                dh(dataBuf, 0, to.p)
                dh = defaultHandler ?: return
                ev.ptr = from.p
            } while (r != ExpatTok.XML_CONVERT_COMPLETED && r != ExpatTok.XML_CONVERT_INPUT_INCOMPLETE)
        } else dh(b, s, end - s)
    }

    // ------------------------------------------------------------------ accounting

    /** accountingGetCurrentAmplification */
    private fun currentAmplification(): Float {
        // (the length of the shortest include, "<!ENTITY a SYSTEM 'b'>")
        val lenOfShortestInclude = 22
        val out = countBytesDirect + countBytesIndirect
        return if (countBytesDirect != 0L) out.toFloat() / countBytesDirect.toFloat()
        else (lenOfShortestInclude + countBytesIndirect).toFloat() / lenOfShortestInclude.toFloat()
    }

    /** accountingDiffTolerated: [before, after)'s bytes counted (direct or indirect); false past the limit. */
    fun accountingDiffTolerated(tok: Int, before: Int, after: Int, account: Int): Boolean {
        when (tok) {
            XML_TOK_INVALID, XML_TOK_PARTIAL, XML_TOK_PARTIAL_CHAR, XML_TOK_NONE -> return true
        }
        if (account == XML_ACCOUNT_NONE) return true
        val root = rootParser()
        val isDirect = account == XML_ACCOUNT_DIRECT && this === root
        val more = (after - before).toLong()
        if (isDirect) root.countBytesDirect += more else root.countBytesIndirect += more
        val out = root.countBytesDirect + root.countBytesIndirect
        return java.lang.Long.compareUnsigned(out, root.activationThresholdBytes) < 0 ||
            root.currentAmplification() <= root.maximumAmplificationFactor
    }

    /**
     * expat_heap_increase_tolerable: whether the root parser may allocate [increase] more bytes. (NeonPy counts
     * Expat's allocations at its main sites with x86-64 sizes: [trackAlloc].)
     */
    fun allocTolerable(increase: Long): Boolean {
        val root = rootParser()
        val newTotal = root.bytesAllocated + increase
        if (java.lang.Long.compareUnsigned(newTotal, root.allocActivationThresholdBytes) >= 0) {
            val amplification = newTotal.toFloat() / root.countBytesDirect.toFloat()
            if (amplification > root.allocMaximumAmplificationFactor) return false
        }
        return true
    }

    /** expat_malloc's accounting of [size] bytes (and the 8 it adds), false where it is not tolerated. */
    fun trackAlloc(size: Long): Boolean {
        val n = size + 8
        if (!allocTolerable(n)) return false
        rootParser().bytesAllocated += n
        return true
    }

    companion object {
        const val PROC_PROLOG_INIT = 0
        const val PROC_PROLOG = 1
        const val PROC_CONTENT = 2
        const val PROC_CDATA_SECTION = 3
        const val PROC_IGNORE_SECTION = 4
        const val PROC_EXTERNAL_PAR_ENT_INIT = 5
        const val PROC_EXTERNAL_PAR_ENT = 6
        const val PROC_ENTITY_VALUE_INIT = 7
        const val PROC_ENTITY_VALUE = 8
        const val PROC_EPILOG = 9
        const val PROC_ERROR = 10
        const val PROC_EXTERNAL_ENTITY_INIT = 11
        const val PROC_EXTERNAL_ENTITY_INIT2 = 12
        const val PROC_EXTERNAL_ENTITY_INIT3 = 13
        const val PROC_EXTERNAL_ENTITY_CONTENT = 14
        const val PROC_INTERNAL_ENTITY = 15

        const val ENTITY_INTERNAL = 0
        const val ENTITY_ATTRIBUTE = 1
        const val ENTITY_VALUE = 2

        /** What appendAttributeValue's helper gives for a reference it skips. */
        private const val CONTINUE = -1

        /** sizeof's on x86-64 Linux, for the allocation tracker. */
        const val PARSER_STRUCT = 1024L
        const val DTD_STRUCT = 400L
        const val TAG_STRUCT = 80L
        const val BINDING_STRUCT = 48L
        const val OPEN_ENTITY_STRUCT = 48L

        @JvmField val NEWLINE = byteArrayOf(0xA)
        const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
        const val XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/"
        const val IMPLICIT_CONTEXT = "xml=http://www.w3.org/XML/1998/namespace"

        /** is_rfc3986_uri_char */
        @JvmStatic
        fun isRfc3986UriChar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "%-._~:/?#[]@!$&'()*+,;="
    }
}
