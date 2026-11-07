package dev.mooner.neonpython

import dev.mooner.neonpython.ExpatTok.XML_TOK_BOM
import dev.mooner.neonpython.ExpatTok.XML_TOK_CLOSE_BRACKET
import dev.mooner.neonpython.ExpatTok.XML_TOK_CLOSE_PAREN
import dev.mooner.neonpython.ExpatTok.XML_TOK_CLOSE_PAREN_ASTERISK
import dev.mooner.neonpython.ExpatTok.XML_TOK_CLOSE_PAREN_PLUS
import dev.mooner.neonpython.ExpatTok.XML_TOK_CLOSE_PAREN_QUESTION
import dev.mooner.neonpython.ExpatTok.XML_TOK_COMMA
import dev.mooner.neonpython.ExpatTok.XML_TOK_COMMENT
import dev.mooner.neonpython.ExpatTok.XML_TOK_COND_SECT_CLOSE
import dev.mooner.neonpython.ExpatTok.XML_TOK_COND_SECT_OPEN
import dev.mooner.neonpython.ExpatTok.XML_TOK_DECL_CLOSE
import dev.mooner.neonpython.ExpatTok.XML_TOK_DECL_OPEN
import dev.mooner.neonpython.ExpatTok.XML_TOK_INSTANCE_START
import dev.mooner.neonpython.ExpatTok.XML_TOK_LITERAL
import dev.mooner.neonpython.ExpatTok.XML_TOK_NAME
import dev.mooner.neonpython.ExpatTok.XML_TOK_NAME_ASTERISK
import dev.mooner.neonpython.ExpatTok.XML_TOK_NAME_PLUS
import dev.mooner.neonpython.ExpatTok.XML_TOK_NAME_QUESTION
import dev.mooner.neonpython.ExpatTok.XML_TOK_NMTOKEN
import dev.mooner.neonpython.ExpatTok.XML_TOK_NONE
import dev.mooner.neonpython.ExpatTok.XML_TOK_OPEN_BRACKET
import dev.mooner.neonpython.ExpatTok.XML_TOK_OPEN_PAREN
import dev.mooner.neonpython.ExpatTok.XML_TOK_OR
import dev.mooner.neonpython.ExpatTok.XML_TOK_PARAM_ENTITY_REF
import dev.mooner.neonpython.ExpatTok.XML_TOK_PERCENT
import dev.mooner.neonpython.ExpatTok.XML_TOK_PI
import dev.mooner.neonpython.ExpatTok.XML_TOK_POUND_NAME
import dev.mooner.neonpython.ExpatTok.XML_TOK_PREFIXED_NAME
import dev.mooner.neonpython.ExpatTok.XML_TOK_PROLOG_S
import dev.mooner.neonpython.ExpatTok.XML_TOK_XML_DECL

/**
 * PROLOG_STATE (Modules/expat/xmlrole.c, Expat 2.8.1): the prolog's state machine, what role each token has in the
 * prolog and the DTD. [handler] is which of xmlrole.c's handler functions is current (one of the H_ constants);
 * [tokenRole] is XmlTokenRole.
 */
class ExpatProlog {
    @JvmField var handler = H_PROLOG0
    @JvmField var level = 0
    @JvmField var roleNone = 0
    @JvmField var includeLevel = 0
    @JvmField var documentEntity = true
    @JvmField var inEntityValue = false

    /** XmlPrologStateInit */
    fun init() {
        handler = H_PROLOG0
        documentEntity = true
        includeLevel = 0
        inEntityValue = false
    }

    /** XmlPrologStateInitExternalEntity */
    fun initExternalEntity() {
        handler = H_EXTERNAL_SUBSET0
        documentEntity = false
        includeLevel = 0
    }

    /** XmlTokenRole: the role of token [tok] at [b][ptr, end) in [enc]. */
    fun tokenRole(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (handler) {
        H_PROLOG0 -> prolog0(tok, b, ptr, end, enc)
        H_PROLOG1 -> prolog1(tok, b, ptr, end, enc)
        H_PROLOG2 -> prolog2(tok)
        H_DOCTYPE0 -> doctype0(tok)
        H_DOCTYPE1 -> doctype1(tok, b, ptr, end, enc)
        H_DOCTYPE2 -> literal(tok, XML_ROLE_DOCTYPE_NONE, H_DOCTYPE3, XML_ROLE_DOCTYPE_PUBLIC_ID)
        H_DOCTYPE3 -> literal(tok, XML_ROLE_DOCTYPE_NONE, H_DOCTYPE4, XML_ROLE_DOCTYPE_SYSTEM_ID)
        H_DOCTYPE4 -> doctype4(tok)
        H_DOCTYPE5 -> doctype5(tok)
        H_INTERNAL_SUBSET -> internalSubset(tok, b, ptr, end, enc)
        H_EXTERNAL_SUBSET0 -> {
            handler = H_EXTERNAL_SUBSET1
            if (tok == XML_TOK_XML_DECL) XML_ROLE_TEXT_DECL else externalSubset1(tok, b, ptr, end, enc)
        }
        H_EXTERNAL_SUBSET1 -> externalSubset1(tok, b, ptr, end, enc)
        H_ENTITY0 -> entity0(tok)
        H_ENTITY1 -> entity1(tok)
        H_ENTITY2 -> entityName(tok, b, ptr, end, enc, H_ENTITY4, H_ENTITY3)
        H_ENTITY3 -> literal(tok, XML_ROLE_ENTITY_NONE, H_ENTITY4, XML_ROLE_ENTITY_PUBLIC_ID)
        H_ENTITY4 -> literal(tok, XML_ROLE_ENTITY_NONE, H_ENTITY5, XML_ROLE_ENTITY_SYSTEM_ID)
        H_ENTITY5 -> entity5(tok, b, ptr, end, enc)
        H_ENTITY6 -> entity6(tok)
        H_ENTITY7 -> entityName(tok, b, ptr, end, enc, H_ENTITY9, H_ENTITY8)
        H_ENTITY8 -> literal(tok, XML_ROLE_ENTITY_NONE, H_ENTITY9, XML_ROLE_ENTITY_PUBLIC_ID)
        H_ENTITY9 -> literal(tok, XML_ROLE_ENTITY_NONE, H_ENTITY10, XML_ROLE_ENTITY_SYSTEM_ID)
        H_ENTITY10 -> entity10(tok)
        H_NOTATION0 -> notation0(tok)
        H_NOTATION1 -> notation1(tok, b, ptr, end, enc)
        H_NOTATION2 -> literal(tok, XML_ROLE_NOTATION_NONE, H_NOTATION4, XML_ROLE_NOTATION_PUBLIC_ID)
        H_NOTATION3 -> notation3(tok)
        H_NOTATION4 -> notation4(tok)
        H_ATTLIST0 -> attlist0(tok)
        H_ATTLIST1 -> attlist1(tok)
        H_ATTLIST2 -> attlist2(tok, b, ptr, end, enc)
        H_ATTLIST3 -> attlist3(tok)
        H_ATTLIST4 -> attlistOr(tok, H_ATTLIST3)
        H_ATTLIST5 -> attlist5(tok)
        H_ATTLIST6 -> attlist6(tok)
        H_ATTLIST7 -> attlistOr(tok, H_ATTLIST6)
        H_ATTLIST8 -> attlist8(tok, b, ptr, end, enc)
        H_ATTLIST9 -> literal(tok, XML_ROLE_ATTLIST_NONE, H_ATTLIST1, XML_ROLE_FIXED_ATTRIBUTE_VALUE)
        H_ELEMENT0 -> element0(tok)
        H_ELEMENT1 -> element1(tok, b, ptr, end, enc)
        H_ELEMENT2 -> element2(tok, b, ptr, end, enc)
        H_ELEMENT3 -> element3(tok)
        H_ELEMENT4 -> element4(tok)
        H_ELEMENT5 -> element5(tok)
        H_ELEMENT6 -> element6(tok)
        H_ELEMENT7 -> element7(tok)
        H_COND_SECT0 -> condSect0(tok, b, ptr, end, enc)
        H_COND_SECT1 -> condSect1(tok)
        H_COND_SECT2 -> condSect2(tok)
        H_DECL_CLOSE -> declClose(tok)
        else -> XML_ROLE_NONE
    }

    /** setTopLevel */
    private fun setTopLevel() {
        handler = if (documentEntity) H_INTERNAL_SUBSET else H_EXTERNAL_SUBSET1
    }

    /** common: what a token no handler takes is (an error). */
    private fun common(tok: Int): Int {
        if (!documentEntity && tok == XML_TOK_PARAM_ENTITY_REF) return XML_ROLE_INNER_PARAM_ENTITY_REF
        handler = H_ERROR
        return XML_ROLE_ERROR
    }

    private fun to(h: Int, role: Int): Int {
        handler = h
        return role
    }

    /** A handler that takes white space ([none]) and a literal (going to [next] as [role]) only. */
    private fun literal(tok: Int, none: Int, next: Int, role: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> none
        XML_TOK_LITERAL -> to(next, role)
        else -> common(tok)
    }

    /** The keyword at [ptr] + 2 characters (a declaration's, after "<!"). */
    private fun decl(b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc, kw: String) = enc.nameMatchesAscii(b, ptr + 2 * enc.minBytesPerChar, end, kw)

    private fun prolog0(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (tok) {
        XML_TOK_PROLOG_S -> to(H_PROLOG1, XML_ROLE_NONE)
        XML_TOK_XML_DECL -> to(H_PROLOG1, XML_ROLE_XML_DECL)
        XML_TOK_PI -> to(H_PROLOG1, XML_ROLE_PI)
        XML_TOK_COMMENT -> to(H_PROLOG1, XML_ROLE_COMMENT)
        XML_TOK_BOM -> XML_ROLE_NONE
        XML_TOK_DECL_OPEN -> if (decl(b, ptr, end, enc, "DOCTYPE")) to(H_DOCTYPE0, XML_ROLE_DOCTYPE_NONE) else common(tok)
        XML_TOK_INSTANCE_START -> to(H_ERROR, XML_ROLE_INSTANCE_START)
        else -> common(tok)
    }

    private fun prolog1(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NONE
        XML_TOK_PI -> XML_ROLE_PI
        XML_TOK_COMMENT -> XML_ROLE_COMMENT
        XML_TOK_BOM -> XML_ROLE_NONE
        XML_TOK_DECL_OPEN -> if (decl(b, ptr, end, enc, "DOCTYPE")) to(H_DOCTYPE0, XML_ROLE_DOCTYPE_NONE) else common(tok)
        XML_TOK_INSTANCE_START -> to(H_ERROR, XML_ROLE_INSTANCE_START)
        else -> common(tok)
    }

    private fun prolog2(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NONE
        XML_TOK_PI -> XML_ROLE_PI
        XML_TOK_COMMENT -> XML_ROLE_COMMENT
        XML_TOK_INSTANCE_START -> to(H_ERROR, XML_ROLE_INSTANCE_START)
        else -> common(tok)
    }

    private fun doctype0(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_DOCTYPE_NONE
        XML_TOK_NAME, XML_TOK_PREFIXED_NAME -> to(H_DOCTYPE1, XML_ROLE_DOCTYPE_NAME)
        else -> common(tok)
    }

    private fun doctype1(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_DOCTYPE_NONE
        XML_TOK_OPEN_BRACKET -> to(H_INTERNAL_SUBSET, XML_ROLE_DOCTYPE_INTERNAL_SUBSET)
        XML_TOK_DECL_CLOSE -> to(H_PROLOG2, XML_ROLE_DOCTYPE_CLOSE)
        XML_TOK_NAME -> if (enc.nameMatchesAscii(b, ptr, end, "SYSTEM")) to(H_DOCTYPE3, XML_ROLE_DOCTYPE_NONE)
            else if (enc.nameMatchesAscii(b, ptr, end, "PUBLIC")) to(H_DOCTYPE2, XML_ROLE_DOCTYPE_NONE)
            else common(tok)
        else -> common(tok)
    }

    private fun doctype4(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_DOCTYPE_NONE
        XML_TOK_OPEN_BRACKET -> to(H_INTERNAL_SUBSET, XML_ROLE_DOCTYPE_INTERNAL_SUBSET)
        XML_TOK_DECL_CLOSE -> to(H_PROLOG2, XML_ROLE_DOCTYPE_CLOSE)
        else -> common(tok)
    }

    private fun doctype5(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_DOCTYPE_NONE
        XML_TOK_DECL_CLOSE -> to(H_PROLOG2, XML_ROLE_DOCTYPE_CLOSE)
        else -> common(tok)
    }

    private fun internalSubset(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NONE
        XML_TOK_DECL_OPEN -> if (decl(b, ptr, end, enc, "ENTITY")) to(H_ENTITY0, XML_ROLE_ENTITY_NONE)
            else if (decl(b, ptr, end, enc, "ATTLIST")) to(H_ATTLIST0, XML_ROLE_ATTLIST_NONE)
            else if (decl(b, ptr, end, enc, "ELEMENT")) to(H_ELEMENT0, XML_ROLE_ELEMENT_NONE)
            else if (decl(b, ptr, end, enc, "NOTATION")) to(H_NOTATION0, XML_ROLE_NOTATION_NONE)
            else common(tok)
        XML_TOK_PI -> XML_ROLE_PI
        XML_TOK_COMMENT -> XML_ROLE_COMMENT
        XML_TOK_PARAM_ENTITY_REF -> XML_ROLE_PARAM_ENTITY_REF
        XML_TOK_CLOSE_BRACKET -> to(H_DOCTYPE5, XML_ROLE_DOCTYPE_NONE)
        XML_TOK_NONE -> XML_ROLE_NONE
        else -> common(tok)
    }

    private fun externalSubset1(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (tok) {
        XML_TOK_COND_SECT_OPEN -> to(H_COND_SECT0, XML_ROLE_NONE)
        XML_TOK_COND_SECT_CLOSE -> if (includeLevel == 0) common(tok) else {
            includeLevel -= 1
            XML_ROLE_NONE
        }
        XML_TOK_PROLOG_S -> XML_ROLE_NONE
        XML_TOK_CLOSE_BRACKET -> common(tok)
        XML_TOK_NONE -> if (includeLevel != 0) common(tok) else XML_ROLE_NONE
        else -> internalSubset(tok, b, ptr, end, enc)
    }

    private fun entity0(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ENTITY_NONE
        XML_TOK_PERCENT -> to(H_ENTITY1, XML_ROLE_ENTITY_NONE)
        XML_TOK_NAME -> to(H_ENTITY2, XML_ROLE_GENERAL_ENTITY_NAME)
        else -> common(tok)
    }

    private fun entity1(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ENTITY_NONE
        XML_TOK_NAME -> to(H_ENTITY7, XML_ROLE_PARAM_ENTITY_NAME)
        else -> common(tok)
    }

    /** entity2 and entity7: after an entity's name, SYSTEM ([system]), PUBLIC ([public]) or its value. */
    private fun entityName(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc, system: Int, public: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ENTITY_NONE
        XML_TOK_NAME -> if (enc.nameMatchesAscii(b, ptr, end, "SYSTEM")) to(system, XML_ROLE_ENTITY_NONE)
            else if (enc.nameMatchesAscii(b, ptr, end, "PUBLIC")) to(public, XML_ROLE_ENTITY_NONE)
            else common(tok)
        XML_TOK_LITERAL -> {
            roleNone = XML_ROLE_ENTITY_NONE
            to(H_DECL_CLOSE, XML_ROLE_ENTITY_VALUE)
        }
        else -> common(tok)
    }

    private fun entity5(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ENTITY_NONE
        XML_TOK_DECL_CLOSE -> {
            setTopLevel()
            XML_ROLE_ENTITY_COMPLETE
        }
        XML_TOK_NAME -> if (enc.nameMatchesAscii(b, ptr, end, "NDATA")) to(H_ENTITY6, XML_ROLE_ENTITY_NONE) else common(tok)
        else -> common(tok)
    }

    private fun entity6(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ENTITY_NONE
        XML_TOK_NAME -> {
            roleNone = XML_ROLE_ENTITY_NONE
            to(H_DECL_CLOSE, XML_ROLE_ENTITY_NOTATION_NAME)
        }
        else -> common(tok)
    }

    private fun entity10(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ENTITY_NONE
        XML_TOK_DECL_CLOSE -> {
            setTopLevel()
            XML_ROLE_ENTITY_COMPLETE
        }
        else -> common(tok)
    }

    private fun notation0(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NOTATION_NONE
        XML_TOK_NAME -> to(H_NOTATION1, XML_ROLE_NOTATION_NAME)
        else -> common(tok)
    }

    private fun notation1(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NOTATION_NONE
        XML_TOK_NAME -> if (enc.nameMatchesAscii(b, ptr, end, "SYSTEM")) to(H_NOTATION3, XML_ROLE_NOTATION_NONE)
            else if (enc.nameMatchesAscii(b, ptr, end, "PUBLIC")) to(H_NOTATION2, XML_ROLE_NOTATION_NONE)
            else common(tok)
        else -> common(tok)
    }

    private fun notation3(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NOTATION_NONE
        XML_TOK_LITERAL -> {
            roleNone = XML_ROLE_NOTATION_NONE
            to(H_DECL_CLOSE, XML_ROLE_NOTATION_SYSTEM_ID)
        }
        else -> common(tok)
    }

    private fun notation4(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NOTATION_NONE
        XML_TOK_LITERAL -> {
            roleNone = XML_ROLE_NOTATION_NONE
            to(H_DECL_CLOSE, XML_ROLE_NOTATION_SYSTEM_ID)
        }
        XML_TOK_DECL_CLOSE -> {
            setTopLevel()
            XML_ROLE_NOTATION_NO_SYSTEM_ID
        }
        else -> common(tok)
    }

    private fun attlist0(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ATTLIST_NONE
        XML_TOK_NAME, XML_TOK_PREFIXED_NAME -> to(H_ATTLIST1, XML_ROLE_ATTLIST_ELEMENT_NAME)
        else -> common(tok)
    }

    private fun attlist1(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ATTLIST_NONE
        XML_TOK_DECL_CLOSE -> {
            setTopLevel()
            XML_ROLE_ATTLIST_NONE
        }
        XML_TOK_NAME, XML_TOK_PREFIXED_NAME -> to(H_ATTLIST2, XML_ROLE_ATTRIBUTE_NAME)
        else -> common(tok)
    }

    private fun attlist2(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int {
        when (tok) {
            XML_TOK_PROLOG_S -> return XML_ROLE_ATTLIST_NONE
            XML_TOK_NAME -> {
                for (i in ATT_TYPES.indices) if (enc.nameMatchesAscii(b, ptr, end, ATT_TYPES[i])) return to(H_ATTLIST8, XML_ROLE_ATTRIBUTE_TYPE_CDATA + i)
                if (enc.nameMatchesAscii(b, ptr, end, "NOTATION")) return to(H_ATTLIST5, XML_ROLE_ATTLIST_NONE)
            }
            XML_TOK_OPEN_PAREN -> return to(H_ATTLIST3, XML_ROLE_ATTLIST_NONE)
        }
        return common(tok)
    }

    private fun attlist3(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ATTLIST_NONE
        XML_TOK_NMTOKEN, XML_TOK_NAME, XML_TOK_PREFIXED_NAME -> to(H_ATTLIST4, XML_ROLE_ATTRIBUTE_ENUM_VALUE)
        else -> common(tok)
    }

    /** attlist4 and attlist7: after an enumerated value, ')' or '|' (back to [or]). */
    private fun attlistOr(tok: Int, or: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ATTLIST_NONE
        XML_TOK_CLOSE_PAREN -> to(H_ATTLIST8, XML_ROLE_ATTLIST_NONE)
        XML_TOK_OR -> to(or, XML_ROLE_ATTLIST_NONE)
        else -> common(tok)
    }

    private fun attlist5(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ATTLIST_NONE
        XML_TOK_OPEN_PAREN -> to(H_ATTLIST6, XML_ROLE_ATTLIST_NONE)
        else -> common(tok)
    }

    private fun attlist6(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ATTLIST_NONE
        XML_TOK_NAME -> to(H_ATTLIST7, XML_ROLE_ATTRIBUTE_NOTATION_VALUE)
        else -> common(tok)
    }

    /** attlist8: the default value. */
    private fun attlist8(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int {
        when (tok) {
            XML_TOK_PROLOG_S -> return XML_ROLE_ATTLIST_NONE
            XML_TOK_POUND_NAME -> {
                val p = ptr + enc.minBytesPerChar
                if (enc.nameMatchesAscii(b, p, end, "IMPLIED")) return to(H_ATTLIST1, XML_ROLE_IMPLIED_ATTRIBUTE_VALUE)
                if (enc.nameMatchesAscii(b, p, end, "REQUIRED")) return to(H_ATTLIST1, XML_ROLE_REQUIRED_ATTRIBUTE_VALUE)
                if (enc.nameMatchesAscii(b, p, end, "FIXED")) return to(H_ATTLIST9, XML_ROLE_ATTLIST_NONE)
            }
            XML_TOK_LITERAL -> return to(H_ATTLIST1, XML_ROLE_DEFAULT_ATTRIBUTE_VALUE)
        }
        return common(tok)
    }

    private fun element0(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ELEMENT_NONE
        XML_TOK_NAME, XML_TOK_PREFIXED_NAME -> to(H_ELEMENT1, XML_ROLE_ELEMENT_NAME)
        else -> common(tok)
    }

    private fun element1(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int {
        when (tok) {
            XML_TOK_PROLOG_S -> return XML_ROLE_ELEMENT_NONE
            XML_TOK_NAME -> {
                if (enc.nameMatchesAscii(b, ptr, end, "EMPTY")) {
                    roleNone = XML_ROLE_ELEMENT_NONE
                    return to(H_DECL_CLOSE, XML_ROLE_CONTENT_EMPTY)
                }
                if (enc.nameMatchesAscii(b, ptr, end, "ANY")) {
                    roleNone = XML_ROLE_ELEMENT_NONE
                    return to(H_DECL_CLOSE, XML_ROLE_CONTENT_ANY)
                }
            }
            XML_TOK_OPEN_PAREN -> {
                level = 1
                return to(H_ELEMENT2, XML_ROLE_GROUP_OPEN)
            }
        }
        return common(tok)
    }

    private fun element2(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ELEMENT_NONE
        XML_TOK_POUND_NAME -> if (enc.nameMatchesAscii(b, ptr + enc.minBytesPerChar, end, "PCDATA")) to(H_ELEMENT3, XML_ROLE_CONTENT_PCDATA) else common(tok)
        XML_TOK_OPEN_PAREN -> {
            level = 2
            to(H_ELEMENT6, XML_ROLE_GROUP_OPEN)
        }
        else -> elementName(tok)
    }

    /** element2's and element6's names, each going to element7. */
    private fun elementName(tok: Int): Int = when (tok) {
        XML_TOK_NAME, XML_TOK_PREFIXED_NAME -> to(H_ELEMENT7, XML_ROLE_CONTENT_ELEMENT)
        XML_TOK_NAME_QUESTION -> to(H_ELEMENT7, XML_ROLE_CONTENT_ELEMENT_OPT)
        XML_TOK_NAME_ASTERISK -> to(H_ELEMENT7, XML_ROLE_CONTENT_ELEMENT_REP)
        XML_TOK_NAME_PLUS -> to(H_ELEMENT7, XML_ROLE_CONTENT_ELEMENT_PLUS)
        else -> common(tok)
    }

    private fun element3(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ELEMENT_NONE
        XML_TOK_CLOSE_PAREN -> {
            roleNone = XML_ROLE_ELEMENT_NONE
            to(H_DECL_CLOSE, XML_ROLE_GROUP_CLOSE)
        }
        XML_TOK_CLOSE_PAREN_ASTERISK -> {
            roleNone = XML_ROLE_ELEMENT_NONE
            to(H_DECL_CLOSE, XML_ROLE_GROUP_CLOSE_REP)
        }
        XML_TOK_OR -> to(H_ELEMENT4, XML_ROLE_ELEMENT_NONE)
        else -> common(tok)
    }

    private fun element4(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ELEMENT_NONE
        XML_TOK_NAME, XML_TOK_PREFIXED_NAME -> to(H_ELEMENT5, XML_ROLE_CONTENT_ELEMENT)
        else -> common(tok)
    }

    private fun element5(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ELEMENT_NONE
        XML_TOK_CLOSE_PAREN_ASTERISK -> {
            roleNone = XML_ROLE_ELEMENT_NONE
            to(H_DECL_CLOSE, XML_ROLE_GROUP_CLOSE_REP)
        }
        XML_TOK_OR -> to(H_ELEMENT4, XML_ROLE_ELEMENT_NONE)
        else -> common(tok)
    }

    private fun element6(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ELEMENT_NONE
        XML_TOK_OPEN_PAREN -> {
            level += 1
            XML_ROLE_GROUP_OPEN
        }
        else -> elementName(tok)
    }

    /** A group closed: back to declClose at the outermost. */
    private fun close(role: Int): Int {
        level -= 1
        if (level == 0) {
            handler = H_DECL_CLOSE
            roleNone = XML_ROLE_ELEMENT_NONE
        }
        return role
    }

    private fun element7(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_ELEMENT_NONE
        XML_TOK_CLOSE_PAREN -> close(XML_ROLE_GROUP_CLOSE)
        XML_TOK_CLOSE_PAREN_ASTERISK -> close(XML_ROLE_GROUP_CLOSE_REP)
        XML_TOK_CLOSE_PAREN_QUESTION -> close(XML_ROLE_GROUP_CLOSE_OPT)
        XML_TOK_CLOSE_PAREN_PLUS -> close(XML_ROLE_GROUP_CLOSE_PLUS)
        XML_TOK_COMMA -> to(H_ELEMENT6, XML_ROLE_GROUP_SEQUENCE)
        XML_TOK_OR -> to(H_ELEMENT6, XML_ROLE_GROUP_CHOICE)
        else -> common(tok)
    }

    private fun condSect0(tok: Int, b: ByteArray, ptr: Int, end: Int, enc: ExpatEnc): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NONE
        XML_TOK_NAME -> if (enc.nameMatchesAscii(b, ptr, end, "INCLUDE")) to(H_COND_SECT1, XML_ROLE_NONE)
            else if (enc.nameMatchesAscii(b, ptr, end, "IGNORE")) to(H_COND_SECT2, XML_ROLE_NONE)
            else common(tok)
        else -> common(tok)
    }

    private fun condSect1(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NONE
        XML_TOK_OPEN_BRACKET -> {
            includeLevel += 1
            to(H_EXTERNAL_SUBSET1, XML_ROLE_NONE)
        }
        else -> common(tok)
    }

    private fun condSect2(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> XML_ROLE_NONE
        XML_TOK_OPEN_BRACKET -> to(H_EXTERNAL_SUBSET1, XML_ROLE_IGNORE_SECT)
        else -> common(tok)
    }

    private fun declClose(tok: Int): Int = when (tok) {
        XML_TOK_PROLOG_S -> roleNone
        XML_TOK_DECL_CLOSE -> {
            setTopLevel()
            roleNone
        }
        else -> common(tok)
    }

    companion object {
        const val XML_ROLE_ERROR = -1
        const val XML_ROLE_NONE = 0
        const val XML_ROLE_XML_DECL = 1
        const val XML_ROLE_INSTANCE_START = 2
        const val XML_ROLE_DOCTYPE_NONE = 3
        const val XML_ROLE_DOCTYPE_NAME = 4
        const val XML_ROLE_DOCTYPE_SYSTEM_ID = 5
        const val XML_ROLE_DOCTYPE_PUBLIC_ID = 6
        const val XML_ROLE_DOCTYPE_INTERNAL_SUBSET = 7
        const val XML_ROLE_DOCTYPE_CLOSE = 8
        const val XML_ROLE_GENERAL_ENTITY_NAME = 9
        const val XML_ROLE_PARAM_ENTITY_NAME = 10
        const val XML_ROLE_ENTITY_NONE = 11
        const val XML_ROLE_ENTITY_VALUE = 12
        const val XML_ROLE_ENTITY_SYSTEM_ID = 13
        const val XML_ROLE_ENTITY_PUBLIC_ID = 14
        const val XML_ROLE_ENTITY_COMPLETE = 15
        const val XML_ROLE_ENTITY_NOTATION_NAME = 16
        const val XML_ROLE_NOTATION_NONE = 17
        const val XML_ROLE_NOTATION_NAME = 18
        const val XML_ROLE_NOTATION_SYSTEM_ID = 19
        const val XML_ROLE_NOTATION_NO_SYSTEM_ID = 20
        const val XML_ROLE_NOTATION_PUBLIC_ID = 21
        const val XML_ROLE_ATTRIBUTE_NAME = 22
        const val XML_ROLE_ATTRIBUTE_TYPE_CDATA = 23
        const val XML_ROLE_ATTRIBUTE_TYPE_ID = 24
        const val XML_ROLE_ATTRIBUTE_TYPE_IDREF = 25
        const val XML_ROLE_ATTRIBUTE_TYPE_IDREFS = 26
        const val XML_ROLE_ATTRIBUTE_TYPE_ENTITY = 27
        const val XML_ROLE_ATTRIBUTE_TYPE_ENTITIES = 28
        const val XML_ROLE_ATTRIBUTE_TYPE_NMTOKEN = 29
        const val XML_ROLE_ATTRIBUTE_TYPE_NMTOKENS = 30
        const val XML_ROLE_ATTRIBUTE_ENUM_VALUE = 31
        const val XML_ROLE_ATTRIBUTE_NOTATION_VALUE = 32
        const val XML_ROLE_ATTLIST_NONE = 33
        const val XML_ROLE_ATTLIST_ELEMENT_NAME = 34
        const val XML_ROLE_IMPLIED_ATTRIBUTE_VALUE = 35
        const val XML_ROLE_REQUIRED_ATTRIBUTE_VALUE = 36
        const val XML_ROLE_DEFAULT_ATTRIBUTE_VALUE = 37
        const val XML_ROLE_FIXED_ATTRIBUTE_VALUE = 38
        const val XML_ROLE_ELEMENT_NONE = 39
        const val XML_ROLE_ELEMENT_NAME = 40
        const val XML_ROLE_CONTENT_ANY = 41
        const val XML_ROLE_CONTENT_EMPTY = 42
        const val XML_ROLE_CONTENT_PCDATA = 43
        const val XML_ROLE_GROUP_OPEN = 44
        const val XML_ROLE_GROUP_CLOSE = 45
        const val XML_ROLE_GROUP_CLOSE_REP = 46
        const val XML_ROLE_GROUP_CLOSE_OPT = 47
        const val XML_ROLE_GROUP_CLOSE_PLUS = 48
        const val XML_ROLE_GROUP_CHOICE = 49
        const val XML_ROLE_GROUP_SEQUENCE = 50
        const val XML_ROLE_CONTENT_ELEMENT = 51
        const val XML_ROLE_CONTENT_ELEMENT_REP = 52
        const val XML_ROLE_CONTENT_ELEMENT_OPT = 53
        const val XML_ROLE_CONTENT_ELEMENT_PLUS = 54
        const val XML_ROLE_PI = 55
        const val XML_ROLE_COMMENT = 56
        const val XML_ROLE_TEXT_DECL = 57
        const val XML_ROLE_IGNORE_SECT = 58
        const val XML_ROLE_INNER_PARAM_ENTITY_REF = 59
        const val XML_ROLE_PARAM_ENTITY_REF = 60

        private val ATT_TYPES = arrayOf("CDATA", "ID", "IDREF", "IDREFS", "ENTITY", "ENTITIES", "NMTOKEN", "NMTOKENS")

        const val H_PROLOG0 = 0
        const val H_PROLOG1 = 1
        const val H_PROLOG2 = 2
        const val H_DOCTYPE0 = 3
        const val H_DOCTYPE1 = 4
        const val H_DOCTYPE2 = 5
        const val H_DOCTYPE3 = 6
        const val H_DOCTYPE4 = 7
        const val H_DOCTYPE5 = 8
        const val H_INTERNAL_SUBSET = 9
        const val H_EXTERNAL_SUBSET0 = 10
        const val H_EXTERNAL_SUBSET1 = 11
        const val H_ENTITY0 = 12
        const val H_ENTITY1 = 13
        const val H_ENTITY2 = 14
        const val H_ENTITY3 = 15
        const val H_ENTITY4 = 16
        const val H_ENTITY5 = 17
        const val H_ENTITY6 = 18
        const val H_ENTITY7 = 19
        const val H_ENTITY8 = 20
        const val H_ENTITY9 = 21
        const val H_ENTITY10 = 22
        const val H_NOTATION0 = 23
        const val H_NOTATION1 = 24
        const val H_NOTATION2 = 25
        const val H_NOTATION3 = 26
        const val H_NOTATION4 = 27
        const val H_ATTLIST0 = 28
        const val H_ATTLIST1 = 29
        const val H_ATTLIST2 = 30
        const val H_ATTLIST3 = 31
        const val H_ATTLIST4 = 32
        const val H_ATTLIST5 = 33
        const val H_ATTLIST6 = 34
        const val H_ATTLIST7 = 35
        const val H_ATTLIST8 = 36
        const val H_ATTLIST9 = 37
        const val H_ELEMENT0 = 38
        const val H_ELEMENT1 = 39
        const val H_ELEMENT2 = 40
        const val H_ELEMENT3 = 41
        const val H_ELEMENT4 = 42
        const val H_ELEMENT5 = 43
        const val H_ELEMENT6 = 44
        const val H_ELEMENT7 = 45
        const val H_COND_SECT0 = 46
        const val H_COND_SECT1 = 47
        const val H_COND_SECT2 = 48
        const val H_DECL_CLOSE = 49
        const val H_ERROR = 50
    }
}
