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

/**
 * Encoder that handles splitting of input into chunks to encode,
 * calls {@link ChunkEncoder} to compress individual chunks and
 * combines resulting chunks into contiguous output byte array.
 * 
 * @author Vidya
 */
public class LZFEncoder
{
    // Static methods only, no point in instantiating
    private LZFEncoder() { }
    
    public static byte[] encode(byte[] data) throws IOException
    {
    	return encode(data, data.length);
    }
    
    /**
     * Method for compressing given input data using LZF encoding and
     * block structure (compatible with lzf command line utility).
     * Result consists of a sequence of chunks.
     */
    public static byte[] encode(byte[] data, int length) throws IOException
    {
        return encode(data, 0, length);
    }

    /**
     * Method for compressing given input data using LZF encoding and
     * block structure (compatible with lzf command line utility).
     * Result consists of a sequence of chunks.
     * 
     * @since 0.8.1
     */
    public static byte[] encode(byte[] data, int offset, int length) throws IOException
    {
        ChunkEncoder enc = new ChunkEncoder(length);
        byte[] result = encode(enc, data, offset, length);
        // important: may be able to reuse buffers
        enc.close();
        return result;
    }
    
    public static byte[] encode(ChunkEncoder enc, byte[] data, int length)
        throws IOException
    {
        return encode(enc, data, 0, length);
    }

    /**
     * @since 0.8.1
     */
    public static byte[] encode(ChunkEncoder enc, byte[] data, int offset, int length)
        throws IOException
    {
        int left = length;
        int chunkLen = Math.min(LZFChunk.MAX_CHUNK_LEN, left);
        LZFChunk first = enc.encodeChunk(data, offset, chunkLen);
        left -= chunkLen;
        // shortcut: if it all fit in, no need to coalesce:
        if (left < 1) {
            return first.getData();
        }
        // otherwise need to get other chunks:
        int resultBytes = first.length();
        offset += chunkLen;
        LZFChunk last = first;

        do {
            chunkLen = Math.min(left, LZFChunk.MAX_CHUNK_LEN);
            LZFChunk chunk = enc.encodeChunk(data, offset, chunkLen);
            offset += chunkLen;
            left -= chunkLen;
            resultBytes += chunk.length();
            last.setNext(chunk);
            last = chunk;
        } while (left > 0);
        // and then coalesce returns into single contiguous byte array
        byte[] result = new byte[resultBytes];
        int ptr = 0;
        for (; first != null; first = first.next()) {
            ptr = first.copyTo(result, ptr);
        }
        return result;
    }
}
