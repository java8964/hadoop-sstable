/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.fullcontact.cassandra.io.compress;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import com.fullcontact.cassandra.io.sstable.CorruptBlockException;
import com.fullcontact.cassandra.io.util.RandomAccessReader;
import org.apache.cassandra.io.FSReadError;
import org.apache.cassandra.io.sstable.CorruptSSTableException;
import com.fullcontact.cassandra.io.util.CompressedPoolingSegmentedFile;
import com.fullcontact.cassandra.io.util.PoolingSegmentedFile;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.hadoop.fs.*;

/**
 * CRAR extends RAR to transparently uncompress blocks from the file into RAR.buffer.  Most of the RAR
 * "read bytes from the buffer, rebuffering when necessary" machinery works unchanged after that.
 */
public class CompressedRandomAccessReader extends RandomAccessReader
{
    public static CompressedRandomAccessReader open(Path path, CompressionMetadata metadata,
                                                    CompressedPoolingSegmentedFile owner, FileSystem fs)
    {
        try
        {
            return new CompressedRandomAccessReader(path, metadata, owner, fs);
        }
        catch (FileNotFoundException e)
        {
            throw new RuntimeException(e);
        }
    }

    public static CompressedRandomAccessReader open(Path dataFilePath,
                                                    CompressionMetadata metadata, FileSystem fs)
    {
        try
        {
            return new CompressedRandomAccessReader(dataFilePath, metadata, null, fs);
        }
        catch (FileNotFoundException e)
        {
            throw new RuntimeException(e);
        }
    }

    private final CompressionMetadata metadata;

    // we read the raw compressed bytes into this buffer, then move the uncompressed ones into super.buffer.
    private ByteBuffer compressed;

    // re-use single crc object
    private final Checksum checksum;

    // raw checksum bytes
    private final ByteBuffer checksumBytes = ByteBuffer.wrap(new byte[4]);

    protected CompressedRandomAccessReader(Path dataFilePath, CompressionMetadata metadata,
                                           PoolingSegmentedFile owner, FileSystem fs) throws
        FileNotFoundException
    {
        super(dataFilePath, metadata.chunkLength(), owner, fs);
        this.metadata = metadata;
        checksum = metadata.hasPostCompressionAdlerChecksums ? new Adler32() : new CRC32();
        compressed = ByteBuffer.wrap(new byte[metadata.compressor().initialCompressedBufferLength(metadata.chunkLength())]);
    }

    @Override
    protected void reBuffer()
    {
        try
        {
            decompressChunk(metadata.chunkFor(current));
        }
        catch (CorruptBlockException e)
        {
            throw new CorruptSSTableException(e, getPath());
        }
        catch (IOException e)
        {
            throw new FSReadError(e, getPath());
        }
    }

    private void decompressChunk(CompressionMetadata.Chunk chunk) throws IOException
    {
        if (input.getPos() != chunk.offset)
            input.seek(chunk.offset);

        if (compressed.capacity() < chunk.length)
            compressed = ByteBuffer.wrap(new byte[chunk.length]);
        else
            compressed.clear();
        compressed.limit(chunk.length);

        byte[] compressedArray = compressed.array();
        try {
            input.readFully(compressedArray, compressed.position(), compressed.limit());
        } catch (IOException e) {
            throw new CorruptBlockException(getPath(), chunk);
        }

        // technically flip() is unnecessary since all the remaining work uses the raw array, but if that changes
        // in the future this will save a lot of hair-pulling
        compressed.flip();
        try
        {
            validBufferBytes = metadata.compressor().uncompress(compressed.array(), 0, chunk.length, buffer, 0);
        }
        catch (IOException e)
        {
            throw new CorruptBlockException(getPath(), chunk, e);
        }

        // TODO: Circle back and determine why this doesn't work. Maybe it does with the new readFully impl?
        if (metadata.parameters.getCrcCheckChance() > FBUtilities.threadLocalRandom().nextDouble())
        {

            if (metadata.hasPostCompressionAdlerChecksums)
            {
                checksum.update(compressed.array(), 0, chunk.length);
            }
            else
            {
                checksum.update(buffer, 0, validBufferBytes);
            }

            if (checksum(chunk) != (int) checksum.getValue())
                throw new CorruptBlockException(getPath(), chunk);

            // reset checksum object back to the original (blank) state
            checksum.reset();
        }

        // buffer offset is always aligned
        bufferOffset = current & ~(buffer.length - 1);
    }

    private int checksum(CompressionMetadata.Chunk chunk) throws IOException
    {
        assert input.getPos() == chunk.offset + chunk.length;
        checksumBytes.clear();
        try {
            input.readFully(checksumBytes.array());
        } catch (IOException e) {
            throw new CorruptBlockException(getPath(), chunk);
        }
        return checksumBytes.getInt(0);
    }

    public int getTotalBufferSize()
    {
        return super.getTotalBufferSize() + compressed.capacity();
    }

    @Override
    public long length()
    {
        return metadata.dataLength;
    }

    @Override
    public String toString()
    {
        return String.format("%s - chunk length %d, data length %d.", getPath(), metadata.chunkLength(), metadata.dataLength);
    }
}
