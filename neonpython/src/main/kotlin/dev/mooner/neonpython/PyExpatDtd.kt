package dev.mooner.neonpython

import dev.mooner.neonpython.Expat.XML_ACCOUNT_DIRECT
import dev.mooner.neonpython.Expat.XML_ACCOUNT_ENTITY_EXPANSION
import dev.mooner.neonpython.Expat.XML_ACCOUNT_NONE
import dev.mooner.neonpython.Expat.XML_ERROR_ABORTED
import dev.mooner.neonpython.Expat.XML_ERROR_AMPLIFICATION_LIMIT_BREACH
import dev.mooner.neonpython.Expat.XML_ERROR_BAD_CHAR_REF
import dev.mooner.neonpython.Expat.XML_ERROR_ENTITY_DECLARED_IN_PE
import dev.mooner.neonpython.Expat.XML_ERROR_EXTERNAL_ENTITY_HANDLING
import dev.mooner.neonpython.Expat.XML_ERROR_INCOMPLETE_PE
import dev.mooner.neonpython.Expat.XML_ERROR_INCORRECT_ENCODING
import dev.mooner.neonpython.Expat.XML_ERROR_INVALID_TOKEN
import dev.mooner.neonpython.Expat.XML_ERROR_MISPLACED_XML_PI
import dev.mooner.neonpython.Expat.XML_ERROR_NONE
import dev.mooner.neonpython.Expat.XML_ERROR_NOT_STANDALONE
import dev.mooner.neonpython.Expat.XML_ERROR_NO_ELEMENTS
import dev.mooner.neonpython.Expat.XML_ERROR_NO_MEMORY
import dev.mooner.neonpython.Expat.XML_ERROR_PARAM_ENTITY_REF
import dev.mooner.neonpython.Expat.XML_ERROR_PARTIAL_CHAR
import dev.mooner.neonpython.Expat.XML_ERROR_PUBLICID
import dev.mooner.neonpython.Expat.XML_ERROR_RECURSIVE_ENTITY_REF
import dev.mooner.neonpython.Expat.XML_ERROR_SYNTAX
import dev.mooner.neonpython.Expat.XML_ERROR_TEXT_DECL
import dev.mooner.neonpython.Expat.XML_ERROR_UNCLOSED_TOKEN
import dev.mooner.neonpython.Expat.XML_ERROR_UNDEFINED_ENTITY
import dev.mooner.neonpython.Expat.XML_ERROR_UNEXPECTED_STATE
import dev.mooner.neonpython.Expat.XML_ERROR_UNKNOWN_ENCODING
import dev.mooner.neonpython.Expat.XML_ERROR_XML_DECL
import dev.mooner.neonpython.Expat.XML_FINISHED
import dev.mooner.neonpython.Expat.XML_PARSING
import dev.mooner.neonpython.Expat.XML_SUSPENDED
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTLIST_ELEMENT_NAME
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTLIST_NONE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_ENUM_VALUE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_NAME
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_NOTATION_VALUE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_TYPE_CDATA
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_TYPE_ENTITIES
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_TYPE_ENTITY
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_TYPE_ID
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_TYPE_IDREF
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_TYPE_IDREFS
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_TYPE_NMTOKEN
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ATTRIBUTE_TYPE_NMTOKENS
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_COMMENT
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_CONTENT_ANY
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_CONTENT_ELEMENT
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_CONTENT_ELEMENT_OPT
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_CONTENT_ELEMENT_PLUS
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_CONTENT_ELEMENT_REP
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_CONTENT_EMPTY
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_CONTENT_PCDATA
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_DEFAULT_ATTRIBUTE_VALUE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_DOCTYPE_CLOSE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_DOCTYPE_INTERNAL_SUBSET
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_DOCTYPE_NAME
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_DOCTYPE_NONE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_DOCTYPE_PUBLIC_ID
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_DOCTYPE_SYSTEM_ID
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ELEMENT_NAME
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ELEMENT_NONE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ENTITY_COMPLETE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ENTITY_NONE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ENTITY_NOTATION_NAME
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ENTITY_PUBLIC_ID
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ENTITY_SYSTEM_ID
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ENTITY_VALUE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_ERROR
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_FIXED_ATTRIBUTE_VALUE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_GENERAL_ENTITY_NAME
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_GROUP_CHOICE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_GROUP_CLOSE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_GROUP_CLOSE_OPT
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_GROUP_CLOSE_PLUS
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_GROUP_CLOSE_REP
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_GROUP_OPEN
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_GROUP_SEQUENCE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_IGNORE_SECT
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_IMPLIED_ATTRIBUTE_VALUE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_INNER_PARAM_ENTITY_REF
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_INSTANCE_START
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_NONE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_NOTATION_NAME
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_NOTATION_NONE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_NOTATION_NO_SYSTEM_ID
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_NOTATION_PUBLIC_ID
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_NOTATION_SYSTEM_ID
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_PARAM_ENTITY_NAME
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_PARAM_ENTITY_REF
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_PI
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_REQUIRED_ATTRIBUTE_VALUE
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_TEXT_DECL
import dev.mooner.neonpython.ExpatProlog.Companion.XML_ROLE_XML_DECL
import dev.mooner.neonpython.ExpatTok.XML_TOK_BOM
import dev.mooner.neonpython.ExpatTok.XML_TOK_INSTANCE_START
import dev.mooner.neonpython.ExpatTok.XML_TOK_INVALID
import dev.mooner.neonpython.ExpatTok.XML_TOK_NONE
import dev.mooner.neonpython.ExpatTok.XML_TOK_PARAM_ENTITY_REF
import dev.mooner.neonpython.ExpatTok.XML_TOK_PARTIAL
import dev.mooner.neonpython.ExpatTok.XML_TOK_PARTIAL_CHAR
import dev.mooner.neonpython.ExpatTok.XML_TOK_PROLOG_S
import dev.mooner.neonpython.ExpatTok.XML_TOK_XML_DECL

// The prolog's side of xmlparse.c (Expat 2.8.1): the XML declaration, the encodings, doProlog, entity values, the
// DTD's tables, the namespace context, content models. Extensions of [ExpatParser].

/** What a doProlog role's helper gives: go on (the default handler called or not), or return. */
private const val R_DEFAULT = -1
private const val R_NO_DEFAULT = -2

/** The external subset's name "#". */
private const val EXTERNAL_SUBSET_NAME = "#"

/** initializeEncoding: the encoding given by name (or none) set up, an unknown one asked of the handler. */
fun ExpatParser.initializeEncoding(): Int {
    if (ExpatTok.initEncoding(initEncoding, protocolEncodingName)) {
        encoding = initEncoding
        return XML_ERROR_NONE
    }
    return handleUnknownEncoding(protocolEncodingName)
}

/** handleUnknownEncoding: the unknown encoding handler's map made an encoding, else XML_ERROR_UNKNOWN_ENCODING. */
fun ExpatParser.handleUnknownEncoding(name: String?): Int {
    val h = unknownEncodingHandler ?: return XML_ERROR_UNKNOWN_ENCODING
    val map = h(name ?: "") ?: return XML_ERROR_UNKNOWN_ENCODING
    if (!trackAlloc(UNKNOWN_ENCODING_STRUCT)) return XML_ERROR_NO_MEMORY
    val enc = ExpatTok.initUnknownEncoding(map, ns) ?: return XML_ERROR_UNKNOWN_ENCODING
    encoding = enc
    return XML_ERROR_NONE
}

private const val UNKNOWN_ENCODING_STRUCT = 2208L

/** processXmlDecl: the XML or text declaration [s, next) read; its handler called; its encoding taken. */
fun ExpatParser.processXmlDecl(isGeneralTextEntity: Boolean, s: Int, next: Int): Int {
    if (!accountingDiffTolerated(ExpatTok.XML_TOK_XML_DECL, s, next, XML_ACCOUNT_DIRECT)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
    val b = buffer!!
    val enc = encoding
    val d = ExpatTok.XmlDecl()
    if (!ExpatTok.parseXmlDecl(isGeneralTextEntity, enc, b, s, next, d, ns)) {
        events.ptr = d.badPtr
        return if (isGeneralTextEntity) XML_ERROR_TEXT_DECL else XML_ERROR_XML_DECL
    }
    if (!isGeneralTextEntity && d.standalone == 1) {
        dtd.standalone = true
        if (paramEntityParsing == Expat.XML_PARAM_ENTITY_PARSING_UNLESS_STANDALONE) paramEntityParsing = Expat.XML_PARAM_ENTITY_PARSING_NEVER
    }
    var storedEncName: String? = null
    var storedVersion: String? = null
    val xd = xmlDeclHandler
    if (xd != null) {
        if (d.encodingName >= 0) {
            storedEncName = temp2Pool.storeString(enc, b, d.encodingName, d.encodingName + enc.nameLength(b, d.encodingName)) ?: return XML_ERROR_NO_MEMORY
            temp2Pool.finish()
        }
        if (d.version >= 0) storedVersion = temp2Pool.storeString(enc, b, d.version, d.versionEnd - enc.minBytesPerChar) ?: return XML_ERROR_NO_MEMORY
        xd(storedVersion, storedEncName, d.standalone)
    } else if (defaultHandler != null) reportDefault(enc, b, s, next)
    if (protocolEncodingName == null) {
        val newEncoding = d.encoding
        if (newEncoding != null) {
            // (the declared encoding must not conflict with what was deduced: as many bytes a character, and for
            // UTF-16 the same byte order)
            if (newEncoding.minBytesPerChar != encoding.minBytesPerChar || (newEncoding.minBytesPerChar == 2 && newEncoding !== encoding)) {
                events.ptr = d.encodingName
                return XML_ERROR_INCORRECT_ENCODING
            }
            encoding = newEncoding
        } else if (d.encodingName >= 0) {
            if (storedEncName == null) {
                storedEncName = temp2Pool.storeString(enc, b, d.encodingName, d.encodingName + enc.nameLength(b, d.encodingName)) ?: return XML_ERROR_NO_MEMORY
            }
            val result = handleUnknownEncoding(storedEncName)
            temp2Pool.clear()
            if (result == XML_ERROR_UNKNOWN_ENCODING) events.ptr = d.encodingName
            return result
        }
    }
    if (storedEncName != null || storedVersion != null) temp2Pool.clear()
    return XML_ERROR_NONE
}

// ------------------------------------------------------------------ the external parameter entity processors

fun ExpatParser.externalParEntInitProcessor(s: Int, end: Int, nextPtr: XPtr): Int {
    val result = initializeEncoding()
    if (result != XML_ERROR_NONE) return result
    // (XML_Parse(Buffer) has been called: the external parameter entity is read)
    dtd.paramEntityRead = true
    if (prologState.inEntityValue) {
        processor = ExpatParser.PROC_ENTITY_VALUE_INIT
        return entityValueInitProcessor(s, end, nextPtr)
    }
    processor = ExpatParser.PROC_EXTERNAL_PAR_ENT
    return externalParEntProcessor(s, end, nextPtr)
}

fun ExpatParser.entityValueInitProcessor(s0: Int, end: Int, nextPtr: XPtr): Int {
    var s = s0
    var start = s
    val b = buffer!!
    val nt = XPtr().also { it.p = start }
    events.ptr = start
    while (true) {
        val tok = encoding.prologTok(b, start, end, nt)
        val next = nt.p
        events.endPtr = next
        if (tok <= 0) {
            if (!finalBuffer && tok != XML_TOK_INVALID) {
                nextPtr.p = s
                return XML_ERROR_NONE
            }
            when (tok) {
                XML_TOK_INVALID -> return XML_ERROR_INVALID_TOKEN
                XML_TOK_PARTIAL -> return XML_ERROR_UNCLOSED_TOKEN
                XML_TOK_PARTIAL_CHAR -> return XML_ERROR_PARTIAL_CHAR
            }
            // (found end of entity value - can store it now)
            return storeEntityValue(encoding, b, s, end, XML_ACCOUNT_DIRECT, null)
        } else if (tok == XML_TOK_XML_DECL) {
            val result = processXmlDecl(false, start, next)
            if (result != XML_ERROR_NONE) return result
            if (parsing == XML_FINISHED) return XML_ERROR_ABORTED
            nextPtr.p = next
            // (stop scanning for text declaration - we found one)
            processor = ExpatParser.PROC_ENTITY_VALUE
            return entityValueProcessor(next, end, nextPtr)
        } else if (tok == XML_TOK_BOM) {
            // (the BOM consumed: the encoding is set from it)
            if (!accountingDiffTolerated(tok, s, next, XML_ACCOUNT_DIRECT)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
            nextPtr.p = next
            s = next
        } else if (tok == XML_TOK_INSTANCE_START) {
            // (the start of what might be a normal tag, not a declaration: not legal in a DTD)
            nextPtr.p = next
            return XML_ERROR_SYNTAX
        }
        start = next
        events.ptr = start
    }
}

fun ExpatParser.externalParEntProcessor(s0: Int, end: Int, nextPtr: XPtr): Int {
    var s = s0
    val b = buffer!!
    val nt = XPtr().also { it.p = s }
    var tok = encoding.prologTok(b, s, end, nt)
    if (tok <= 0) {
        if (!finalBuffer && tok != XML_TOK_INVALID) {
            nextPtr.p = s
            return XML_ERROR_NONE
        }
        when (tok) {
            XML_TOK_INVALID -> return XML_ERROR_INVALID_TOKEN
            XML_TOK_PARTIAL -> return XML_ERROR_UNCLOSED_TOKEN
            XML_TOK_PARTIAL_CHAR -> return XML_ERROR_PARTIAL_CHAR
        }
    } else if (tok == XML_TOK_BOM) {
        // (an external subset's doProlog takes no BOM: skipped, and its bytes counted)
        if (!accountingDiffTolerated(tok, s, nt.p, XML_ACCOUNT_DIRECT)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
        s = nt.p
        tok = encoding.prologTok(b, s, end, nt)
    }
    processor = ExpatParser.PROC_PROLOG
    return doProlog(encoding, b, s, end, tok, nt.p, nextPtr, !finalBuffer, true, XML_ACCOUNT_DIRECT)
}

fun ExpatParser.entityValueProcessor(s: Int, end: Int, nextPtr: XPtr): Int {
    var start = s
    val b = buffer!!
    val enc = encoding
    val nt = XPtr().also { it.p = s }
    while (true) {
        val tok = enc.prologTok(b, start, end, nt)
        if (tok <= 0) {
            if (!finalBuffer && tok != XML_TOK_INVALID) {
                nextPtr.p = s
                return XML_ERROR_NONE
            }
            when (tok) {
                XML_TOK_INVALID -> return XML_ERROR_INVALID_TOKEN
                XML_TOK_PARTIAL -> return XML_ERROR_UNCLOSED_TOKEN
                XML_TOK_PARTIAL_CHAR -> return XML_ERROR_PARTIAL_CHAR
            }
            // (found end of entity value - can store it now)
            return storeEntityValue(enc, b, s, end, XML_ACCOUNT_DIRECT, null)
        } else if (tok == XML_TOK_INSTANCE_START) {
            nextPtr.p = nt.p
            return XML_ERROR_SYNTAX
        }
        start = nt.p
    }
}

// ------------------------------------------------------------------ doProlog

/** doProlog: the prolog (or DTD, or a parameter entity's text) [b][s, end) in [enc], its first token [tok] to [next]. */
fun ExpatParser.doProlog(enc0: ExpatEnc, b: ByteArray, s0: Int, end: Int, tok0: Int, next0: Int, nextPtr: XPtr, haveMore: Boolean,
                         allowClosingDoctype: Boolean, account: Int): Int {
    var enc = enc0
    var s = s0
    var tok = tok0
    var next = next0
    val ev = eventsFor(enc)
    val nt = XPtr()
    while (true) {
        var handleDefault = true
        ev.ptr = s
        ev.endPtr = next
        if (tok <= 0) {
            if (haveMore && tok != XML_TOK_INVALID) {
                nextPtr.p = s
                return XML_ERROR_NONE
            }
            when (tok) {
                XML_TOK_INVALID -> {
                    ev.ptr = next
                    return XML_ERROR_INVALID_TOKEN
                }
                XML_TOK_PARTIAL -> return XML_ERROR_UNCLOSED_TOKEN
                XML_TOK_PARTIAL_CHAR -> return XML_ERROR_PARTIAL_CHAR
                -XML_TOK_PROLOG_S -> tok = -tok
                XML_TOK_NONE -> {
                    // (for internal PE NOT referenced between declarations)
                    if (enc !== encoding && !openInternalEntities!!.betweenDecl) {
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    // (WFC: PE Between Declarations - a PE must contain complete markup)
                    if (isParamEntity || enc !== encoding) {
                        if (prologState.tokenRole(XML_TOK_NONE, b, end, end, enc) == XML_ROLE_ERROR) return XML_ERROR_INCOMPLETE_PE
                        nextPtr.p = s
                        return XML_ERROR_NONE
                    }
                    return XML_ERROR_NO_ELEMENTS
                }
                else -> {
                    tok = -tok
                    next = end
                }
            }
        }
        val role = prologState.tokenRole(tok, b, s, next, enc)
        when (role) {
            // (counted in contentProcessor or processXmlDecl)
            XML_ROLE_INSTANCE_START, XML_ROLE_XML_DECL, XML_ROLE_TEXT_DECL -> {}
            else -> if (!accountingDiffTolerated(tok, s, next, account)) return XML_ERROR_AMPLIFICATION_LIMIT_BREACH
        }
        when (role) {
            XML_ROLE_XML_DECL, XML_ROLE_TEXT_DECL -> {
                val result = processXmlDecl(role == XML_ROLE_TEXT_DECL, s, next)
                if (result != XML_ERROR_NONE) return result
                enc = encoding
                handleDefault = false
            }
            XML_ROLE_INSTANCE_START -> {
                instanceStart()?.let { return it }
                processor = ExpatParser.PROC_CONTENT
                return contentProcessor(s, end, nextPtr)
            }
            XML_ROLE_IGNORE_SECT -> {
                if (defaultHandler != null) reportDefault(enc, b, s, next)
                handleDefault = false
                nt.p = next
                val result = doIgnoreSection(enc, b, nt, end, nextPtr, haveMore)
                if (result != XML_ERROR_NONE) return result
                if (nt.p < 0) {
                    processor = ExpatParser.PROC_IGNORE_SECTION
                    return result
                }
                next = nt.p
            }
            else -> {
                val r = prologRole(role, tok, enc, b, s, next, ev, allowClosingDoctype)
                if (r >= 0) return r
                if (r == R_NO_DEFAULT) handleDefault = false
            }
        }
        if (handleDefault && defaultHandler != null) reportDefault(enc, b, s, next)
        when (parsing) {
            XML_SUSPENDED -> {
                nextPtr.p = next
                return XML_ERROR_NONE
            }
            XML_FINISHED -> return XML_ERROR_ABORTED
            XML_PARSING -> if (reenter) {
                nextPtr.p = next
                return XML_ERROR_NONE
            }
        }
        s = next
        nt.p = s
        tok = enc.prologTok(b, s, end, nt)
        next = nt.p
    }
}

/** XML_ROLE_INSTANCE_START: the last chance to read a foreign DTD; an error to return, or null. */
private fun ExpatParser.instanceStart(): Int? {
    if (useForeignDTD) {
        val hadParamEntityRefs = dtd.hasParamEntityRefs
        dtd.hasParamEntityRefs = true
        val ext = externalEntityRefHandler
        if (paramEntityParsing != 0 && ext != null) {
            val entity = dtd.paramEntities.getOrPut(EXTERNAL_SUBSET_NAME) { XEntity(EXTERNAL_SUBSET_NAME) }
            entity.base = curBase
            dtd.paramEntityRead = false
            if (ext(null, entity.base, entity.systemId, entity.publicId) == 0) return XML_ERROR_EXTERNAL_ENTITY_HANDLING
            if (dtd.paramEntityRead) {
                val ns = notStandaloneHandler
                if (!dtd.standalone && ns != null && ns() == 0) return XML_ERROR_NOT_STANDALONE
            } else dtd.hasParamEntityRefs = hadParamEntityRefs
        }
    }
    return null
}

/** doProlog's other roles: R_DEFAULT or R_NO_DEFAULT to go on, else an error to return. */
private fun ExpatParser.prologRole(role: Int, tok: Int, enc: ExpatEnc, b: ByteArray, s: Int, next: Int, ev: XEvents, allowClosingDoctype: Boolean): Int =
    when (role) {
        XML_ROLE_DOCTYPE_NAME, XML_ROLE_DOCTYPE_INTERNAL_SUBSET, XML_ROLE_DOCTYPE_PUBLIC_ID, XML_ROLE_ENTITY_PUBLIC_ID,
        XML_ROLE_DOCTYPE_CLOSE, XML_ROLE_DOCTYPE_SYSTEM_ID, XML_ROLE_ENTITY_SYSTEM_ID -> doctypeRole(role, enc, b, s, next, ev, allowClosingDoctype)
        XML_ROLE_ATTLIST_ELEMENT_NAME, XML_ROLE_ATTRIBUTE_NAME, XML_ROLE_ATTRIBUTE_TYPE_CDATA, XML_ROLE_ATTRIBUTE_TYPE_ID,
        XML_ROLE_ATTRIBUTE_TYPE_IDREF, XML_ROLE_ATTRIBUTE_TYPE_IDREFS, XML_ROLE_ATTRIBUTE_TYPE_ENTITY, XML_ROLE_ATTRIBUTE_TYPE_ENTITIES,
        XML_ROLE_ATTRIBUTE_TYPE_NMTOKEN, XML_ROLE_ATTRIBUTE_TYPE_NMTOKENS, XML_ROLE_ATTRIBUTE_ENUM_VALUE,
        XML_ROLE_ATTRIBUTE_NOTATION_VALUE -> attlistRole(role, enc, b, s, next)
        XML_ROLE_IMPLIED_ATTRIBUTE_VALUE, XML_ROLE_REQUIRED_ATTRIBUTE_VALUE, XML_ROLE_DEFAULT_ATTRIBUTE_VALUE,
        XML_ROLE_FIXED_ATTRIBUTE_VALUE -> attributeValueRole(role, enc, b, s, next, ev)
        XML_ROLE_ENTITY_VALUE, XML_ROLE_ENTITY_COMPLETE, XML_ROLE_ENTITY_NOTATION_NAME, XML_ROLE_GENERAL_ENTITY_NAME,
        XML_ROLE_PARAM_ENTITY_NAME -> entityRole(role, enc, b, s, next, ev)
        XML_ROLE_NOTATION_NAME, XML_ROLE_NOTATION_PUBLIC_ID, XML_ROLE_NOTATION_SYSTEM_ID,
        XML_ROLE_NOTATION_NO_SYSTEM_ID -> notationRole(role, enc, b, s, next, ev)
        XML_ROLE_ERROR -> when (tok) {
            // (PE references in the internal subset are not allowed within declarations)
            XML_TOK_PARAM_ENTITY_REF -> XML_ERROR_PARAM_ENTITY_REF
            XML_TOK_XML_DECL -> XML_ERROR_MISPLACED_XML_PI
            else -> XML_ERROR_SYNTAX
        }
        XML_ROLE_GROUP_OPEN, XML_ROLE_GROUP_SEQUENCE, XML_ROLE_GROUP_CHOICE -> groupRole(role)
        XML_ROLE_PARAM_ENTITY_REF, XML_ROLE_INNER_PARAM_ENTITY_REF -> paramEntityRefRole(role, enc, b, s, next)
        XML_ROLE_ELEMENT_NAME, XML_ROLE_CONTENT_ANY, XML_ROLE_CONTENT_EMPTY, XML_ROLE_CONTENT_PCDATA, XML_ROLE_CONTENT_ELEMENT,
        XML_ROLE_CONTENT_ELEMENT_OPT, XML_ROLE_CONTENT_ELEMENT_REP, XML_ROLE_CONTENT_ELEMENT_PLUS, XML_ROLE_GROUP_CLOSE,
        XML_ROLE_GROUP_CLOSE_OPT, XML_ROLE_GROUP_CLOSE_REP, XML_ROLE_GROUP_CLOSE_PLUS -> elementDeclRole(role, enc, b, s, next, ev)
        XML_ROLE_PI -> if (!reportProcessingInstruction(enc, b, s, next)) XML_ERROR_NO_MEMORY else R_NO_DEFAULT
        XML_ROLE_COMMENT -> if (!reportComment(enc, b, s, next)) XML_ERROR_NO_MEMORY else R_NO_DEFAULT
        XML_ROLE_NONE -> if (tok == XML_TOK_BOM) R_NO_DEFAULT else R_DEFAULT
        XML_ROLE_DOCTYPE_NONE -> if (startDoctypeDeclHandler != null) R_NO_DEFAULT else R_DEFAULT
        XML_ROLE_ENTITY_NONE -> if (dtd.keepProcessing && entityDeclHandler != null) R_NO_DEFAULT else R_DEFAULT
        XML_ROLE_NOTATION_NONE -> if (notationDeclHandler != null) R_NO_DEFAULT else R_DEFAULT
        XML_ROLE_ATTLIST_NONE -> if (dtd.keepProcessing && attlistDeclHandler != null) R_NO_DEFAULT else R_DEFAULT
        XML_ROLE_ELEMENT_NONE -> if (elementDeclHandler != null) R_NO_DEFAULT else R_DEFAULT
        else -> R_DEFAULT
    }

/** A literal's text, its quotes left out. */
private fun ExpatParser.literal(pool: XPool, enc: ExpatEnc, b: ByteArray, s: Int, next: Int): String? =
    pool.storeString(enc, b, s + enc.minBytesPerChar, next - enc.minBytesPerChar)

/** The DOCTYPE's roles, and an entity's public and system ids. */
private fun ExpatParser.doctypeRole(role: Int, enc: ExpatEnc, b: ByteArray, s: Int, next: Int, ev: XEvents, allowClosingDoctype: Boolean): Int {
    var handleDefault = true
    when (role) {
        XML_ROLE_DOCTYPE_NAME -> {
            if (startDoctypeDeclHandler != null) {
                doctypeName = tempPool.storeString(enc, b, s, next) ?: return XML_ERROR_NO_MEMORY
                tempPool.finish()
                doctypePubid = null
                handleDefault = false
            }
            // (always initialize to NULL)
            doctypeSysid = null
        }
        XML_ROLE_DOCTYPE_INTERNAL_SUBSET -> {
            val h = startDoctypeDeclHandler
            if (h != null) {
                h(doctypeName, doctypeSysid, doctypePubid, 1)
                doctypeName = null
                tempPool.clear()
                handleDefault = false
            }
        }
        XML_ROLE_DOCTYPE_PUBLIC_ID, XML_ROLE_ENTITY_PUBLIC_ID -> {
            var checked = false
            if (role == XML_ROLE_DOCTYPE_PUBLIC_ID) {
                useForeignDTD = false
                declEntity = dtd.paramEntities.getOrPut(EXTERNAL_SUBSET_NAME) { XEntity(EXTERNAL_SUBSET_NAME) }
                dtd.hasParamEntityRefs = true
                if (startDoctypeDeclHandler != null) {
                    val bad = XPtr()
                    if (!enc.isPublicId(b, s, next, bad)) {
                        ev.ptr = bad.p
                        return XML_ERROR_PUBLICID
                    }
                    doctypePubid = normalizePublicId(literal(tempPool, enc, b, s, next) ?: return XML_ERROR_NO_MEMORY)
                    tempPool.finish()
                    handleDefault = false
                    checked = true
                }
            }
            if (!checked) {
                val bad = XPtr()
                if (!enc.isPublicId(b, s, next, bad)) {
                    ev.ptr = bad.p
                    return XML_ERROR_PUBLICID
                }
            }
            val de = declEntity
            if (dtd.keepProcessing && de != null) {
                de.publicId = normalizePublicId(literal(dtd.pool, enc, b, s, next) ?: return XML_ERROR_NO_MEMORY)
                dtd.pool.finish()
                // (don't suppress the default handler if we fell through from the XML_ROLE_DOCTYPE_PUBLIC_ID case)
                if (entityDeclHandler != null && role == XML_ROLE_ENTITY_PUBLIC_ID) handleDefault = false
            }
        }
        XML_ROLE_DOCTYPE_CLOSE -> {
            // (must not close doctype from within expanded parameter entities)
            if (!allowClosingDoctype) return XML_ERROR_INVALID_TOKEN
            if (doctypeName != null) {
                startDoctypeDeclHandler!!(doctypeName, doctypeSysid, doctypePubid, 0)
                tempPool.clear()
                handleDefault = false
            }
            // (doctypeSysid is non-NULL after a system id, even without a start handler: an external subset)
            if (doctypeSysid != null || useForeignDTD) {
                val r = readExternalSubset()
                if (r != XML_ERROR_NONE) return r
            }
            val h = endDoctypeDeclHandler
            if (h != null) {
                h()
                handleDefault = false
            }
        }
        XML_ROLE_DOCTYPE_SYSTEM_ID, XML_ROLE_ENTITY_SYSTEM_ID -> {
            if (role == XML_ROLE_DOCTYPE_SYSTEM_ID) {
                useForeignDTD = false
                dtd.hasParamEntityRefs = true
                if (startDoctypeDeclHandler != null) {
                    doctypeSysid = literal(tempPool, enc, b, s, next) ?: return XML_ERROR_NO_MEMORY
                    tempPool.finish()
                    handleDefault = false
                } else {
                    // ("#", so that doctypeSysid is non-NULL without a start handler)
                    doctypeSysid = EXTERNAL_SUBSET_NAME
                }
                val nsh = notStandaloneHandler
                if (!dtd.standalone && paramEntityParsing == 0 && nsh != null && nsh() == 0) return XML_ERROR_NOT_STANDALONE
                if (declEntity == null) {
                    val e = dtd.paramEntities.getOrPut(EXTERNAL_SUBSET_NAME) { XEntity(EXTERNAL_SUBSET_NAME) }
                    e.publicId = null
                    declEntity = e
                }
            }
            val de = declEntity
            if (dtd.keepProcessing && de != null) {
                de.systemId = literal(dtd.pool, enc, b, s, next) ?: return XML_ERROR_NO_MEMORY
                de.base = curBase
                dtd.pool.finish()
                // (don't suppress the default handler if we fell through from the XML_ROLE_DOCTYPE_SYSTEM_ID case)
                if (entityDeclHandler != null && role == XML_ROLE_ENTITY_SYSTEM_ID) handleDefault = false
            }
        }
    }
    return if (handleDefault) R_DEFAULT else R_NO_DEFAULT
}

/** XML_ROLE_DOCTYPE_CLOSE's external subset (or foreign DTD) read through the external entity handler. */
private fun ExpatParser.readExternalSubset(): Int {
    val hadParamEntityRefs = dtd.hasParamEntityRefs
    dtd.hasParamEntityRefs = true
    val ext = externalEntityRefHandler
    if (paramEntityParsing != 0 && ext != null) {
        val entity = dtd.paramEntities.getOrPut(EXTERNAL_SUBSET_NAME) { XEntity(EXTERNAL_SUBSET_NAME) }
        if (useForeignDTD) entity.base = curBase
        dtd.paramEntityRead = false
        if (ext(null, entity.base, entity.systemId, entity.publicId) == 0) return XML_ERROR_EXTERNAL_ENTITY_HANDLING
        if (dtd.paramEntityRead) {
            val nsh = notStandaloneHandler
            if (!dtd.standalone && nsh != null && nsh() == 0) return XML_ERROR_NOT_STANDALONE
        } else if (doctypeSysid == null) {
            // (the foreign DTD not read: there is no external subset)
            dtd.hasParamEntityRefs = hadParamEntityRefs
        }
    }
    useForeignDTD = false
    return XML_ERROR_NONE
}

/** The ATTLIST declaration's names and types. */
private fun ExpatParser.attlistRole(role: Int, enc: ExpatEnc, b: ByteArray, s: Int, next: Int): Int {
    when (role) {
        XML_ROLE_ATTLIST_ELEMENT_NAME -> declElementType = getElementType(enc, b, s, next) ?: return XML_ERROR_NO_MEMORY
        XML_ROLE_ATTRIBUTE_NAME -> {
            declAttributeId = getAttributeId(enc, b, s, next) ?: return XML_ERROR_NO_MEMORY
            declAttributeIsCdata = false
            declAttributeType = null
            declAttributeIsId = false
        }
        XML_ROLE_ATTRIBUTE_TYPE_CDATA -> {
            declAttributeIsCdata = true
            declAttributeType = "CDATA"
        }
        XML_ROLE_ATTRIBUTE_TYPE_ID -> {
            declAttributeIsId = true
            declAttributeType = "ID"
        }
        XML_ROLE_ATTRIBUTE_TYPE_IDREF -> declAttributeType = "IDREF"
        XML_ROLE_ATTRIBUTE_TYPE_IDREFS -> declAttributeType = "IDREFS"
        XML_ROLE_ATTRIBUTE_TYPE_ENTITY -> declAttributeType = "ENTITY"
        XML_ROLE_ATTRIBUTE_TYPE_ENTITIES -> declAttributeType = "ENTITIES"
        XML_ROLE_ATTRIBUTE_TYPE_NMTOKEN -> declAttributeType = "NMTOKEN"
        XML_ROLE_ATTRIBUTE_TYPE_NMTOKENS -> declAttributeType = "NMTOKENS"
        else -> {
            // (XML_ROLE_ATTRIBUTE_ENUM_VALUE, XML_ROLE_ATTRIBUTE_NOTATION_VALUE: the type built in the temp pool)
            if (dtd.keepProcessing && attlistDeclHandler != null) {
                val prefix = if (declAttributeType != null) "|" else if (role == XML_ROLE_ATTRIBUTE_NOTATION_VALUE) "NOTATION(" else "("
                if (!tempPool.appendString(prefix)) return XML_ERROR_NO_MEMORY
                if (!tempPool.append(enc, b, s, next)) return XML_ERROR_NO_MEMORY
                declAttributeType = tempPool.string()
                return R_NO_DEFAULT
            }
            return R_DEFAULT
        }
    }
    // (checkAttListDeclHandler)
    return if (dtd.keepProcessing && attlistDeclHandler != null) R_NO_DEFAULT else R_DEFAULT
}

/** An attribute's default: defined, the ATTLIST handler called. */
private fun ExpatParser.attributeValueRole(role: Int, enc: ExpatEnc, b: ByteArray, s: Int, next: Int, ev: XEvents): Int {
    var handleDefault = true
    if (role == XML_ROLE_IMPLIED_ATTRIBUTE_VALUE || role == XML_ROLE_REQUIRED_ATTRIBUTE_VALUE) {
        if (dtd.keepProcessing) {
            if (!defineAttribute(declElementType!!, declAttributeId!!, declAttributeIsCdata, declAttributeIsId, null)) return XML_ERROR_NO_MEMORY
            val h = attlistDeclHandler
            if (h != null && declAttributeType != null) {
                if (!finishEnumeratedType()) return XML_ERROR_NO_MEMORY
                ev.endPtr = s
                h(declElementType!!.name, declAttributeId!!.name, declAttributeType, null, if (role == XML_ROLE_REQUIRED_ATTRIBUTE_VALUE) 1 else 0)
                handleDefault = false
            }
        }
        tempPool.clear()
        return if (handleDefault) R_DEFAULT else R_NO_DEFAULT
    }
    // (XML_ROLE_DEFAULT_ATTRIBUTE_VALUE, XML_ROLE_FIXED_ATTRIBUTE_VALUE)
    if (dtd.keepProcessing) {
        val mb = enc.minBytesPerChar
        val result = storeAttributeValue(enc, b, declAttributeIsCdata, s + mb, next - mb, dtd.pool, XML_ACCOUNT_NONE)
        if (result != XML_ERROR_NONE) return result
        val attVal = dtd.pool.string()
        dtd.pool.finish()
        // (ID attributes aren't allowed to have a default)
        if (!defineAttribute(declElementType!!, declAttributeId!!, declAttributeIsCdata, false, attVal)) return XML_ERROR_NO_MEMORY
        val h = attlistDeclHandler
        if (h != null && declAttributeType != null) {
            if (!finishEnumeratedType()) return XML_ERROR_NO_MEMORY
            ev.endPtr = s
            h(declElementType!!.name, declAttributeId!!.name, declAttributeType, attVal, if (role == XML_ROLE_FIXED_ATTRIBUTE_VALUE) 1 else 0)
            tempPool.clear()
            handleDefault = false
        }
    }
    return if (handleDefault) R_DEFAULT else R_NO_DEFAULT
}

/** An enumerated or NOTATION type's closing parenthesis added. */
private fun ExpatParser.finishEnumeratedType(): Boolean {
    val t = declAttributeType!!
    if (t.startsWith("(") || t.startsWith("NO")) {
        if (!tempPool.appendChar(')'.code) || !tempPool.appendChar(0)) return false
        declAttributeType = tempPool.string()
        tempPool.finish()
    }
    return true
}

/** The ENTITY declaration's roles. */
private fun ExpatParser.entityRole(role: Int, enc: ExpatEnc, b: ByteArray, s: Int, next: Int, ev: XEvents): Int {
    var handleDefault = true
    val mb = enc.minBytesPerChar
    when (role) {
        XML_ROLE_ENTITY_VALUE -> if (dtd.keepProcessing) {
            // (the replacement text stored in declEntity's text)
            val result = callStoreEntityValue(enc, b, s + mb, next - mb, XML_ACCOUNT_NONE)
            val de = declEntity
            if (de != null) {
                de.text = dtd.entityValuePool.bytes()
                dtd.entityValuePool.finish()
                val h = entityDeclHandler
                if (h != null) {
                    ev.endPtr = s
                    h(de.name, if (de.isParam) 1 else 0, de.text, curBase, null, null, null)
                    handleDefault = false
                }
            } else dtd.entityValuePool.discard()
            if (result != XML_ERROR_NONE) return result
        }
        XML_ROLE_ENTITY_COMPLETE -> {
            val de = declEntity
            val h = entityDeclHandler
            if (dtd.keepProcessing && de != null && h != null) {
                ev.endPtr = s
                h(de.name, if (de.isParam) 1 else 0, null, de.base, de.systemId, de.publicId, null)
                handleDefault = false
            }
        }
        XML_ROLE_ENTITY_NOTATION_NAME -> {
            val de = declEntity
            if (dtd.keepProcessing && de != null) {
                de.notation = dtd.pool.storeString(enc, b, s, next) ?: return XML_ERROR_NO_MEMORY
                dtd.pool.finish()
                val uh = unparsedEntityDeclHandler
                val eh = entityDeclHandler
                if (uh != null) {
                    ev.endPtr = s
                    uh(de.name, de.base, de.systemId, de.publicId, de.notation)
                    handleDefault = false
                } else if (eh != null) {
                    ev.endPtr = s
                    eh(de.name, 0, null, de.base, de.systemId, de.publicId, de.notation)
                    handleDefault = false
                }
            }
        }
        XML_ROLE_GENERAL_ENTITY_NAME -> {
            if (enc.predefinedEntityName(b, s, next) != 0) {
                declEntity = null
                return R_DEFAULT
            }
            if (!declareEntity(dtd.generalEntities, false, enc, b, s, next)) return XML_ERROR_NO_MEMORY
            if (declEntity != null && entityDeclHandler != null) handleDefault = false
        }
        XML_ROLE_PARAM_ENTITY_NAME -> {
            if (!declareEntity(dtd.paramEntities, true, enc, b, s, next)) return XML_ERROR_NO_MEMORY
            if (declEntity != null && entityDeclHandler != null) handleDefault = false
        }
    }
    return if (handleDefault) R_DEFAULT else R_NO_DEFAULT
}

/** An entity's name declared in [table]: declEntity the new entity, or null (declared before, or not processing). */
private fun ExpatParser.declareEntity(table: LinkedHashMap<String, XEntity>, isParam: Boolean, enc: ExpatEnc, b: ByteArray, s: Int, next: Int): Boolean {
    if (!dtd.keepProcessing) {
        dtd.pool.discard()
        declEntity = null
        return true
    }
    val name = dtd.pool.storeString(enc, b, s, next) ?: return false
    if (table.containsKey(name)) {
        dtd.pool.discard()
        declEntity = null
        return true
    }
    if (table.isEmpty() && !trackAlloc(64 * 8L)) return false
    if (!trackAlloc(ENTITY_STRUCT)) return false
    val e = XEntity(name)
    table[name] = e
    dtd.pool.finish()
    e.publicId = null
    e.isParam = isParam
    // (with a parent parser, or reading an internal parameter entity, the declaration is not "internal")
    e.isInternal = !(parentParser != null || openInternalEntities != null)
    declEntity = e
    return true
}

private const val ENTITY_STRUCT = 72L

/** The NOTATION declaration's roles. */
private fun ExpatParser.notationRole(role: Int, enc: ExpatEnc, b: ByteArray, s: Int, next: Int, ev: XEvents): Int {
    var handleDefault = true
    when (role) {
        XML_ROLE_NOTATION_NAME -> {
            declNotationPublicId = null
            declNotationName = null
            if (notationDeclHandler != null) {
                declNotationName = tempPool.storeString(enc, b, s, next) ?: return XML_ERROR_NO_MEMORY
                tempPool.finish()
                handleDefault = false
            }
        }
        XML_ROLE_NOTATION_PUBLIC_ID -> {
            val bad = XPtr()
            if (!enc.isPublicId(b, s, next, bad)) {
                ev.ptr = bad.p
                return XML_ERROR_PUBLICID
            }
            // (declNotationName set means a notation handler)
            if (declNotationName != null) {
                declNotationPublicId = normalizePublicId(literal(tempPool, enc, b, s, next) ?: return XML_ERROR_NO_MEMORY)
                tempPool.finish()
                handleDefault = false
            }
        }
        XML_ROLE_NOTATION_SYSTEM_ID -> {
            val h = notationDeclHandler
            if (declNotationName != null && h != null) {
                val systemId = literal(tempPool, enc, b, s, next) ?: return XML_ERROR_NO_MEMORY
                ev.endPtr = s
                h(declNotationName, curBase, systemId, declNotationPublicId)
                handleDefault = false
            }
            tempPool.clear()
        }
        XML_ROLE_NOTATION_NO_SYSTEM_ID -> {
            val h = notationDeclHandler
            if (declNotationPublicId != null && h != null) {
                ev.endPtr = s
                h(declNotationName, curBase, null, declNotationPublicId)
                handleDefault = false
            }
            tempPool.clear()
        }
    }
    return if (handleDefault) R_DEFAULT else R_NO_DEFAULT
}

/** A content model group opened, or its connector (',' or '|') seen. */
private fun ExpatParser.groupRole(role: Int): Int {
    val level = prologState.level
    when (role) {
        XML_ROLE_GROUP_OPEN -> {
            if (level >= groupConnector.size) {
                val size = if (groupConnector.isEmpty()) 32 else groupConnector.size * 2
                if (!trackAlloc((size - groupConnector.size).toLong())) return XML_ERROR_NO_MEMORY
                groupConnector = groupConnector.copyOf(size)
                val si = dtd.scaffIndex
                if (si != null && si.size < size) dtd.scaffIndex = si.copyOf(size)
            }
            groupConnector[level] = 0
            if (dtd.inEldecl) {
                val myindex = nextScaffoldPart()
                if (myindex < 0) return XML_ERROR_NO_MEMORY
                dtd.scaffIndex!![dtd.scaffLevel] = myindex
                dtd.scaffLevel++
                dtd.scaffold[myindex]!!.type = Expat.XML_CTYPE_SEQ
                if (elementDeclHandler != null) return R_NO_DEFAULT
            }
        }
        XML_ROLE_GROUP_SEQUENCE -> {
            if (groupConnector[level] == '|'.code.toByte()) return XML_ERROR_SYNTAX
            groupConnector[level] = ','.code.toByte()
            if (dtd.inEldecl && elementDeclHandler != null) return R_NO_DEFAULT
        }
        else -> {
            if (groupConnector[level] == ','.code.toByte()) return XML_ERROR_SYNTAX
            var handleDefault = true
            if (dtd.inEldecl && groupConnector[level].toInt() == 0) {
                val parent = dtd.scaffold[dtd.scaffIndex!![dtd.scaffLevel - 1]]!!
                if (parent.type != Expat.XML_CTYPE_MIXED) {
                    parent.type = Expat.XML_CTYPE_CHOICE
                    if (elementDeclHandler != null) handleDefault = false
                }
            }
            groupConnector[level] = '|'.code.toByte()
            return if (handleDefault) R_DEFAULT else R_NO_DEFAULT
        }
    }
    return R_DEFAULT
}

/** A parameter entity reference in the DTD: expanded (internal), read (external), or skipped. */
private fun ExpatParser.paramEntityRefRole(role: Int, enc: ExpatEnc, b: ByteArray, s: Int, next: Int): Int {
    dtd.hasParamEntityRefs = true
    if (paramEntityParsing == 0) dtd.keepProcessing = dtd.standalone
    else {
        val mb = enc.minBytesPerChar
        val name = dtd.pool.storeString(enc, b, s + mb, next - mb) ?: return XML_ERROR_NO_MEMORY
        val entity = dtd.paramEntities[name]
        dtd.pool.discard()
        // (first, determine if a check for an existing declaration is needed; if yes, check that the entity exists,
        // and that it is internal, otherwise call the skipped entity handler)
        if (prologState.documentEntity && (if (dtd.standalone) openInternalEntities == null else !dtd.hasParamEntityRefs)) {
            if (entity == null) return XML_ERROR_UNDEFINED_ENTITY
            else if (!entity.isInternal) return XML_ERROR_ENTITY_DECLARED_IN_PE
        } else if (entity == null) {
            dtd.keepProcessing = dtd.standalone
            // (cannot report skipped entities in declarations)
            val sk = skippedEntityHandler
            if (role == XML_ROLE_PARAM_ENTITY_REF && sk != null) {
                sk(name, 1)
                return R_NO_DEFAULT
            }
            return R_DEFAULT
        }
        entity!!
        if (entity.open) return XML_ERROR_RECURSIVE_ENTITY_REF
        if (entity.text != null) {
            val result = processEntity(entity, role == XML_ROLE_PARAM_ENTITY_REF, ExpatParser.ENTITY_INTERNAL)
            if (result != XML_ERROR_NONE) return result
            return R_NO_DEFAULT
        }
        val ext = externalEntityRefHandler
        if (ext != null) {
            dtd.paramEntityRead = false
            entity.open = true
            if (ext(null, entity.base, entity.systemId, entity.publicId) == 0) {
                entity.open = false
                return XML_ERROR_EXTERNAL_ENTITY_HANDLING
            }
            entity.open = false
            if (!dtd.paramEntityRead) {
                dtd.keepProcessing = dtd.standalone
                return R_NO_DEFAULT
            }
            val nsh = notStandaloneHandler
            if (!dtd.standalone && nsh != null && nsh() == 0) return XML_ERROR_NOT_STANDALONE
            return R_NO_DEFAULT
        } else {
            dtd.keepProcessing = dtd.standalone
            return R_DEFAULT
        }
    }
    val nsh = notStandaloneHandler
    if (!dtd.standalone && nsh != null && nsh() == 0) return XML_ERROR_NOT_STANDALONE
    return R_DEFAULT
}

/** The ELEMENT declaration's roles: its content model scaffolded, then built and given to its handler. */
private fun ExpatParser.elementDeclRole(role: Int, enc: ExpatEnc, b: ByteArray, s: Int, next: Int, ev: XEvents): Int {
    when (role) {
        XML_ROLE_ELEMENT_NAME -> {
            if (elementDeclHandler != null) {
                declElementType = getElementType(enc, b, s, next) ?: return XML_ERROR_NO_MEMORY
                dtd.scaffLevel = 0
                dtd.scaffCount = 0
                dtd.inEldecl = true
                return R_NO_DEFAULT
            }
        }
        XML_ROLE_CONTENT_ANY, XML_ROLE_CONTENT_EMPTY -> {
            if (dtd.inEldecl) {
                var handleDefault = true
                val h = elementDeclHandler
                if (h != null) {
                    val content = XContent(if (role == XML_ROLE_CONTENT_ANY) Expat.XML_CTYPE_ANY else Expat.XML_CTYPE_EMPTY, Expat.XML_CQUANT_NONE, null, arrayOf())
                    ev.endPtr = s
                    h(declElementType!!.name, content)
                    handleDefault = false
                }
                dtd.inEldecl = false
                return if (handleDefault) R_DEFAULT else R_NO_DEFAULT
            }
        }
        XML_ROLE_CONTENT_PCDATA -> {
            if (dtd.inEldecl) {
                dtd.scaffold[dtd.scaffIndex!![dtd.scaffLevel - 1]]!!.type = Expat.XML_CTYPE_MIXED
                if (elementDeclHandler != null) return R_NO_DEFAULT
            }
        }
        XML_ROLE_CONTENT_ELEMENT, XML_ROLE_CONTENT_ELEMENT_OPT, XML_ROLE_CONTENT_ELEMENT_REP, XML_ROLE_CONTENT_ELEMENT_PLUS -> {
            if (dtd.inEldecl) {
                val quant = when (role) {
                    XML_ROLE_CONTENT_ELEMENT -> Expat.XML_CQUANT_NONE
                    XML_ROLE_CONTENT_ELEMENT_OPT -> Expat.XML_CQUANT_OPT
                    XML_ROLE_CONTENT_ELEMENT_REP -> Expat.XML_CQUANT_REP
                    else -> Expat.XML_CQUANT_PLUS
                }
                val nxt = if (quant == Expat.XML_CQUANT_NONE) next else next - enc.minBytesPerChar
                val myindex = nextScaffoldPart()
                if (myindex < 0) return XML_ERROR_NO_MEMORY
                val sc = dtd.scaffold[myindex]!!
                sc.type = Expat.XML_CTYPE_NAME
                sc.quant = quant
                val el = getElementType(enc, b, s, nxt) ?: return XML_ERROR_NO_MEMORY
                sc.name = el.name
                dtd.contentStringLen += el.name.length + 1
                if (elementDeclHandler != null) return R_NO_DEFAULT
            }
        }
        else -> {
            // (XML_ROLE_GROUP_CLOSE and its quantified forms)
            if (dtd.inEldecl) {
                val quant = when (role) {
                    XML_ROLE_GROUP_CLOSE -> Expat.XML_CQUANT_NONE
                    XML_ROLE_GROUP_CLOSE_OPT -> Expat.XML_CQUANT_OPT
                    XML_ROLE_GROUP_CLOSE_REP -> Expat.XML_CQUANT_REP
                    else -> Expat.XML_CQUANT_PLUS
                }
                var handleDefault = true
                val h = elementDeclHandler
                if (h != null) handleDefault = false
                dtd.scaffLevel--
                dtd.scaffold[dtd.scaffIndex!![dtd.scaffLevel]]!!.quant = quant
                if (dtd.scaffLevel == 0) {
                    if (!handleDefault) {
                        val model = buildModel()
                        ev.endPtr = s
                        h!!(declElementType!!.name, model)
                    }
                    dtd.inEldecl = false
                    dtd.contentStringLen = 0
                }
                return if (handleDefault) R_DEFAULT else R_NO_DEFAULT
            }
        }
    }
    return R_DEFAULT
}

// ------------------------------------------------------------------ entity values

/** storeEntityValue: an entity value [b][ptr, end) stored (its references expanded) in the entity value pool. */
fun ExpatParser.storeEntityValue(enc: ExpatEnc, b: ByteArray, entityTextPtr0: Int, entityTextEnd: Int, account: Int, nextPtr: XPtr?): Int {
    val pool = dtd.entityValuePool
    var result = XML_ERROR_NONE
    val oldInEntityValue = prologState.inEntityValue
    prologState.inEntityValue = true
    // (never NULL for the value given to EntityDeclHandler, which would mean an external entity)
    if (!pool.hasBlock && !pool.grow()) return XML_ERROR_NO_MEMORY
    var entityTextPtr = entityTextPtr0
    var next = entityTextPtr
    val nt = XPtr()
    if (entityTextPtr < entityTextEnd) {
        loop@ while (true) {
            nt.p = entityTextPtr
            val tok = enc.entityValueTok(b, entityTextPtr, entityTextEnd, nt)
            next = nt.p
            if (!accountingDiffTolerated(tok, entityTextPtr, next, account)) {
                result = XML_ERROR_AMPLIFICATION_LIMIT_BREACH
                break
            }
            when (tok) {
                XML_TOK_PARAM_ENTITY_REF -> {
                    if (isParamEntity || enc !== encoding) {
                        val r = entityValuePeRef(enc, b, entityTextPtr, next)
                        if (r != STORE_GO_ON) {
                            if (r != STORE_STOP) result = r
                            break@loop
                        }
                    } else {
                        // (in the internal subset, PE references are not legal within markup declarations)
                        events.ptr = entityTextPtr
                        result = XML_ERROR_PARAM_ENTITY_REF
                        break@loop
                    }
                }
                XML_TOK_NONE -> break@loop
                ExpatTok.XML_TOK_ENTITY_REF, ExpatTok.XML_TOK_DATA_CHARS -> if (!pool.append(enc, b, entityTextPtr, next)) {
                    result = XML_ERROR_NO_MEMORY
                    break@loop
                }
                ExpatTok.XML_TOK_TRAILING_CR, ExpatTok.XML_TOK_DATA_NEWLINE -> {
                    if (tok == ExpatTok.XML_TOK_TRAILING_CR) next = entityTextPtr + enc.minBytesPerChar
                    if (!pool.appendChar(0xA)) {
                        result = XML_ERROR_NO_MEMORY
                        break@loop
                    }
                }
                ExpatTok.XML_TOK_CHAR_REF -> {
                    val n = enc.charRefNumber(b, entityTextPtr)
                    if (n < 0) {
                        if (enc === encoding) events.ptr = entityTextPtr
                        result = XML_ERROR_BAD_CHAR_REF
                        break@loop
                    }
                    val buf = ByteArray(4)
                    val k = ExpatTok.utf8Encode(n, buf, 0)
                    for (i in 0 until k) if (!pool.appendChar(buf[i].toInt())) {
                        result = XML_ERROR_NO_MEMORY
                        break@loop
                    }
                }
                XML_TOK_PARTIAL -> {
                    if (enc === encoding) events.ptr = entityTextPtr
                    result = XML_ERROR_INVALID_TOKEN
                    break@loop
                }
                XML_TOK_INVALID -> {
                    if (enc === encoding) events.ptr = next
                    result = XML_ERROR_INVALID_TOKEN
                    break@loop
                }
                else -> {
                    if (enc === encoding) events.ptr = entityTextPtr
                    result = XML_ERROR_UNEXPECTED_STATE
                    break@loop
                }
            }
            entityTextPtr = next
        }
    }
    prologState.inEntityValue = oldInEntityValue
    nextPtr?.p = next
    return result
}

private const val STORE_GO_ON = -1
private const val STORE_STOP = -2

/** storeEntityValue's parameter entity reference: STORE_GO_ON, STORE_STOP (endEntityValue), or an error. */
private fun ExpatParser.entityValuePeRef(enc: ExpatEnc, b: ByteArray, entityTextPtr: Int, next: Int): Int {
    val mb = enc.minBytesPerChar
    val name = tempPool.storeString(enc, b, entityTextPtr + mb, next - mb) ?: return XML_ERROR_NO_MEMORY
    val entity = dtd.paramEntities[name]
    tempPool.discard()
    if (entity == null) {
        // (not a well-formedness error - see XML 1.0: WFC Entity Declared)
        dtd.keepProcessing = dtd.standalone
        return STORE_STOP
    }
    if (entity.open || entity === declEntity) {
        if (enc === encoding) events.ptr = entityTextPtr
        return XML_ERROR_RECURSIVE_ENTITY_REF
    }
    if (entity.systemId != null) {
        val ext = externalEntityRefHandler
        if (ext != null) {
            dtd.paramEntityRead = false
            entity.open = true
            if (ext(null, entity.base, entity.systemId, entity.publicId) == 0) {
                entity.open = false
                return XML_ERROR_EXTERNAL_ENTITY_HANDLING
            }
            entity.open = false
            if (!dtd.paramEntityRead) dtd.keepProcessing = dtd.standalone
        } else dtd.keepProcessing = dtd.standalone
        return STORE_GO_ON
    }
    val result = processEntity(entity, false, ExpatParser.ENTITY_VALUE)
    return if (result == XML_ERROR_NONE) STORE_STOP else result
}

/** callStoreEntityValue: an entity value stored, the parameter entities it opens expanded in turn. */
fun ExpatParser.callStoreEntityValue(enc: ExpatEnc, b: ByteArray, entityTextPtr: Int, entityTextEnd: Int, account: Int): Int {
    val next = XPtr().also { it.p = entityTextPtr }
    var result: Int
    while (true) {
        val openEntity = openValueEntities
        if (openEntity == null) {
            result = storeEntityValue(enc, b, next.p, entityTextEnd, account, next)
        } else {
            val entity = openEntity.entity!!
            val text = entity.text!!
            val nextInEntity = XPtr().also { it.p = entity.processed }
            if (entity.hasMore) {
                result = storeEntityValue(internalEncoding, text, entity.processed, text.size, XML_ACCOUNT_ENTITY_EXPANSION, nextInEntity)
                if (result != XML_ERROR_NONE) break
                if (text.size != nextInEntity.p) {
                    entity.processed = nextInEntity.p
                    continue
                }
                entity.hasMore = false
                continue
            }
            entity.open = false
            openValueEntities = openEntity.next
            openEntity.next = freeValueEntities
            freeValueEntities = openEntity
            result = XML_ERROR_NONE
        }
        if (result != XML_ERROR_NONE || (openValueEntities == null && entityTextEnd == next.p)) break
    }
    return result
}

// ------------------------------------------------------------------ the DTD's tables

/** normalizePublicId: white space runs as one space, none at the ends. */
fun normalizePublicId(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        if (c == ' ' || c == '\r' || c == '\n') {
            if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
        } else sb.append(c)
    }
    if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
    return Expat.cstr(sb.toString())
}

/** defineAttribute: [attId] a default attribute of [type] (an ID's noted); false where memory is refused. */
fun ExpatParser.defineAttribute(type: XElementType, attId: XAttributeId, isCdata: Boolean, isId: Boolean, value: String?): Boolean {
    if (value != null || isId) {
        // (the handling of default attributes gets messed up if we have a default which duplicates a non-default)
        if (attId.name in type.defaultAttsNames) return true
        if (isId && type.idAtt == null && !attId.xmlns) type.idAtt = attId
    }
    if (type.defaultAtts.size == 0 || (type.defaultAtts.size >= 8 && Integer.bitCount(type.defaultAtts.size) == 1)) {
        if (!trackAlloc(maxOf(8, type.defaultAtts.size) * 24L)) return false
    }
    type.defaultAtts.add(XDefaultAttribute(attId, isCdata, value))
    if (!isCdata) attId.maybeTokenized = true
    type.defaultAttsNames.add(attId.name)
    return true
}

/** setElementTypePrefix: an element type's prefix, where its name has a colon. */
fun ExpatParser.setElementTypePrefix(elementType: XElementType): Boolean {
    val i = elementType.name.indexOf(':')
    if (i >= 0) elementType.prefix = prefixOf(elementType.name.substring(0, i)) ?: return false
    return true
}

/** The prefix [name] (made where there is none). */
fun ExpatParser.prefixOf(name: String): XPrefix? {
    dtd.prefixes[name]?.let { return it }
    if (!trackAlloc(16L + name.length + 1)) return null
    return XPrefix(name).also { dtd.prefixes[name] = it }
}

/** getAttributeId: the attribute id of the name [b][start, end) (made, with its prefix, where there is none). */
fun ExpatParser.getAttributeId(enc: ExpatEnc, b: ByteArray, start: Int, end: Int): XAttributeId? {
    val name = dtd.pool.storeString(enc, b, start, end) ?: return null
    dtd.pool.discard()
    dtd.attributeIds[name]?.let { return it }
    if (!trackAlloc(32L + name.length + 2)) return null
    val id = XAttributeId(name)
    dtd.attributeIds[name] = id
    if (!ns) return id
    if (name.startsWith("xmlns") && (name.length == 5 || name[5] == ':')) {
        id.prefix = if (name.length == 5) dtd.defaultPrefix else prefixOf(name.substring(6)) ?: return null
        id.xmlns = true
    } else {
        // (attributes without prefix are *not* in the default namespace)
        val i = name.indexOf(':')
        if (i >= 0) id.prefix = prefixOf(name.substring(0, i)) ?: return null
    }
    return id
}

/** getElementType: the element type of the name [b][ptr, end) (made where there is none). */
fun ExpatParser.getElementType(enc: ExpatEnc, b: ByteArray, ptr: Int, end: Int): XElementType? {
    val name = dtd.pool.storeString(enc, b, ptr, end) ?: return null
    dtd.pool.discard()
    dtd.elementTypes[name]?.let { return it }
    if (!trackAlloc(80L + name.length + 1)) return null
    val ret = XElementType(name)
    dtd.elementTypes[name] = ret
    if (!setElementTypePrefix(ret)) return null
    return ret
}

/** getContext: the namespace bindings and open entities, as an external entity's parser is given them. */
fun ExpatParser.getContext(): String? {
    val sb = StringBuilder()
    var needSep = false
    val sepLen = if (namespaceSeparator != 0) 1 else 0
    val db = dtd.defaultPrefix.binding
    if (db != null) {
        sb.append('=').append(db.uri, 0, db.uri.length - sepLen)
        needSep = true
    }
    for (prefix in dtd.prefixes.values) {
        val bd = prefix.binding ?: continue
        if (needSep) sb.append('\u000C')
        sb.append(prefix.name!!).append('=').append(bd.uri, 0, bd.uri.length - sepLen)
        needSep = true
    }
    for (e in dtd.generalEntities.values) {
        if (!e.open) continue
        if (needSep) sb.append('\u000C')
        sb.append(e.name)
        needSep = true
    }
    if (!tempPool.appendString(sb.toString()) || !tempPool.appendChar(0)) return null
    return sb.toString()
}

/** setContext: the bindings and open entities of [context] (getContext's form) taken. */
fun ExpatParser.setContext(context: String): Boolean {
    var i = 0
    val n = context.length
    val cur = StringBuilder()
    while (i <= n) {
        val c = if (i < n) context[i] else '\u0000'
        if (c == '\u000C' || c == '\u0000') {
            if (cur.isEmpty() && c == '\u0000') break
            dtd.generalEntities[cur.toString()]?.open = true
            cur.setLength(0)
            if (c == '\u0000') break
            i++
        } else if (c == '=') {
            val prefix = if (cur.isEmpty()) dtd.defaultPrefix else prefixOf(cur.toString()) ?: return false
            cur.setLength(0)
            var j = i + 1
            while (j < n && context[j] != '\u000C') j++
            val uri = context.substring(i + 1, j)
            if (addBindingTo(prefix, null, uri) { b ->
                    b.nextTagBinding = inheritedBindings
                    inheritedBindings = b
                } != XML_ERROR_NONE) return false
            i = if (j < n) j + 1 else j
            if (j >= n) break
        } else {
            cur.append(c)
            i++
        }
    }
    return true
}

/** dtdCopy: [oldDtd]'s declarations copied into this (an external entity's) parser's DTD. */
fun ExpatParser.dtdCopy(oldDtd: XDtd): Boolean {
    val newDtd = dtd
    for (oldP in oldDtd.prefixes.values) newDtd.prefixes[oldP.name!!] = XPrefix(oldP.name)
    for (oldA in oldDtd.attributeIds.values) {
        val newA = XAttributeId(oldA.name)
        newDtd.attributeIds[oldA.name] = newA
        newA.maybeTokenized = oldA.maybeTokenized
        val op = oldA.prefix
        if (op != null) {
            newA.xmlns = oldA.xmlns
            newA.prefix = if (op === oldDtd.defaultPrefix) newDtd.defaultPrefix else newDtd.prefixes[op.name!!]
        }
    }
    for (oldE in oldDtd.elementTypes.values) {
        val newE = XElementType(oldE.name)
        newDtd.elementTypes[oldE.name] = newE
        val idAtt = oldE.idAtt
        if (idAtt != null) newE.idAtt = newDtd.attributeIds[idAtt.name]
        val op = oldE.prefix
        if (op != null) newE.prefix = newDtd.prefixes[op.name!!]
        for (da in oldE.defaultAtts) {
            newE.defaultAtts.add(XDefaultAttribute(newDtd.attributeIds[da.id.name]!!, da.isCdata, da.value))
            newE.defaultAttsNames.add(da.id.name)
        }
    }
    copyEntityTable(newDtd.generalEntities, oldDtd.generalEntities)
    copyEntityTable(newDtd.paramEntities, oldDtd.paramEntities)
    newDtd.paramEntityRead = oldDtd.paramEntityRead
    newDtd.keepProcessing = oldDtd.keepProcessing
    newDtd.hasParamEntityRefs = oldDtd.hasParamEntityRefs
    newDtd.standalone = oldDtd.standalone
    // (don't want deep copying for scaffolding)
    newDtd.inEldecl = oldDtd.inEldecl
    newDtd.scaffold = oldDtd.scaffold
    newDtd.contentStringLen = oldDtd.contentStringLen
    newDtd.scaffSize = oldDtd.scaffSize
    newDtd.scaffLevel = oldDtd.scaffLevel
    newDtd.scaffIndex = oldDtd.scaffIndex
    return true
}

/** copyEntityTable */
private fun copyEntityTable(newTable: LinkedHashMap<String, XEntity>, oldTable: LinkedHashMap<String, XEntity>) {
    for (oldE in oldTable.values) {
        val newE = XEntity(oldE.name)
        newTable[oldE.name] = newE
        if (oldE.systemId != null) {
            newE.systemId = oldE.systemId
            newE.base = oldE.base
            newE.publicId = oldE.publicId
        } else newE.text = oldE.text
        newE.notation = oldE.notation
        newE.isParam = oldE.isParam
        newE.isInternal = oldE.isInternal
    }
}

/** nextScaffoldPart: a new node of the content model being declared, -1 where memory is refused. */
fun ExpatParser.nextScaffoldPart(): Int {
    val dtd = dtd
    if (dtd.scaffIndex == null) {
        if (!trackAlloc(groupConnector.size * 4L)) return -1
        dtd.scaffIndex = IntArray(groupConnector.size)
    }
    if (dtd.scaffCount >= dtd.scaffSize) {
        val size = if (dtd.scaffSize != 0) dtd.scaffSize * 2 else 32
        if (!trackAlloc((size - dtd.scaffSize) * 32L)) return -1
        dtd.scaffold = dtd.scaffold.copyOf(size)
        dtd.scaffSize = size
    }
    val next = dtd.scaffCount++
    val me = XScaffold()
    dtd.scaffold[next] = me
    if (dtd.scaffLevel != 0) {
        val parent = dtd.scaffold[dtd.scaffIndex!![dtd.scaffLevel - 1]]!!
        if (parent.lastchild != 0) dtd.scaffold[parent.lastchild]!!.nextsib = next
        if (parent.childcnt == 0) parent.firstchild = next
        parent.lastchild = next
        parent.childcnt++
    }
    return next
}

/** build_model: the content model from the scaffold. */
fun ExpatParser.buildModel(): XContent = buildNode(0)

private fun ExpatParser.buildNode(i: Int): XContent {
    val sc = dtd.scaffold[i]!!
    if (sc.type == Expat.XML_CTYPE_NAME) return XContent(sc.type, sc.quant, sc.name, arrayOf())
    val children = ArrayList<XContent>(sc.childcnt)
    var cn = sc.firstchild
    for (k in 0 until sc.childcnt) {
        children.add(buildNode(cn))
        cn = dtd.scaffold[cn]!!.nextsib
    }
    return XContent(sc.type, sc.quant, null, children.toTypedArray())
}
