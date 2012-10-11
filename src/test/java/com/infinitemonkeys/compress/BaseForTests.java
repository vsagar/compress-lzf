package com.infinitemonkeys.compress;

import java.io.*;
import java.util.Random;

public class BaseForTests
{
    private final static byte[] ABCD = new byte[] { 'a', 'b', 'c', 'd' };
    
    protected byte[] constructFluff(int length) throws IOException
    {
        Random rnd = new Random(length);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(length + 100);
        while (bytes.size() < length) {
            int num = rnd.nextInt();
            switch (num & 3) {
            case 0:
                bytes.write(ABCD);
                break;
            case 1:
                bytes.write(num);
                break;
            default:
                bytes.write((num >> 3) & 0x7);
                break;
            }
        }
        return bytes.toByteArray();
    }

    protected byte[] constructUncompressable(int length)
    {
        byte[] result = new byte[length];
        Random rnd = new Random(length);
        // SecureRandom is "more random", but not reproduceable, so use default instead:
//        SecureRandom.getInstance("SHA1PRNG").nextBytes(result);
        rnd.nextBytes(result);
        return result;
    }

    protected byte[] readAll(InputStream in) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1000);
        byte[] buf = new byte[1000];
        int count;
        
        while ((count = in.read(buf)) > 0) {
            bytes.write(buf, 0, count);
        }
        in.close();
        return bytes.toByteArray();
    }
}
