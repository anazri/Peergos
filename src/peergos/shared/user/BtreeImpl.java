package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class BtreeImpl implements Btree {
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private static final boolean LOGGING = false;
    private final Map<PublicKeyHash, CompletableFuture<CommittedWriterData>> pending = new HashMap<>();

    public BtreeImpl(MutablePointers mutable, ContentAddressedStorage dht) {
        this.mutable = mutable;
        this.dht = dht;
    }

    private <T> T log(T result, String toPrint) {
        if (LOGGING)
            System.out.println(toPrint);
        return result;
    }

    private CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash controller, MaybeMultihash hash) {
        if (!hash.isPresent())
            return CompletableFuture.completedFuture(new CommittedWriterData(MaybeMultihash.EMPTY(), WriterData.createEmpty(controller)));
        return dht.get(hash.get())
                .thenApply(cborOpt -> {
                    if (! cborOpt.isPresent())
                        throw new IllegalStateException("Couldn't retrieve WriterData from dht! " + hash);
                    return new CommittedWriterData(hash, WriterData.fromCbor(cborOpt.get(), null));
                });
    }

    private CompletableFuture<CommittedWriterData> getWriterData(PublicKeyHash hash) {
        return mutable.getPointer(hash)
                .thenCompose(dataOpt -> dht.getSigningKey(hash)
                        .thenApply(signer -> dataOpt.isPresent() ?
                                HashCasPair.fromCbor(CborObject.fromByteArray(signer.get().unsignMessage(dataOpt.get()))).updated :
                                MaybeMultihash.EMPTY())
                        .thenCompose(x -> getWriterData(hash, x)));
    }

    private CompletableFuture<CommittedWriterData> addToQueue(PublicKeyHash pubKey, CompletableFuture<CommittedWriterData> lock) {
        synchronized (pending) {
            // This is subtle, but we need to ensure that there is only ever 1 thenAble waiting on the future for a given key
            // otherwise when the future completes, then the two or more waiters will both proceed with the existing hash,
            // and whoever commits first will win. We also need to retrieve the writer data again from the network after
            // a previous transaction has completed (another node/user may have updated the mapping)

            if (pending.containsKey(pubKey)) {
                return pending.put(pubKey, lock).thenCompose(x -> getWriterData(pubKey));
            }
            pending.put(pubKey, lock);
            return getWriterData(pubKey);
        }
    }

    @Override
    public CompletableFuture<Boolean> put(SigningPrivateKeyAndPublicHash writer, byte[] mapKey, Multihash value) {
        PublicKeyHash publicWriterKey = writer.publicKeyHash;
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();

        return addToQueue(publicWriterKey, lock)
                .thenCompose(committed -> {
                    WriterData holder = committed.props;
                    MaybeMultihash btreeRootHash = holder.btree.isPresent() ? MaybeMultihash.of(holder.btree.get()) : MaybeMultihash.EMPTY();
                    return MerkleBTree.create(publicWriterKey, btreeRootHash, dht)
                            .thenCompose(btree -> btree.put(publicWriterKey, mapKey, value))
                            .thenApply(newRoot -> LOGGING ? log(newRoot, "BTREE.put (" + ArrayOps.bytesToHex(mapKey)
                                    + ", " + value + ") => CAS(" + btreeRootHash + ", " + newRoot + ")") : newRoot)
                            .thenCompose(newBtreeRoot -> holder.withBtree(newBtreeRoot)
                                    .commit(writer, committed.hash, mutable, dht, lock::complete))
                            .thenApply(x -> true);
                });
    }

    @Override
    public CompletableFuture<MaybeMultihash> get(PublicKeyHash writer, byte[] mapKey) {
        CompletableFuture<CommittedWriterData> lock = new CompletableFuture<>();

        return addToQueue(writer, lock)
                .thenCompose(committed -> {
                    lock.complete(committed);
                    WriterData holder = committed.props;
                    MaybeMultihash btreeRootHash = holder.btree.isPresent() ? MaybeMultihash.of(holder.btree.get()) : MaybeMultihash.EMPTY();
                    return MerkleBTree.create(writer, btreeRootHash, dht)
                            .thenCompose(btree -> btree.get(mapKey))
                            .thenApply(maybe -> LOGGING ?
                                    log(maybe, "BTREE.get (" + ArrayOps.bytesToHex(mapKey) + ", root="+btreeRootHash+" => " + maybe) : maybe);
                });
    }

    @Override
    public CompletableFuture<Boolean> remove(SigningPrivateKeyAndPublicHash writer, byte[] mapKey) {
        PublicKeyHash publicWriter = writer.publicKeyHash;
        CompletableFuture<CommittedWriterData> future = new CompletableFuture<>();

        return addToQueue(publicWriter, future)
                .thenCompose(committed -> {
                    WriterData holder = committed.props;
                    MaybeMultihash btreeRootHash = holder.btree.isPresent() ? MaybeMultihash.of(holder.btree.get()) : MaybeMultihash.EMPTY();
                    return MerkleBTree.create(publicWriter, btreeRootHash, dht)
                            .thenCompose(btree -> btree.delete(publicWriter, mapKey))
                            .thenApply(pair -> LOGGING ? log(pair, "BTREE.rm (" + ArrayOps.bytesToHex(mapKey) + "  => " + pair) : pair)
                            .thenCompose(newBtreeRoot -> holder.withBtree(newBtreeRoot)
                                    .commit(writer, committed.hash, mutable, dht, future::complete))
                            .thenApply(x -> true);
                });
    }
}
