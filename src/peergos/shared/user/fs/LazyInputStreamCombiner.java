package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.concurrent.*;

public class LazyInputStreamCombiner implements AsyncReader {
    private final NetworkAccess network;
    private final SafeRandom random;
    private final SymmetricKey dataKey;
    private final ProgressConsumer<Long> monitor;
    private final long totalLength;

    private final byte[] originalChunk;
    private final Location originalNextPointer;

    private byte[] currentChunk;
    private Location nextChunkPointer;

    private long globalIndex; // index of beginning of current chunk in file
    private int index; // index within current chunk

    public LazyInputStreamCombiner(long globalIndex,
                                   byte[] chunk,
                                   Location nextChunkPointer,
                                   byte[] originalChunk,
                                   Location originalNextChunkPointer,
                                   NetworkAccess network,
                                   SafeRandom random,
                                   SymmetricKey dataKey,
                                   long totalLength,
                                   ProgressConsumer<Long> monitor) {
        if (chunk == null)
            throw new IllegalStateException("Null initial chunk!");
        this.network = network;
        this.random = random;
        this.dataKey = dataKey;
        this.monitor = monitor;
        this.totalLength = totalLength;
        this.originalChunk = originalChunk;
        this.originalNextPointer = originalNextChunkPointer;
        this.currentChunk = chunk;
        this.nextChunkPointer = nextChunkPointer;
        this.globalIndex = globalIndex;
        this.index = 0;
    }

    public CompletableFuture<Boolean> getNextStream(int len) {
        if (this.nextChunkPointer != null) {
            Location nextLocation = this.nextChunkPointer;
            return network.getMetadata(nextLocation).thenCompose(meta -> {
                if (!meta.isPresent()) {
                    CompletableFuture<Boolean> err = new CompletableFuture<>();
                    err.completeExceptionally(new EOFException());
                    return err;
                }
                FileRetriever nextRet = meta.get().retriever();
                Location newNextChunkPointer = nextRet.getNext(dataKey).orElse(null);
                return nextRet.getChunkInputStream(network, random, dataKey, 0, len, nextLocation, monitor)
                        .thenApply(x -> {
                            byte[] nextData = x.get().chunk.data();
                            updateState(0,globalIndex + Chunk.MAX_SIZE, nextData, newNextChunkPointer);
                            return true;
                        });
            });
        }
        CompletableFuture<Boolean> err = new CompletableFuture<>();
        err.completeExceptionally(new EOFException());
        return err;
    }

    private CompletableFuture<AsyncReader> skip(long skip) {
        long available = (long) bytesReady();

        if (skip <= available) {
            index += (int) skip;
            return CompletableFuture.completedFuture(this);
        }

        long toRead = Math.min(available, skip);

        int remainingToRead = totalLength - globalIndex > Chunk.MAX_SIZE ? Chunk.MAX_SIZE : (int) (totalLength - globalIndex);
        return getNextStream(remainingToRead)
                .thenCompose(done -> this.skip(skip - toRead));
    }

    @Override
    public CompletableFuture<AsyncReader> seek(int hi32, int low32) {
        long seek = ((long) (hi32) << 32) | low32;

        if (totalLength < seek)
            throw new IllegalStateException("Cannot seek to position "+ seek);
        return reset().thenCompose(x -> ((LazyInputStreamCombiner)x).skip(seek));
    }

    private int bytesReady() {
        return this.currentChunk.length - this.index;
    }

    public void close() {}

    public CompletableFuture<AsyncReader> reset() {
        this.globalIndex = 0;
        this.currentChunk = originalChunk;
        this.nextChunkPointer = originalNextPointer;
        this.index = 0;
        return CompletableFuture.completedFuture(this);
    }

    /**
     *
     * @param res array to store data in
     * @param offset initial index to store data in res
     * @param length number of bytes to read
     * @return number of bytes read
     */
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        int available = bytesReady();
        int toRead = Math.min(available, length);
        System.arraycopy(currentChunk, index, res, offset, toRead);
        index += toRead;
        long globalOffset = globalIndex + index;

        if (available >= length) // we are done
            return CompletableFuture.completedFuture(length);
        if (globalOffset > totalLength) {
            CompletableFuture<Integer> err=  new CompletableFuture<>();
            err.completeExceptionally(new EOFException());
            return err;
        }
        int nextChunkSize = totalLength - globalOffset > Chunk.MAX_SIZE ?
                Chunk.MAX_SIZE :
                (int) (totalLength - globalOffset);
        return getNextStream(nextChunkSize).thenCompose(done ->
            this.readIntoArray(res, offset + toRead, length - toRead).thenApply(bytesRead -> bytesRead + toRead)
        );
    }

    private void updateState(int index,
                             long globalIndex,
                             byte[] chunk,
                             Location nextChunkPointer) {
        this.index = index;
        this.globalIndex = globalIndex;
        this.currentChunk = chunk;
        this.nextChunkPointer = nextChunkPointer;
    }

}
