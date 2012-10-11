package com.infinitemonkeys.compress.lzf;

import com.infinitemonkeys.compress.CompressionFormatException;

public class LZFException extends CompressionFormatException
{
    private static final long serialVersionUID = 1L;

    public LZFException(String message) {
        super(message);
    }

    public LZFException(Throwable t) {
        super(t);
    }

    public LZFException(String message, Throwable t) {
        super(message, t);
    }
}
