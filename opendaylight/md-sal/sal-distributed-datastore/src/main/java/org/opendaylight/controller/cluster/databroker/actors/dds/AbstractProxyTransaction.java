/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.databroker.actors.dds;

import akka.actor.ActorRef;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.base.Verify;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import org.opendaylight.controller.cluster.access.client.ConnectionEntry;
import org.opendaylight.controller.cluster.access.commands.TransactionAbortRequest;
import org.opendaylight.controller.cluster.access.commands.TransactionAbortSuccess;
import org.opendaylight.controller.cluster.access.commands.TransactionCanCommitSuccess;
import org.opendaylight.controller.cluster.access.commands.TransactionCommitSuccess;
import org.opendaylight.controller.cluster.access.commands.TransactionDoCommitRequest;
import org.opendaylight.controller.cluster.access.commands.TransactionPreCommitRequest;
import org.opendaylight.controller.cluster.access.commands.TransactionPreCommitSuccess;
import org.opendaylight.controller.cluster.access.commands.TransactionPurgeRequest;
import org.opendaylight.controller.cluster.access.commands.TransactionRequest;
import org.opendaylight.controller.cluster.access.concepts.Request;
import org.opendaylight.controller.cluster.access.concepts.RequestFailure;
import org.opendaylight.controller.cluster.access.concepts.Response;
import org.opendaylight.controller.cluster.access.concepts.TransactionIdentifier;
import org.opendaylight.mdsal.common.api.ReadFailedException;
import org.opendaylight.yangtools.concepts.Identifiable;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class translating transaction operations towards a particular backend shard.
 *
 * <p>
 * This class is not safe to access from multiple application threads, as is usual for transactions. Internal state
 * transitions coming from interactions with backend are expected to be thread-safe.
 *
 * <p>
 * This class interacts with the queueing mechanism in ClientActorBehavior, hence once we arrive at a decision
 * to use either a local or remote implementation, we are stuck with it. We can re-evaluate on the next transaction.
 *
 * @author Robert Varga
 */
abstract class AbstractProxyTransaction implements Identifiable<TransactionIdentifier> {
    /**
     * Marker object used instead of read-type of requests, which are satisfied only once. This has a lower footprint
     * and allows compressing multiple requests into a single entry.
     */
    @NotThreadSafe
    private static final class IncrementSequence {
        private long delta = 1;

        long getDelta() {
            return delta;
        }

        void incrementDelta() {
            delta++;
        }
    }

    // Generic state base class. Direct instances are used for fast paths, sub-class is used for successor transitions
    private static class State {
        private final String string;

        State(final String string) {
            this.string = Preconditions.checkNotNull(string);
        }

        @Override
        public final String toString() {
            return string;
        }
    }

    // State class used when a successor has interfered. Contains coordinator latch, the successor and previous state
    private static final class SuccessorState extends State {
        private final CountDownLatch latch = new CountDownLatch(1);
        private AbstractProxyTransaction successor;
        private State prevState;

        SuccessorState() {
            super("successor");
        }

        // Synchronize with succession process and return the successor
        AbstractProxyTransaction await() {
            try {
                latch.await();
            } catch (InterruptedException e) {
                LOG.warn("Interrupted while waiting for latch of {}", successor);
                throw Throwables.propagate(e);
            }
            return successor;
        }

        void finish() {
            latch.countDown();
        }

        State getPrevState() {
            return prevState;
        }

        void setPrevState(final State prevState) {
            Verify.verify(this.prevState == null);
            this.prevState = Preconditions.checkNotNull(prevState);
        }

        // To be called from safe contexts, where successor is known to be completed
        AbstractProxyTransaction getSuccessor() {
            return Verify.verifyNotNull(successor);
        }

        void setSuccessor(final AbstractProxyTransaction successor) {
            Verify.verify(this.successor == null);
            this.successor = Preconditions.checkNotNull(successor);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(AbstractProxyTransaction.class);
    private static final AtomicIntegerFieldUpdater<AbstractProxyTransaction> SEALED_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractProxyTransaction.class, "sealed");
    private static final AtomicReferenceFieldUpdater<AbstractProxyTransaction, State> STATE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(AbstractProxyTransaction.class, State.class, "state");
    private static final State OPEN = new State("open");
    private static final State SEALED = new State("sealed");
    private static final State FLUSHED = new State("flushed");

    // Touched from client actor thread only
    private final Deque<Object> successfulRequests = new ArrayDeque<>();
    private final ProxyHistory parent;

    // Accessed from user thread only, which may not access this object concurrently
    private long sequence;

    /*
     * Atomic state-keeping is required to synchronize the process of propagating completed transaction state towards
     * the backend -- which may include a successor.
     *
     * Successor, unlike {@link AbstractProxyTransaction#seal()} is triggered from the client actor thread, which means
     * the successor placement needs to be atomic with regard to the application thread.
     *
     * In the common case, the application thread performs performs the seal operations and then "immediately" sends
     * the corresponding message. The uncommon case is when the seal and send operations race with a connect completion
     * or timeout, when a successor is injected.
     *
     * This leaves the problem of needing to completely transferring state just after all queued messages are replayed
     * after a successor was injected, so that it can be properly sealed if we are racing. Further complication comes
     * from lock ordering, where the successor injection works with a locked queue and locks proxy objects -- leading
     * to a potential AB-BA deadlock in case of a naive implementation.
     *
     * For tracking user-visible state we use a single volatile int, which is flipped atomically from 0 to 1 exactly
     * once in {@link AbstractProxyTransaction#seal()}. That keeps common operations fast, as they need to perform
     * only a single volatile read to assert state correctness.
     *
     * For synchronizing client actor (successor-injecting) and user (commit-driving) thread, we keep a separate state
     * variable. It uses pre-allocated objects for fast paths (i.e. no successor present) and a per-transition object
     * for slow paths (when successor is injected/present).
     */
    private volatile int sealed = 0;
    private volatile State state = OPEN;

    AbstractProxyTransaction(final ProxyHistory parent) {
        this.parent = Preconditions.checkNotNull(parent);
    }

    final ActorRef localActor() {
        return parent.localActor();
    }

    private void incrementSequence(final long delta) {
        sequence += delta;
        LOG.debug("Transaction {} incremented sequence to {}", this, sequence);
    }

    final long nextSequence() {
        final long ret = sequence++;
        LOG.debug("Transaction {} allocated sequence {}", this, ret);
        return ret;
    }

    final void delete(final YangInstanceIdentifier path) {
        checkReadWrite();
        checkNotSealed();
        doDelete(path);
    }

    final void merge(final YangInstanceIdentifier path, final NormalizedNode<?, ?> data) {
        checkReadWrite();
        checkNotSealed();
        doMerge(path, data);
    }

    final void write(final YangInstanceIdentifier path, final NormalizedNode<?, ?> data) {
        checkReadWrite();
        checkNotSealed();
        doWrite(path, data);
    }

    final CheckedFuture<Boolean, ReadFailedException> exists(final YangInstanceIdentifier path) {
        checkNotSealed();
        return doExists(path);
    }

    final CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> read(final YangInstanceIdentifier path) {
        checkNotSealed();
        return doRead(path);
    }

    final void sendRequest(final TransactionRequest<?> request, final Consumer<Response<?, ?>> callback) {
        LOG.debug("Transaction proxy {} sending request {} callback {}", this, request, callback);
        parent.sendRequest(request, callback);
    }

    /**
     * Seal this transaction before it is either committed or aborted.
     */
    final void seal() {
        // Transition user-visible state first
        final boolean success = SEALED_UPDATER.compareAndSet(this, 0, 1);
        Preconditions.checkState(success, "Proxy %s was already sealed", getIdentifier());
        internalSeal();
    }

    final void ensureSealed() {
        if (SEALED_UPDATER.compareAndSet(this, 0, 1)) {
            internalSeal();
        }
    }

    private void internalSeal() {
        doSeal();
        parent.onTransactionSealed(this);

        // Now deal with state transfer, which can occur via successor or a follow-up canCommit() or directCommit().
        if (!STATE_UPDATER.compareAndSet(this, OPEN, SEALED)) {
            // Slow path: wait for the successor to complete
            final AbstractProxyTransaction successor = awaitSuccessor();

            // At this point the successor has completed transition and is possibly visible by the user thread, which is
            // still stuck here. The successor has not seen final part of our state, nor the fact it is sealed.
            // Propagate state and seal the successor.
            flushState(successor);
            successor.ensureSealed();
        }
    }

    private void checkNotSealed() {
        Preconditions.checkState(sealed == 0, "Transaction %s has already been sealed", getIdentifier());
    }

    private void checkSealed() {
        Preconditions.checkState(sealed != 0, "Transaction %s has not been sealed yet", getIdentifier());
    }

    private SuccessorState getSuccessorState() {
        final State local = state;
        Verify.verify(local instanceof SuccessorState, "State %s has unexpected class", local);
        return (SuccessorState) local;
    }

    private void checkReadWrite() {
        if (isSnapshotOnly()) {
            throw new UnsupportedOperationException("Transaction " + getIdentifier() + " is a read-only snapshot");
        }
    }

    final void recordSuccessfulRequest(final @Nonnull TransactionRequest<?> req) {
        successfulRequests.add(Verify.verifyNotNull(req));
    }

    final void recordFinishedRequest() {
        final Object last = successfulRequests.peekLast();
        if (last instanceof IncrementSequence) {
            ((IncrementSequence) last).incrementDelta();
        } else {
            successfulRequests.addLast(new IncrementSequence());
        }
    }

    /**
     * Abort this transaction. This is invoked only for read-only transactions and will result in an explicit message
     * being sent to the backend.
     */
    final void abort() {
        checkNotSealed();
        doAbort();
        parent.abortTransaction(this);
    }

    final void abort(final VotingFuture<Void> ret) {
        checkSealed();

        sendAbort(t -> {
            if (t instanceof TransactionAbortSuccess) {
                ret.voteYes();
            } else if (t instanceof RequestFailure) {
                ret.voteNo(((RequestFailure<?, ?>) t).getCause());
            } else {
                ret.voteNo(new IllegalStateException("Unhandled response " + t.getClass()));
            }

            // This is a terminal request, hence we do not need to record it
            LOG.debug("Transaction {} abort completed", this);
            purge();
        });
    }

    final void sendAbort(final Consumer<Response<?, ?>> callback) {
        sendRequest(new TransactionAbortRequest(getIdentifier(), nextSequence(), localActor()), callback);
    }

    /**
     * Commit this transaction, possibly in a coordinated fashion.
     *
     * @param coordinated True if this transaction should be coordinated across multiple participants.
     * @return Future completion
     */
    final ListenableFuture<Boolean> directCommit() {
        checkReadWrite();
        checkSealed();

        // Precludes startReconnect() from interfering with the fast path
        synchronized (this) {
            if (STATE_UPDATER.compareAndSet(this, SEALED, FLUSHED)) {
                final SettableFuture<Boolean> ret = SettableFuture.create();
                sendRequest(Verify.verifyNotNull(commitRequest(false)), t -> {
                    if (t instanceof TransactionCommitSuccess) {
                        ret.set(Boolean.TRUE);
                    } else if (t instanceof RequestFailure) {
                        ret.setException(((RequestFailure<?, ?>) t).getCause());
                    } else {
                        ret.setException(new IllegalStateException("Unhandled response " + t.getClass()));
                    }

                    // This is a terminal request, hence we do not need to record it
                    LOG.debug("Transaction {} directCommit completed", this);
                    purge();
                });

                return ret;
            }
        }

        // We have had some interference with successor injection, wait for it to complete and defer to the successor.
        return awaitSuccessor().directCommit();
    }

    final void canCommit(final VotingFuture<?> ret) {
        checkReadWrite();
        checkSealed();

        // Precludes startReconnect() from interfering with the fast path
        synchronized (this) {
            if (STATE_UPDATER.compareAndSet(this, SEALED, FLUSHED)) {
                final TransactionRequest<?> req = Verify.verifyNotNull(commitRequest(true));

                sendRequest(req, t -> {
                    if (t instanceof TransactionCanCommitSuccess) {
                        ret.voteYes();
                    } else if (t instanceof RequestFailure) {
                        ret.voteNo(((RequestFailure<?, ?>) t).getCause());
                    } else {
                        ret.voteNo(new IllegalStateException("Unhandled response " + t.getClass()));
                    }

                    recordSuccessfulRequest(req);
                    LOG.debug("Transaction {} canCommit completed", this);
                });

                return;
            }
        }

        // We have had some interference with successor injection, wait for it to complete and defer to the successor.
        awaitSuccessor().canCommit(ret);
    }

    private AbstractProxyTransaction awaitSuccessor() {
        return getSuccessorState().await();
    }

    final void preCommit(final VotingFuture<?> ret) {
        checkReadWrite();
        checkSealed();

        final TransactionRequest<?> req = new TransactionPreCommitRequest(getIdentifier(), nextSequence(),
            localActor());
        sendRequest(req, t -> {
            if (t instanceof TransactionPreCommitSuccess) {
                ret.voteYes();
            } else if (t instanceof RequestFailure) {
                ret.voteNo(((RequestFailure<?, ?>) t).getCause());
            } else {
                ret.voteNo(new IllegalStateException("Unhandled response " + t.getClass()));
            }

            onPreCommitComplete(req);
        });
    }

    private void onPreCommitComplete(final TransactionRequest<?> req) {
        /*
         * The backend has agreed that the transaction has entered PRE_COMMIT phase, meaning it will be committed
         * to storage after the timeout completes.
         *
         * All state has been replicated to the backend, hence we do not need to keep it around. Retain only
         * the precommit request, so we know which request to use for resync.
         */
        LOG.debug("Transaction {} preCommit completed, clearing successfulRequests", this);
        successfulRequests.clear();

        // TODO: this works, but can contain some useless state (like batched operations). Create an empty
        //       equivalent of this request and store that.
        recordSuccessfulRequest(req);
    }

    final void doCommit(final VotingFuture<?> ret) {
        checkReadWrite();
        checkSealed();

        sendRequest(new TransactionDoCommitRequest(getIdentifier(), nextSequence(), localActor()), t -> {
            if (t instanceof TransactionCommitSuccess) {
                ret.voteYes();
            } else if (t instanceof RequestFailure) {
                ret.voteNo(((RequestFailure<?, ?>) t).getCause());
            } else {
                ret.voteNo(new IllegalStateException("Unhandled response " + t.getClass()));
            }

            LOG.debug("Transaction {} doCommit completed", this);
            purge();
        });
    }

    void purge() {
        successfulRequests.clear();

        final TransactionRequest<?> req = new TransactionPurgeRequest(getIdentifier(), nextSequence(), localActor());
        sendRequest(req, t -> {
            LOG.debug("Transaction {} purge completed", this);
            parent.completeTransaction(this);
        });
    }

    // Called with the connection unlocked
    final synchronized void startReconnect() {
        // At this point canCommit/directCommit are blocked, we assert a new successor state, retrieving the previous
        // state. This method is called with the queue still unlocked.
        final SuccessorState nextState = new SuccessorState();
        final State prevState = STATE_UPDATER.getAndSet(this, nextState);

        LOG.debug("Start reconnect of proxy {} previous state {}", this, prevState);
        Verify.verify(!(prevState instanceof SuccessorState), "Proxy %s duplicate reconnect attempt after %s", this,
            prevState);

        // We have asserted a slow-path state, seal(), canCommit(), directCommit() are forced to slow paths, which will
        // wait until we unblock nextState's latch before accessing state. Now we record prevState for later use and we
        // are done.
        nextState.setPrevState(prevState);
    }

    // Called with the connection locked
    final void replayMessages(final AbstractProxyTransaction successor,
            final Iterable<ConnectionEntry> enqueuedEntries) {
        final SuccessorState local = getSuccessorState();
        local.setSuccessor(successor);

        // Replay successful requests first
        for (Object obj : successfulRequests) {
            if (obj instanceof TransactionRequest) {
                LOG.debug("Forwarding successful request {} to successor {}", obj, successor);
                successor.handleForwardedRemoteRequest((TransactionRequest<?>) obj, response -> { });
            } else {
                Verify.verify(obj instanceof IncrementSequence);
                successor.incrementSequence(((IncrementSequence) obj).getDelta());
            }
        }
        LOG.debug("{} replayed {} successful requests", getIdentifier(), successfulRequests.size());
        successfulRequests.clear();

        // Now replay whatever is in the connection
        final Iterator<ConnectionEntry> it = enqueuedEntries.iterator();
        while (it.hasNext()) {
            final ConnectionEntry e = it.next();
            final Request<?, ?> req = e.getRequest();

            if (getIdentifier().equals(req.getTarget())) {
                Verify.verify(req instanceof TransactionRequest, "Unhandled request %s", req);
                LOG.debug("Forwarding queued request{} to successor {}", req, successor);
                successor.handleForwardedRemoteRequest((TransactionRequest<?>) req, e.getCallback());
                it.remove();
            }
        }

        /*
         * Check the state at which we have started the reconnect attempt. State transitions triggered while we were
         * reconnecting have been forced to slow paths, which will be unlocked once we unblock the state latch
         * at the end of this method.
         */
        final State prevState = local.getPrevState();
        if (SEALED.equals(prevState)) {
            LOG.debug("Proxy {} reconnected while being sealed, propagating state to successor {}", this, successor);
            flushState(successor);
            successor.ensureSealed();
        }
    }

    // Called with the connection locked
    final void finishReconnect() {
        final SuccessorState local = getSuccessorState();
        LOG.debug("Finishing reconnect of proxy {}", this);

        // All done, release the latch, unblocking seal() and canCommit() slow paths
        local.finish();
    }

    /**
     * Invoked from a retired connection for requests which have been in-flight and need to be re-adjusted
     * and forwarded to the successor connection.
     *
     * @param request Request to be forwarded
     * @param callback Original callback
     */
    final void replayRequest(final TransactionRequest<?> request, final Consumer<Response<?, ?>> callback) {
        final AbstractProxyTransaction successor = getSuccessorState().getSuccessor();

        if (successor instanceof LocalProxyTransaction) {
            forwardToLocal((LocalProxyTransaction)successor, request, callback);
        } else if (successor instanceof RemoteProxyTransaction) {
            forwardToRemote((RemoteProxyTransaction)successor, request, callback);
        } else {
            throw new IllegalStateException("Unhandled successor " + successor);
        }
    }

    abstract boolean isSnapshotOnly();

    abstract void doDelete(YangInstanceIdentifier path);

    abstract void doMerge(YangInstanceIdentifier path, NormalizedNode<?, ?> data);

    abstract void doWrite(YangInstanceIdentifier path, NormalizedNode<?, ?> data);

    abstract CheckedFuture<Boolean, ReadFailedException> doExists(YangInstanceIdentifier path);

    abstract CheckedFuture<Optional<NormalizedNode<?, ?>>, ReadFailedException> doRead(YangInstanceIdentifier path);

    abstract void doSeal();

    abstract void doAbort();

    @GuardedBy("this")
    abstract void flushState(AbstractProxyTransaction successor);

    abstract TransactionRequest<?> commitRequest(boolean coordinated);

    /**
     * Invoked from {@link RemoteProxyTransaction} when it replays its successful requests to its successor. There is
     * no equivalent of this call from {@link LocalProxyTransaction} because it does not send a request until all
     * operations are packaged in the message.
     *
     * <p>
     * Note: this method is invoked by the predecessor on the successor.
     *
     * @param request Request which needs to be forwarded
     * @param callback Callback to be invoked once the request completes
     */
    abstract void handleForwardedRemoteRequest(TransactionRequest<?> request,
            @Nullable Consumer<Response<?, ?>> callback);

    /**
     * Replay a request originating in this proxy to a successor remote proxy.
     */
    abstract void forwardToRemote(RemoteProxyTransaction successor, TransactionRequest<?> request,
            Consumer<Response<?, ?>> callback);

    /**
     * Replay a request originating in this proxy to a successor local proxy.
     */
    abstract void forwardToLocal(LocalProxyTransaction successor, TransactionRequest<?> request,
            Consumer<Response<?, ?>> callback);

    @Override
    public final String toString() {
        return MoreObjects.toStringHelper(this).add("identifier", getIdentifier()).add("state", state).toString();
    }
}
