/*
 * SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
 * SPDX-License-Identifier: 0BSD
 */
package io.github.stanio.bibata.util;

import java.io.IOException;
import java.io.StringReader;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * Extends the standard {@code XMLFilterImpl} with {@code LexicalHandler}
 * methods.  Handles the {@value #LEXICAL_HANDLER} property explicitly.
 */
public class BaseXMLFilter extends XMLFilterImpl implements LexicalHandler {

    public static final String FEATURE = "http://xml.org/sax/features/";
    public static final String PROPERTY = "http://xml.org/sax/properties/";
    static final String LEXICAL_HANDLER = PROPERTY + "lexical-handler";

    static final Logger log = Logger.getLogger(BaseXMLFilter.class.getName());

    LexicalHandler lexicalHandler;

    /**
     * Returns the {@code lexicalHandler}.
     *
     * @return  the {@code lexicalHandler}, or {@code null}
     */
    public LexicalHandler getLexicalHandler() {
        return lexicalHandler;
    }

    /**
     * Sets the {@code lexicalHandler}.
     *
     * @param   handler  the lexicalHandler to set, or {@code null} to unset
     */
    public void setLexicalHandler(LexicalHandler handler) {
        this.lexicalHandler = handler;
    }

    @Override
    public void setProperty(String name, Object value)
            throws SAXNotRecognizedException, SAXNotSupportedException {
        if (name.equals(LEXICAL_HANDLER)) {
            lexicalHandler = (LexicalHandler) value;
        } else {
            super.setProperty(name, value);
        }
    }

    @Override
    public Object getProperty(String name)
            throws SAXNotRecognizedException, SAXNotSupportedException {
        return name.equals(LEXICAL_HANDLER) ? lexicalHandler
                                            : super.getProperty(name);
    }

    @Override
    public void parse(InputSource input) throws SAXException, IOException {
        setUpParse(input);
        super.parse(input);
    }

    /**
     * Failure to set {@value #LEXICAL_HANDLER} is reported via
     * {@link #error(SAXParseException)}.  Inspect the exception {@code cause}
     * to detect, and if deemed fatal – rethrow to stop parsing.
     */
    void setUpParse(InputSource input) throws SAXException {
        if (super.getParent() == null)
            throw new NullPointerException("No parent for filter");

        try {
            //getParent().setProperty(LEXICAL_HANDLER, this);
            super.setProperty(LEXICAL_HANDLER, this);
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            error(new SAXParseException(e.toString(), null, null, -1, -1, e));
        }
    }

    @Override
    public InputSource resolveEntity(String publicId, String systemId)
            throws SAXException, IOException {
        InputSource entity = super.resolveEntity(publicId, systemId);
        // REVISIT: Fail by default?
        return (entity == null) ? emptyEntity() : entity;
    }

    private static InputSource emptyEntity() {
        return new InputSource(new StringReader(""));
    }

    @Override
    public void startDTD(String name, String publicId, String systemId)
            throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.startDTD(name, publicId, systemId);
        }
    }

    @Override
    public void endDTD() throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.endDTD();
        }
    }

    @Override
    public void startEntity(String name) throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.startEntity(name);
        }
    }

    @Override
    public void endEntity(String name) throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.endEntity(name);
        }
    }

    @Override
    public void startCDATA() throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.startCDATA();
        }
    }

    @Override
    public void endCDATA() throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.endCDATA();
        }
    }

    @Override
    public void comment(char[] ch, int start, int length) throws SAXException {
        if (lexicalHandler != null) {
            lexicalHandler.comment(ch, start, length);
        }
    }

    public void close() {
        XMLReader parent = super.getParent();
        super.setParent(null);
        super.setErrorHandler(null);
        super.setEntityResolver(null);
        super.setContentHandler(null);
        super.setDTDHandler(null);
        lexicalHandler = null;
        super.setDocumentLocator(null);
        if (parent != null) {
            reset(parent);
        }
    }

    public static void reset(XMLReader xmlReader) {
        xmlReader.setEntityResolver(null);
        xmlReader.setContentHandler(null);
        xmlReader.setErrorHandler(null);
        // A transformer may set any of these and does set the lexical-handler.
        // Unset them so they don't accidentally kick in when the xmlReader gets
        // reused (w/o reseting them explicitly).
        xmlReader.setDTDHandler(null);
        try {
            xmlReader.setProperty(LEXICAL_HANDLER, null);
            xmlReader.setProperty(PROPERTY + "declaration-handler", null);
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            log.log(Level.FINE, "reset(XMLReader)", e);
        }
    }

} // class BaseXMLFilter
