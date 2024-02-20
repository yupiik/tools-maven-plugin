/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.tools.ascii2svg.json;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.NoSuchElementException;

import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.END_ARRAY;
import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.END_OBJECT;
import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.KEY_NAME;
import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.START_ARRAY;
import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.START_OBJECT;
import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.VALUE_FALSE;
import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.VALUE_NULL;
import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.VALUE_NUMBER;
import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.VALUE_STRING;
import static io.yupiik.tools.ascii2svg.json.JsonParser.Event.VALUE_TRUE;

// forked from Apache johnzon
public class JsonParser implements AutoCloseable {
    private static final Event[] EVT_MAP = Event.values();

    private final boolean autoAdjust;
    private final char[] buffer;
    private int bufferPos = Integer.MIN_VALUE;
    private int bufferLeft = 0;
    private int availableCharsInBuffer;
    private int startOfValueInBuffer = -1;
    private int endOfValueInBuffer = -1;

    private final Reader in;

    private final BufferProvider bufferProvider;

    private byte previousEvent = -1;
    private char[] fallBackCopyBuffer;
    private boolean releaseFallBackCopyBufferLength = true;
    private int fallBackCopyBufferLength;

    private long currentLine = 1;
    private long lastLineBreakPosition;
    private long pastBufferReadCount;

    private boolean isCurrentNumberIntegral = true;
    private int currentIntegralNumber = Integer.MIN_VALUE;

    private StructureElement currentStructureElement = null;

    private boolean closed;

    // for wrappers mainly
    private Event rewindedEvent;

    public JsonParser(final Reader reader, final BufferProvider bufferProvider) {
        this.autoAdjust = true;
        this.bufferProvider = bufferProvider;
        this.in = reader;
        this.buffer = bufferProvider.newBuffer();
        this.fallBackCopyBuffer = bufferProvider.newBuffer();
    }

    private void appendToCopyBuffer(final char c) {
        if (fallBackCopyBufferLength >= fallBackCopyBuffer.length - 1) {
            doAutoAdjust(1);
        }
        fallBackCopyBuffer[fallBackCopyBufferLength++] = c;
    }

    //copy content between "start" and "end" from buffer to value buffer 
    private void copyCurrentValue() {
        final int length = endOfValueInBuffer - startOfValueInBuffer;
        if (length > 0) {
            if (fallBackCopyBufferLength >= fallBackCopyBuffer.length - length) { // not good at runtime but handled
                doAutoAdjust(length);
            } else {
                System.arraycopy(buffer, startOfValueInBuffer, fallBackCopyBuffer, fallBackCopyBufferLength, length);
            }
            fallBackCopyBufferLength += length;
        }

        startOfValueInBuffer = endOfValueInBuffer = -1;
    }

    private void doAutoAdjust(final int length) {
        if (!autoAdjust) {
            throw new ArrayIndexOutOfBoundsException("Buffer too small for such a long string");
        }

        final char[] newArray = new char[fallBackCopyBuffer.length + Math.max(getBufferExtends(fallBackCopyBuffer.length), length)];
        // TODO: log to adjust size once?
        System.arraycopy(fallBackCopyBuffer, 0, newArray, 0, fallBackCopyBufferLength);
        if (startOfValueInBuffer != -1) {
            System.arraycopy(buffer, startOfValueInBuffer, newArray, fallBackCopyBufferLength, length);
        }
        if (releaseFallBackCopyBufferLength) {
            bufferProvider.release(fallBackCopyBuffer);
            releaseFallBackCopyBufferLength = false;
        }
        fallBackCopyBuffer = newArray;
    }

    protected int getBufferExtends(final int currentLength) {
        return Math.min(1024, currentLength / 4);
    }

    public boolean hasNext() {
        if (rewindedEvent != null) {
            return true;
        }

        if (currentStructureElement != null || previousEvent == 0) {
            return true;
        }
        if (previousEvent != END_ARRAY.ordinal() && previousEvent != END_OBJECT.ordinal() &&
                previousEvent != VALUE_STRING.ordinal() && previousEvent != VALUE_FALSE.ordinal() && previousEvent != VALUE_TRUE.ordinal() &&
                previousEvent != VALUE_NULL.ordinal() && previousEvent != VALUE_NUMBER.ordinal()) {
            if (bufferPos < 0) { // check we don't have an empty string to parse
                final char c = readNextChar();
                unreadChar();
                return c != Character.MIN_VALUE; // EOF
            }
            return true;
        }

        //detect garbage at the end of the file after last object or array is closed
        if (bufferPos < availableCharsInBuffer) {
            final char c = readNextNonWhitespaceChar(readNextChar());
            if (c == Character.MIN_VALUE) {
                return false;
            }
            if (bufferPos < availableCharsInBuffer) {
                throw unexpectedChar("EOF expected");
            }
        }
        return false;

    }

    private static boolean isAsciiDigit(final char value) {
        return value <= '9' && value >= '0';
    }

    private int parseHexDigit(final char value) {
        if (isAsciiDigit(value)) {
            return value - 48;
        }
        if (value <= 'f' && value >= 'a') {
            return (value) - 87;
        }
        if ((value <= 'F' && value >= 'A')) {
            return (value) - 55;
        }
        throw unexpectedChar("Invalid hex character");
    }

    private String createLocation() {
        long column = 1;
        long charOffset = 0;
        if (bufferPos >= -1) {
            charOffset = pastBufferReadCount + bufferPos + 1;
            column = lastLineBreakPosition == 0 ? charOffset + 1 : charOffset - lastLineBreakPosition;
        }
        return "currentLine=" + currentLine + ",column=" + column + ",charOffset=" + charOffset;
    }

    protected final char readNextChar() {
        if (bufferLeft == 0) {
            if (startOfValueInBuffer > -1 && endOfValueInBuffer == -1) {
                endOfValueInBuffer = availableCharsInBuffer;
                copyCurrentValue();

                startOfValueInBuffer = 0;
            }

            if (bufferPos >= -1) {
                pastBufferReadCount += availableCharsInBuffer;
            }

            try {
                availableCharsInBuffer = in.read(buffer, 0, buffer.length);
                if (availableCharsInBuffer <= 0) {
                    return Character.MIN_VALUE;
                }
            } catch (final IOException e) {
                close();
                throw new IllegalStateException("Unexpected IO exception on " + createLocation(), e);
            }

            bufferPos = 0;
            bufferLeft = availableCharsInBuffer - 1;
        } else {
            bufferPos++;
            bufferLeft--;
        }
        return buffer[bufferPos];
    }

    protected final char readNextNonWhitespaceChar(char c) {
        while (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            if (c == '\n') {
                currentLine++;
                lastLineBreakPosition = pastBufferReadCount + bufferPos;
            }
            c = readNextChar();
        }
        return c;
    }

    private void unreadChar() {
        bufferPos--;
        bufferLeft++;
    }

    public void rewind(final Event event) {
        rewindedEvent = event;
    }

    public Event next() {
        if (rewindedEvent != null) {
            final var event = rewindedEvent;
            rewindedEvent = null;
            return event;
        }

        if (!hasNext()) {
            final char c = readNextChar();
            unreadChar();
            if (c != Character.MIN_VALUE) {
                throw unexpectedChar("No available event");
            }
            throw new NoSuchElementException();
        }

        if (previousEvent > 0 && currentStructureElement == null) {
            throw unexpectedChar("Unexpected end of structure");
        }

        final char c = readNextNonWhitespaceChar(readNextChar());
        if (c == ',') {
            //last event must one of the following-> " ] } LITERAL
            if (previousEvent == Byte.MIN_VALUE || previousEvent == START_ARRAY.ordinal()
                    || previousEvent == START_OBJECT.ordinal() || previousEvent == Byte.MAX_VALUE
                    || previousEvent == KEY_NAME.ordinal()) {
                throw unexpectedChar("Expected \" ] } LITERAL");
            }

            previousEvent = Byte.MAX_VALUE;
            return next();
        }

        if (c == ':') {
            if (previousEvent != KEY_NAME.ordinal()) {
                throw unexpectedChar("A : can only follow a key name");
            }
            previousEvent = Byte.MIN_VALUE;
            return next();
        }
        if (!isCurrentNumberIntegral) {
            isCurrentNumberIntegral = true;
        }
        if (currentIntegralNumber != Integer.MIN_VALUE) {
            currentIntegralNumber = Integer.MIN_VALUE;
        }
        if (fallBackCopyBufferLength != 0) {
            fallBackCopyBufferLength = 0;
        }

        startOfValueInBuffer = endOfValueInBuffer = -1;
        switch (c) {
            case '{':
                return handleStartObject();
            case '}':
                return handleEndObject();
            case '[':
                return handleStartArray();
            case ']':
                return handleEndArray();
            case '"':
                return handleQuote();
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
            case '-':
            case 'f':
            case 't':
            case 'n':
                return handleLiteral();
            default:
                return defaultHandling(c);
        }
    }

    protected Event defaultHandling(char c) {
        throw c == Character.MIN_VALUE ?
                unexpectedChar("End of file hit too early") :
                unexpectedChar("Expected structural character or digit or 't' or 'n' or 'f' or '-'");
    }

    private Event handleStartObject() {
        if (previousEvent > 0 && previousEvent != Byte.MAX_VALUE) {
            throw unexpectedChar("Expected : , [");
        }

        if (currentStructureElement == null) {
            currentStructureElement = new StructureElement(null, false);
        } else {
            if (!currentStructureElement.isArray && previousEvent != Byte.MIN_VALUE) {
                throw unexpectedChar("Expected :");
            }
            currentStructureElement = new StructureElement(currentStructureElement, false);
        }
        return EVT_MAP[previousEvent = (byte) START_OBJECT.ordinal()];
    }

    private Event handleEndObject() {
        if (previousEvent == START_ARRAY.ordinal()
                || previousEvent == Byte.MAX_VALUE
                || previousEvent == KEY_NAME.ordinal()
                || previousEvent == Byte.MIN_VALUE
                || currentStructureElement == null) {
            throw unexpectedChar("Expected \" ] { } LITERAL");
        }

        if (currentStructureElement.isArray) {
            throw unexpectedChar("Expected : ]");
        }

        currentStructureElement = currentStructureElement.previous;
        return EVT_MAP[previousEvent = (byte) END_OBJECT.ordinal()];
    }

    private Event handleStartArray() {
        if (previousEvent > 0 && previousEvent != Byte.MAX_VALUE) {
            throw unexpectedChar("Expected : , [");
        }

        if (currentStructureElement == null) {
            currentStructureElement = new StructureElement(null, true);
        } else {
            if (!currentStructureElement.isArray && previousEvent != Byte.MIN_VALUE) {
                throw unexpectedChar("Expected \"");
            }
            currentStructureElement = new StructureElement(currentStructureElement, true);
        }
        return EVT_MAP[previousEvent = (byte) START_ARRAY.ordinal()];
    }

    private Event handleEndArray() {
        if (previousEvent == START_OBJECT.ordinal() || previousEvent == Byte.MAX_VALUE || previousEvent == Byte.MIN_VALUE
                || currentStructureElement == null) {
            throw unexpectedChar("Expected [ ] } \" LITERAL");
        }

        if (!currentStructureElement.isArray) {
            throw unexpectedChar("Expected : }");
        }

        currentStructureElement = currentStructureElement.previous;
        return EVT_MAP[previousEvent = (byte) END_ARRAY.ordinal()];
    }

    private void readString() {
        do {
            char n = readNextChar();
            if (n == '"') {
                endOfValueInBuffer = startOfValueInBuffer = bufferPos; //->"" case
                return;
            }
            if (n == '\n') {
                throw unexpectedChar("Unexpected linebreak");
            }
            if (/* n >= '\u0000' && */ n <= '\u001F') {
                throw unexpectedChar("Unescaped control character");
            }
            if (n == '\\') {
                n = readNextChar();
                if (this.fallBackCopyBuffer == null) {
                    this.fallBackCopyBuffer = bufferProvider.newBuffer();
                }
                if (n == 'u') {
                    n = parseUnicodeHexChars();
                    appendToCopyBuffer(n);
                } else if (n == '\\') {
                    appendToCopyBuffer(n);
                } else {
                    appendToCopyBuffer(JsonStrings.asEscapedChar(n));
                }
            } else {
                startOfValueInBuffer = bufferPos;
                endOfValueInBuffer = -1;

                while ((n = readNextChar()) > '\u001F' && n != '\\' && n != '"') {
                    // read fast
                }

                endOfValueInBuffer = bufferPos;

                if (n == '"') {
                    if (fallBackCopyBufferLength > 0) {
                        copyCurrentValue();
                    }
                    return;
                }
                if (n == '\n') {
                    throw unexpectedChar("Unexpected linebreak");
                }
                if (n <= '\u001F') {
                    throw unexpectedChar("Unescaped control character");
                }
                copyCurrentValue();
                unreadChar();
            }
        } while (true);
    }

    private char parseUnicodeHexChars() {
        return (char) (((parseHexDigit(readNextChar())) * 4096) + ((parseHexDigit(readNextChar())) * 256)
                + ((parseHexDigit(readNextChar())) * 16) + ((parseHexDigit(readNextChar()))));
    }

    private Event handleQuote() {
        if (previousEvent != -1 &&
                (previousEvent != Byte.MIN_VALUE &&
                        previousEvent != START_OBJECT.ordinal() &&
                        previousEvent != START_ARRAY.ordinal() &&
                        previousEvent != Byte.MAX_VALUE)) {
            throw unexpectedChar("Expected : { [ ,");
        }
        readString();

        if (previousEvent == Byte.MIN_VALUE) {
            if (currentStructureElement != null && currentStructureElement.isArray) {
                //not in array, only allowed within array
                throw unexpectedChar("Key value pair not allowed in an array");
            }
            return EVT_MAP[previousEvent = (byte) VALUE_STRING.ordinal()];
        }
        if (currentStructureElement == null || currentStructureElement.isArray) {
            return EVT_MAP[previousEvent = (byte) VALUE_STRING.ordinal()];
        }
        return EVT_MAP[previousEvent = (byte) KEY_NAME.ordinal()];
    }

    private void readNumber() {
        char c = buffer[bufferPos];
        startOfValueInBuffer = bufferPos;
        endOfValueInBuffer = -1;

        char y;
        int cumulatedDigitValue = 0;
        while (isAsciiDigit(y = readNextChar())) {
            if (c == '0') {
                throw unexpectedChar("Leading zeros not allowed");
            }
            if (c == '-' && cumulatedDigitValue == 48) {
                throw unexpectedChar("Leading zeros after minus not allowed");
            }
            cumulatedDigitValue += y;
        }
        if (c == '-' && cumulatedDigitValue == 0) {
            throw unexpectedChar("Unexpected premature end of number");
        }

        if (y == '.') {
            isCurrentNumberIntegral = false;
            cumulatedDigitValue = 0;
            while (isAsciiDigit(y = readNextChar())) {
                cumulatedDigitValue++;
            }
            if (cumulatedDigitValue == 0) {
                throw unexpectedChar("Unexpected premature end of number");
            }
        }

        if (y == 'e' || y == 'E') {
            isCurrentNumberIntegral = false;
            y = readNextChar(); //+ or - or digit
            if (!isAsciiDigit(y) && y != '-' && y != '+') {
                throw unexpectedChar("Expected DIGIT or + or -");
            }

            if (y == '-' || y == '+') {
                y = readNextChar();
                if (!isAsciiDigit(y)) {
                    throw unexpectedChar("Unexpected premature end of number");
                }
            }

            while (isAsciiDigit(y = readNextChar())) {
                //no-op
            }
        }

        endOfValueInBuffer = y == Character.MIN_VALUE && endOfValueInBuffer < 0 ? -1 : bufferPos;
        if (y == ',' || y == ']' || y == '}' || y == '\n' || y == ' ' || y == '\t' || y == '\r' || y == Character.MIN_VALUE) {
            unreadChar();
            if (isCurrentNumberIntegral && c == '-' && cumulatedDigitValue >= 48 && cumulatedDigitValue <= 57) {
                currentIntegralNumber = -(cumulatedDigitValue - 48); //optimize -0 till -9
                return;
            }

            if (isCurrentNumberIntegral && c != '-' && cumulatedDigitValue == 0) {
                currentIntegralNumber = (c - 48); //optimize 0 till 9
                return;
            }

            if (fallBackCopyBufferLength > 0) {
                copyCurrentValue();
            }
            return;
        }
        throw unexpectedChar("Unexpected premature end of number");
    }

    private Event handleLiteral() {
        if (previousEvent != -1 && previousEvent != Byte.MIN_VALUE && previousEvent != START_ARRAY.ordinal() && previousEvent != Byte.MAX_VALUE) {
            throw unexpectedChar("Expected : , [");
        }

        if (previousEvent == Byte.MAX_VALUE && !currentStructureElement.isArray) {
            throw unexpectedChar("Not in an array context");
        }

        char c = buffer[bufferPos];
        switch (c) {
            case 't': {
                if (readNextChar() != 'r' || readNextChar() != 'u' || readNextChar() != 'e') {
                    throw unexpectedChar("Expected LITERAL: true");
                }
                return EVT_MAP[previousEvent = (byte) VALUE_TRUE.ordinal()];
            }
            case 'f': {
                if (readNextChar() != 'a' || readNextChar() != 'l' || readNextChar() != 's' || readNextChar() != 'e') {
                    throw unexpectedChar("Expected LITERAL: false");
                }
                return EVT_MAP[previousEvent = (byte) VALUE_FALSE.ordinal()];
            }
            case 'n': {
                if (readNextChar() != 'u' || readNextChar() != 'l' || readNextChar() != 'l') {
                    throw unexpectedChar("Expected LITERAL: null");
                }
                return EVT_MAP[previousEvent = (byte) VALUE_NULL.ordinal()];
            }
            default: {
                readNumber();
                return EVT_MAP[previousEvent = (byte) VALUE_NUMBER.ordinal()];
            }
        }
    }

    public String getString() {
        if (previousEvent == KEY_NAME.ordinal() || previousEvent == VALUE_STRING.ordinal() || previousEvent == VALUE_NUMBER.ordinal()) {
            return fallBackCopyBufferLength > 0 ?
                    new String(fallBackCopyBuffer, 0, fallBackCopyBufferLength) :
                    new String(buffer, startOfValueInBuffer, endOfValueInBuffer - startOfValueInBuffer);
        }
        throw new IllegalStateException(EVT_MAP[previousEvent] + " doesn't support getString()");
    }

    public void enforceNext(final Event event) {
        if (!hasNext()) {
            throw new IllegalStateException("Expected " + event + " stream is finished.");
        }
        final var next = next();
        if (next != event) {
            throw new IllegalStateException("Expected " + event + " but got " + next);
        }
    }

    public BigDecimal getBigDecimal() {
        if (previousEvent != VALUE_NUMBER.ordinal()) {
            throw new IllegalStateException(EVT_MAP[previousEvent] + " doesn't support getBigDecimal()");
        }
        if (isCurrentNumberIntegral && currentIntegralNumber != Integer.MIN_VALUE) {
            return new BigDecimal(currentIntegralNumber);
        }
        return fallBackCopyBufferLength > 0 ?
                new BigDecimal(fallBackCopyBuffer, 0, fallBackCopyBufferLength) :
                new BigDecimal(buffer, startOfValueInBuffer, (endOfValueInBuffer - startOfValueInBuffer));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        bufferProvider.release(buffer);
        if (releaseFallBackCopyBufferLength && fallBackCopyBuffer != null) {
            bufferProvider.release(fallBackCopyBuffer);
        }

        try {
            in.close();
        } catch (final IOException e) {
            throw new IllegalStateException("Unexpected IO exception " + e.getMessage(), e);
        } finally {
            closed = true;
        }
    }

    private IllegalStateException unexpectedChar(final String message) {
        final char c = bufferPos < 0 ? 0 : buffer[bufferPos];
        return new IllegalStateException("Unexpected character '" + c + "' (Codepoint: " + String.valueOf(c).codePointAt(0) + ") on "
                + createLocation() + ". Reason is [[" + message + "]]");
    }

    @Data
    @Accessors(fluent = true)
    private static class StructureElement {
        private final StructureElement previous;
        private final boolean isArray;
    }

    public enum Event {
        START_ARRAY,
        START_OBJECT,
        KEY_NAME,
        VALUE_STRING,
        VALUE_NUMBER,
        VALUE_TRUE,
        VALUE_FALSE,
        VALUE_NULL,
        END_OBJECT,
        END_ARRAY
    }
}