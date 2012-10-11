/**
Package that contains optimized stream implementations for working
with GZIP: they internally use JDK provided efficient ZLIB codecs,
but add appropriate reuse to specifically improve handling of relatively
short compressed data.
*/

package com.infinitemonkeys.compress.gzip;
