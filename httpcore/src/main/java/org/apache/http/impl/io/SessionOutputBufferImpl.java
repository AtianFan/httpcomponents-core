/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

import org.apache.http.Consts;
import org.apache.http.annotation.NotThreadSafe;
import org.apache.http.io.BufferInfo;
import org.apache.http.io.HttpTransportMetrics;
import org.apache.http.io.SessionOutputBuffer;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;
import org.apache.http.util.ByteArrayBuffer;
import org.apache.http.util.CharArrayBuffer;

/**
 * Abstract base class for session output buffers that stream data to
 * an arbitrary {@link OutputStream}. This class buffers small chunks of
 * output data in an internal byte array for optimal output performance.
 * </p>
 * {@link #writeLine(CharArrayBuffer)} and {@link #writeLine(String)} methods
 * of this class use CR-LF as a line delimiter.
 *
 * @since 4.3
 */
@NotThreadSafe
public class SessionOutputBufferImpl implements SessionOutputBuffer, BufferInfo {

    private static final byte[] CRLF = new byte[] {HTTP.CR, HTTP.LF};

    private final HttpTransportMetricsImpl metrics;
    private final ByteArrayBuffer buffer;
    private final Charset charset;
    private final boolean ascii;
    private final int minChunkLimit;
    private final CodingErrorAction onMalformedCharAction;
    private final CodingErrorAction onUnmappableCharAction;

    private OutputStream outstream;
    private CharsetEncoder encoder;
    private ByteBuffer bbuf;

    /**
     * Creates new instance of SessionOutputBufferImpl.
     *
     * @param metrics HTTP transport metrics.
     * @param buffersize buffer size. Must be a positive number.
     * @param charset charset to be used for encoding HTTP protocol elements.
     *   If <code>null</code> US-ASCII will be used.
     * @param minChunkLimit size limit below which data chunks should be buffered in memory
     *   in order to minimize native method invocations on the underlying network socket.
     *   The optimal value of this parameter can be platform specific and defines a trade-off
     *   between performance of memory copy operations and that of native method invocation.
     *   If negative default chunk limited will be used.
     * @param malformedCharAction action to perform upon receiving a malformed input.
     *   If <code>null</code> {@link CodingErrorAction#REPORT} will be used.
     * @param unmappableCharAction action to perform upon receiving an unmappable input.
     *   If <code>null</code> {@link CodingErrorAction#REPORT}  will be used.
     */
    public SessionOutputBufferImpl(
            final HttpTransportMetricsImpl metrics,
            int buffersize,
            int minChunkLimit,
            final Charset charset,
            final CodingErrorAction malformedCharAction,
            final CodingErrorAction unmappableCharAction) {
        super();
        Args.positive(buffersize, "Buffer size");
        Args.notNull(metrics, "HTTP transport metrcis");
        this.metrics = metrics;
        this.buffer = new ByteArrayBuffer(buffersize);
        this.charset = charset != null ? charset : Consts.ASCII;
        this.ascii = this.charset.equals(Consts.ASCII);
        this.encoder = null;
        this.minChunkLimit = minChunkLimit >= 0 ? minChunkLimit : 512;
        this.onMalformedCharAction = malformedCharAction != null ? malformedCharAction :
            CodingErrorAction.REPORT;
        this.onUnmappableCharAction = unmappableCharAction != null? unmappableCharAction :
            CodingErrorAction.REPORT;
    }

    public void bind(final OutputStream outstream) {
        this.outstream = outstream;
    }

    public int capacity() {
        return this.buffer.capacity();
    }

    public int length() {
        return this.buffer.length();
    }

    public int available() {
        return capacity() - length();
    }

    private void streamWrite(final byte[] b, int off, int len) throws IOException {
        Asserts.notNull(outstream, "Output stream");
        this.outstream.write(b, off, len);
    }

    private void flushStream() throws IOException {
        Asserts.notNull(outstream, "Output stream");
        this.outstream.flush();
    }

    private void flushBuffer() throws IOException {
        int len = this.buffer.length();
        if (len > 0) {
            streamWrite(this.buffer.buffer(), 0, len);
            this.buffer.clear();
            this.metrics.incrementBytesTransferred(len);
        }
    }

    public void flush() throws IOException {
        flushBuffer();
        flushStream();
    }

    public void write(final byte[] b, int off, int len) throws IOException {
        if (b == null) {
            return;
        }
        // Do not want to buffer large-ish chunks
        // if the byte array is larger then MIN_CHUNK_LIMIT
        // write it directly to the output stream
        if (len > this.minChunkLimit || len > this.buffer.capacity()) {
            // flush the buffer
            flushBuffer();
            // write directly to the out stream
            streamWrite(b, off, len);
            this.metrics.incrementBytesTransferred(len);
        } else {
            // Do not let the buffer grow unnecessarily
            int freecapacity = this.buffer.capacity() - this.buffer.length();
            if (len > freecapacity) {
                // flush the buffer
                flushBuffer();
            }
            // buffer
            this.buffer.append(b, off, len);
        }
    }

    public void write(final byte[] b) throws IOException {
        if (b == null) {
            return;
        }
        write(b, 0, b.length);
    }

    public void write(int b) throws IOException {
        if (this.buffer.isFull()) {
            flushBuffer();
        }
        this.buffer.append(b);
    }

    /**
     * Writes characters from the specified string followed by a line delimiter
     * to this session buffer.
     * <p>
     * This method uses CR-LF as a line delimiter.
     *
     * @param      s   the line.
     * @exception  IOException  if an I/O error occurs.
     */
    public void writeLine(final String s) throws IOException {
        if (s == null) {
            return;
        }
        if (s.length() > 0) {
            if (this.ascii) {
                for (int i = 0; i < s.length(); i++) {
                    write(s.charAt(i));
                }
            } else {
                CharBuffer cbuf = CharBuffer.wrap(s);
                writeEncoded(cbuf);
            }
        }
        write(CRLF);
    }

    /**
     * Writes characters from the specified char array followed by a line
     * delimiter to this session buffer.
     * <p>
     * This method uses CR-LF as a line delimiter.
     *
     * @param      charbuffer the buffer containing chars of the line.
     * @exception  IOException  if an I/O error occurs.
     */
    public void writeLine(final CharArrayBuffer charbuffer) throws IOException {
        if (charbuffer == null) {
            return;
        }
        if (this.ascii) {
            int off = 0;
            int remaining = charbuffer.length();
            while (remaining > 0) {
                int chunk = this.buffer.capacity() - this.buffer.length();
                chunk = Math.min(chunk, remaining);
                if (chunk > 0) {
                    this.buffer.append(charbuffer, off, chunk);
                }
                if (this.buffer.isFull()) {
                    flushBuffer();
                }
                off += chunk;
                remaining -= chunk;
            }
        } else {
            CharBuffer cbuf = CharBuffer.wrap(charbuffer.buffer(), 0, charbuffer.length());
            writeEncoded(cbuf);
        }
        write(CRLF);
    }

    private void writeEncoded(final CharBuffer cbuf) throws IOException {
        if (!cbuf.hasRemaining()) {
            return;
        }
        if (this.encoder == null) {
            this.encoder = this.charset.newEncoder();
            this.encoder.onMalformedInput(this.onMalformedCharAction);
            this.encoder.onUnmappableCharacter(this.onUnmappableCharAction);
        }
        if (this.bbuf == null) {
            this.bbuf = ByteBuffer.allocate(1024);
        }
        this.encoder.reset();
        while (cbuf.hasRemaining()) {
            CoderResult result = this.encoder.encode(cbuf, this.bbuf, true);
            handleEncodingResult(result);
        }
        CoderResult result = this.encoder.flush(this.bbuf);
        handleEncodingResult(result);
        this.bbuf.clear();
    }

    private void handleEncodingResult(final CoderResult result) throws IOException {
        if (result.isError()) {
            result.throwException();
        }
        this.bbuf.flip();
        while (this.bbuf.hasRemaining()) {
            write(this.bbuf.get());
        }
        this.bbuf.compact();
    }

    public HttpTransportMetrics getMetrics() {
        return this.metrics;
    }

}