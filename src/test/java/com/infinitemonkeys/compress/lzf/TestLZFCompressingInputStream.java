package com.infinitemonkeys.compress.lzf;

import java.io.*;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.infinitemonkeys.compress.BaseForTests;
import com.infinitemonkeys.compress.lzf.LZFCompressingInputStream;
import com.infinitemonkeys.compress.lzf.LZFDecoder;
import com.infinitemonkeys.compress.lzf.LZFEncoder;

public class TestLZFCompressingInputStream extends BaseForTests
{
    @Test
    public void testSimpleCompression() throws IOException
    {
        // produce multiple chunks, about 3 here:
        byte[] source = constructFluff(140000);
        LZFCompressingInputStream compIn = new LZFCompressingInputStream(new ByteArrayInputStream(source));
        byte[] comp = readAll(compIn);
        byte[] uncomp = LZFDecoder.decode(comp);
        Assert.assertEquals(uncomp, source);

        // and then check that size is about same as with static methods
        byte[] comp2 = LZFEncoder.encode(source);
        Assert.assertEquals(comp2.length, comp.length);
    }

    @Test
    public void testSimpleNonCompressed() throws IOException
    {
        // produce two chunks as well
        byte[] source = this.constructUncompressable(89000);
        LZFCompressingInputStream compIn = new LZFCompressingInputStream(new ByteArrayInputStream(source));
        byte[] comp = readAll(compIn);
        // 2 non-compressed chunks with headers:
        Assert.assertEquals(comp.length, 89000 + 5 + 5);
        byte[] uncomp = LZFDecoder.decode(comp);
        Assert.assertEquals(uncomp, source);
    }
}
