/* Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.infinitemonkeys.compress.lzf;

import java.io.IOException;
import java.io.OutputStream;

import com.infinitemonkeys.compress.BufferRecycler;

/**
 * Class that handles actual encoding of individual chunks.
 * Resulting chunks can be compressed or non-compressed; compression
 * is only used if it actually reduces chunk size (including overhead
 * of additional header bytes)
 * 
 * @author Vidya
 */
public final class ChunkEncoder
{
    // Beyond certain point we won't be able to compress; let's use 16 bytes as cut-off
    private static final int MIN_BLOCK_TO_COMPRESS = 16;

    private static final int MIN_HASH_SIZE = 256;

    // Not much point in bigger tables, with 8k window
    private static final int MAX_HASH_SIZE = 16384;

    private static final int MAX_OFF = 1 << 13; // 8k
    private static final int MAX_REF = (1 << 8) + (1 << 3); // 264
    
    // // Encoding tables etc

    private final BufferRecycler _recycler;

    /**
     * Hash table contains lookup based on 3-byte sequence; key is hash
     * of such triplet, value is offset in buffer.
     */
    private int[] _hashTable;
    
    private final int _hashModulo;

    /**
     * Buffer in which encoded content is stored during processing
     */
    private byte[] _encodeBuffer;

    /**
     * Small buffer passed to LZFChunk, needed for writing chunk header
     */
    private byte[] _headerBuffer;
    
    /**
     * @param totalLength Total encoded length; used for calculating size
     *   of hash table to use
     */
    public ChunkEncoder(int totalLength)
    {
        int largestChunkLen = Math.max(totalLength, LZFChunk.MAX_CHUNK_LEN);
        
        int suggestedHashLen = calcHashLen(largestChunkLen);
        _recycler = BufferRecycler.instance();
        _hashTable = _recycler.allocEncodingHash(suggestedHashLen);
        _hashModulo = _hashTable.length - 1;
        // Ok, then, what's the worst case output buffer length?
        // length indicator for each 32 literals, so:
        int bufferLen = largestChunkLen + ((largestChunkLen + 31) >> 5);
        _encodeBuffer = _recycler.allocEncodingBuffer(bufferLen);
    }

    /**
     * Alternate constructor used when we want to avoid allocation encoding
     * buffer, in cases where caller wants full control over allocations.
     */
    private ChunkEncoder(int totalLength, boolean bogus)
    {
        int largestChunkLen = Math.max(totalLength, LZFChunk.MAX_CHUNK_LEN);
        int suggestedHashLen = calcHashLen(largestChunkLen);
        _recycler = BufferRecycler.instance();
        _hashTable = _recycler.allocEncodingHash(suggestedHashLen);
        _hashModulo = _hashTable.length - 1;
        _encodeBuffer = null;
    }

    public static ChunkEncoder nonAllocatingEncoder(int totalLength) {
        return new ChunkEncoder(totalLength, true);
    }
    
    /*
    ///////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////
     */
    
    /**
     * Method to close once encoder is no longer in use. Note: after calling
     * this method, further calls to {@link #encodeChunk} will fail
     */
    public void close()
    {
        byte[] buf = _encodeBuffer;
        if (buf != null) {
            _encodeBuffer = null;
            _recycler.releaseEncodeBuffer(buf);
        }
        int[] ibuf = _hashTable;
        if (ibuf != null) {
            _hashTable = null;
            _recycler.releaseEncodingHash(ibuf);
        }
    }
    
    /**
     * Method for compressing (or not) individual chunks
     */
    public LZFChunk encodeChunk(byte[] data, int offset, int len)
    {
        if (len >= MIN_BLOCK_TO_COMPRESS) {
            /* If we have non-trivial block, and can compress it by at least
             * 2 bytes (since header is 2 bytes longer), let's compress:
             */
            int compLen = tryCompress(data, offset, offset+len, _encodeBuffer, 0);
            if (compLen < (len-2)) { // nah; just return uncompressed
                return LZFChunk.createCompressed(len, _encodeBuffer, 0, compLen);
            }
        }
        // Otherwise leave uncompressed:
        return LZFChunk.createNonCompressed(data, offset, len);
    }

    /**
     * Method for encoding individual chunk, writing it to given output stream.
     */
    public void encodeAndWriteChunk(byte[] data, int offset, int len, OutputStream out)
        throws IOException
    {
        byte[] headerBuf = _headerBuffer;
        if (headerBuf == null) {
            _headerBuffer = headerBuf = new byte[LZFChunk.MAX_HEADER_LEN];
        }
        if (len >= MIN_BLOCK_TO_COMPRESS) {
            /* If we have non-trivial block, and can compress it by at least
             * 2 bytes (since header is 2 bytes longer), let's compress:
             */
            int compLen = tryCompress(data, offset, offset+len, _encodeBuffer, 0);
            if (compLen < (len-2)) { // nah; just return uncompressed
                LZFChunk.writeCompressedHeader(len, compLen, out, headerBuf);
                out.write(_encodeBuffer, 0, compLen);
                return;
            }
        }
        // Otherwise leave uncompressed:
        LZFChunk.writeNonCompressedHeader(len, out, headerBuf);
        out.write(data, offset, len);
    }

    /**
     * Main workhorse method that will try to compress given chunk, and return
     * end position (offset to byte after last included byte)
     */
    protected int tryCompress(byte[] in, int inPos, int inEnd, byte[] out, int outPos)
    {
        final int[] hashTable = _hashTable;
        ++outPos;
        int seen = first(in, 0); // past 4 bytes we have seen... (last one is LSB)
        int literals = 0;
        inEnd -= 4;
        final int firstPos = inPos; // so that we won't have back references across block boundary
        
        while (inPos < inEnd) {
            byte p2 = in[inPos + 2];
            // next
            seen = (seen << 8) + (p2 & 255);
            int off = hash(seen);
            int ref = hashTable[off];
            hashTable[off] = inPos;
  
            // First expected common case: no back-ref (for whatever reason)
            if (ref >= inPos // can't refer forward (i.e. leftovers)
                    || ref < firstPos // or to previous block
                    || (off = inPos - ref) > MAX_OFF
                    || in[ref+2] != p2 // must match hash
                    || in[ref+1] != (byte) (seen >> 8)
                    || in[ref] != (byte) (seen >> 16)) {
                out[outPos++] = in[inPos++];
                literals++;
                if (literals == LZFChunk.MAX_LITERAL) {
                    out[outPos - 33] = (byte) 31; // <= out[outPos - literals - 1] = MAX_LITERAL_MINUS_1;
                    literals = 0;
                    outPos++;
                }
                continue;
            }
            // match
            int maxLen = inEnd - inPos + 2;
            if (maxLen > MAX_REF) {
                maxLen = MAX_REF;
            }
            if (literals == 0) {
                outPos--;
            } else {
                out[outPos - literals - 1] = (byte) (literals - 1);
                literals = 0;
            }
            int len = 3;
            while (len < maxLen && in[ref + len] == in[inPos + len]) {
                len++;
            }
            len -= 2;
            --off; // was off by one earlier
            if (len < 7) {
                out[outPos++] = (byte) ((off >> 8) + (len << 5));
            } else {
                out[outPos++] = (byte) ((off >> 8) + (7 << 5));
                out[outPos++] = (byte) (len - 7);
            }
            out[outPos++] = (byte) off;
            outPos++;
            inPos += len;
            seen = first(in, inPos);
            seen = (seen << 8) + (in[inPos + 2] & 255);
            hashTable[hash(seen)] = inPos;
            ++inPos;
            seen = (seen << 8) + (in[inPos + 2] & 255); // hash = next(hash, in, inPos);
            hashTable[hash(seen)] = inPos;
            ++inPos;
        }
        // try offlining the tail
        return handleTail(in, inPos, inEnd+4, out, outPos, literals);
    }

    private final int handleTail(byte[] in, int inPos, int inEnd, byte[] out, int outPos,
            int literals)
    {
        while (inPos < inEnd) {
            out[outPos++] = in[inPos++];
            literals++;
            if (literals == LZFChunk.MAX_LITERAL) {
                out[outPos - literals - 1] = (byte) (literals - 1);
                literals = 0;
                outPos++;
            }
        }
        out[outPos - literals - 1] = (byte) (literals - 1);
        if (literals == 0) {
            outPos--;
        }
        return outPos;
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // Internal methods
    ///////////////////////////////////////////////////////////////////////
     */
    
    private static int calcHashLen(int chunkSize)
    {
        // in general try get hash table size of 2x input size
        chunkSize += chunkSize;
        // but no larger than max size:
        if (chunkSize >= MAX_HASH_SIZE) {
            return MAX_HASH_SIZE;
        }
        // otherwise just need to round up to nearest 2x
        int hashLen = MIN_HASH_SIZE;
        while (hashLen < chunkSize) {
            hashLen += hashLen;
        }
        return hashLen;
    }

    private final int first(byte[] in, int inPos) {
        return (in[inPos] << 8) + (in[inPos + 1] & 0xFF);
    }

    /*
    private static int next(int v, byte[] in, int inPos) {
        return (v << 8) + (in[inPos + 2] & 255);
    }
*/

    private final int hash(int h) {
        // or 184117; but this seems to give better hashing?
        return ((h * 57321) >> 9) & _hashModulo;
        // original lzf-c.c used this:
        //return (((h ^ (h << 5)) >> (24 - HLOG) - h*5) & _hashModulo;
        // but that didn't seem to provide better matches
    }
    
    /*
    ///////////////////////////////////////////////////////////////////////
    // Alternative experimental version using Unsafe
    // NOTE: not currently used, retained for future inspiration...
    ///////////////////////////////////////////////////////////////////////
     */

    /*
    private static final Unsafe unsafe;
    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = (Unsafe) theUnsafe.get(null);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long BYTE_ARRAY_OFFSET = unsafe.arrayBaseOffset(byte[].class);

    private final int MASK = 0xFFFFFF;
    
    private final int get3Bytes(byte[] src, int srcIndex)
    {
        return unsafe.getInt(src, BYTE_ARRAY_OFFSET + srcIndex) & MASK;
    }

    private int tryCompress(byte[] in, int inPos, int inEnd, byte[] out, int outPos)
    {
        final int[] hashTable = _hashTable;
        ++outPos;
        int literals = 0;
        inEnd -= 4;
        final int firstPos = inPos; // so that we won't have back references across block boundary
        
        while (inPos < inEnd) {
            int seen = get3Bytes(in, inPos);
            int off = hash(seen);
            int ref = hashTable[off];
            hashTable[off] = inPos;
  
            // First expected common case: no back-ref (for whatever reason)
            if (ref >= inPos // can't refer forward (i.e. leftovers)
                    || ref < firstPos // or to previous block
                    || (off = inPos - ref) > MAX_OFF
                    || get3Bytes(in, ref) != seen
                    ) {
                out[outPos++] = in[inPos++];
                literals++;
                if (literals == LZFChunk.MAX_LITERAL) {
                    out[outPos - 33] = (byte) 31; // <= out[outPos - literals - 1] = MAX_LITERAL_MINUS_1;
                    literals = 0;
                    outPos++;
                }
                continue;
            }
            // match
            int maxLen = inEnd - inPos + 2;
            if (maxLen > MAX_REF) {
                maxLen = MAX_REF;
            }
            if (literals == 0) {
                outPos--;
            } else {
                out[outPos - literals - 1] = (byte) (literals - 1);
                literals = 0;
            }
            int len = 3;
            while (len < maxLen && in[ref + len] == in[inPos + len]) {
                len++;
            }
            len -= 2;
            --off; // was off by one earlier
            if (len < 7) {
                out[outPos++] = (byte) ((off >> 8) + (len << 5));
            } else {
                out[outPos++] = (byte) ((off >> 8) + (7 << 5));
                out[outPos++] = (byte) (len - 7);
            }
            out[outPos] = (byte) off;
            outPos += 2;
            inPos += len;
            hashTable[hash(get3Bytes(in, inPos))] = inPos;
            ++inPos;
            hashTable[hash(get3Bytes(in, inPos))] = inPos;
            ++inPos;
        }
        // try offlining the tail
        return handleTail(in, inPos, inEnd+4, out, outPos, literals);
    }
    */
}
