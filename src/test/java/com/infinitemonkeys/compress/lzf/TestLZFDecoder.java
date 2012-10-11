package com.infinitemonkeys.compress.lzf;

import java.io.*;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.infinitemonkeys.compress.lzf.LZFDecoder;
import com.infinitemonkeys.compress.lzf.LZFEncoder;
import com.infinitemonkeys.compress.lzf.util.ChunkDecoderFactory;

public class TestLZFDecoder
{
    @Test
    public void testSimple() throws IOException
    {
        byte[] orig = "Another trivial test".getBytes("UTF-8");
        byte[] compressed = LZFEncoder.encode(orig);
        byte[] result = ChunkDecoderFactory.optimalInstance().decode(compressed);
        Assert.assertEquals(result, orig);

        // also, ensure that offset, length are passed
        byte[] compressed2 = new byte[compressed.length + 4];
        System.arraycopy(compressed, 0, compressed2, 2, compressed.length);

        result = ChunkDecoderFactory.optimalInstance().decode(compressed2, 2, compressed.length);
        Assert.assertEquals(result, orig);

        // two ways to do that as well:
        result = LZFDecoder.decode(compressed2, 2, compressed.length);
        Assert.assertEquals(result, orig);
    }

    @Test
    public void testChunks() throws IOException
    {
        byte[] orig1 = "Another trivial test".getBytes("UTF-8");
        byte[] orig2 = " with some of repepepepepetitition too!".getBytes("UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(orig1);
        out.write(orig2);
        byte[] orig = out.toByteArray();

        byte[] compressed1 = LZFEncoder.encode(orig1);
        byte[] compressed2 = LZFEncoder.encode(orig2);
        out = new ByteArrayOutputStream();
        out.write(compressed1);
        out.write(compressed2);
        byte[] compressed = out.toByteArray();
        
        byte[] result = ChunkDecoderFactory.optimalInstance().decode(compressed);
        Assert.assertEquals(result, orig);
   }
}
