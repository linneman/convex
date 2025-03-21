package convex.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Result;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.exceptions.MissingDataException;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.transactions.ATransaction;
import convex.core.util.Utils;
import convex.net.Connection;

public class ConvexRemote extends Convex {
	/**
	 * Current Connection to a Peer, may be null or a closed connection.
	 */
	protected Connection connection;
	
	private static final Logger log = LoggerFactory.getLogger(ConvexRemote.class.getName());

	
	/**
	 * Gets the Internet address of the currently connected remote
	 *
	 * @return Remote socket address
	 */
	public InetSocketAddress getRemoteAddress() {
		return connection.getRemoteAddress();
	}

	protected ConvexRemote(Address address, AKeyPair keyPair) {
		super(address, keyPair);
	}
	
	protected void connectToPeer(InetSocketAddress peerAddress, AStore store) throws IOException, TimeoutException {
		setConnection(Connection.connect(peerAddress, internalHandler, store));
	}

	/**
	 * Sets the current Connection for this Remote Client
	 *
	 * @param conn Connection value to use
	 */
	protected void setConnection(Connection conn) {
		if (this.connection == conn)
			return;
		close();
		this.connection = conn;
	}
	
	/**
	 * Gets the underlying Connection instance for this Client. May be null if not
	 * connected.
	 *
	 * @return Connection instance or null
	 */
	public Connection getConnection() {
		return connection;
	}
	
	/**
	 * Checks if this Convex client instance has an open remote connection.
	 *
	 * @return true if connected, false otherwise
	 */
	public boolean isConnected() {
		Connection c = this.connection;
		return (c != null) && (!c.isClosed());
	}
	
	/**
	 * Close without affecting the connection
	 */
	public void closeButMaintainConnection() {
		this.connection = null;
		close();
	}
	
	/**
	 * Gets the consensus state from the connected Peer. The acquired state will be a snapshot
	 * of the network global state as calculated by the Peer.
	 * 
	 * SECURITY: Be aware that if this client instance is connected to an untrusted Peer, the
	 * Peer may lie about the latest state. If this is a security concern, the client should
	 * validate the consensus state independently.
	 * 
	 * @return Future for consensus state
	 * @throws TimeoutException If initial status request times out
	 */
	public CompletableFuture<State> acquireState() throws TimeoutException {
		try {
			Future<Result> sF = requestStatus();
			AVector<ACell> status = sF.get(timeout, TimeUnit.MILLISECONDS).getValue();
			Hash stateHash = RT.ensureHash(status.get(4));

			if (stateHash == null)
				throw new Error("Bad status response from Peer");
			return acquire(stateHash);
		} catch (InterruptedException | ExecutionException e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	@Override
	public synchronized CompletableFuture<Result> transact(SignedData<ATransaction> signed) throws IOException {
		CompletableFuture<Result> cf;
		long id = -1;

		synchronized (awaiting) {
			// loop until request is queued
			while (id < 0) {
				id = connection.sendTransaction(signed);
				if (id < 0) {
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						// Ignore
					}
				}
			}

			// Store future for completion by result message
			cf = awaitResult(id);
		}

		log.debug("Sent transaction with message ID: {} awaiting count = {}", id, awaiting.size());
		return cf;
	}
	
	@Override
	public CompletableFuture<Result> query(ACell query, Address address) throws IOException {
		synchronized (awaiting) {
			long id = connection.sendQuery(query, address);
			if (id < 0) {
				throw new IOException("Failed to send query due to full buffer");
			}

			return awaitResult(id);
		}
	}
	
	@Override
	public CompletableFuture<Result> requestStatus() {
		try {
			synchronized (awaiting) {
				long id = connection.sendStatusRequest();
				if (id < 0) {
					return CompletableFuture.failedFuture(new IOException("Failed to send status request due to full buffer"));
				}
	
				// TODO: ensure status is fully loaded
				// Store future for completion by result message
				CompletableFuture<Result> cf = awaitResult(id);
	
				return cf;
			}
		} catch (Throwable t) {
			return CompletableFuture.failedFuture(t);
		}
	}
	
	@Override
	public CompletableFuture<Result> requestChallenge(SignedData<ACell> data) throws IOException {
		synchronized (awaiting) {
			long id = connection.sendChallenge(data);
			if (id < 0) {
				// TODO: too fragile?
				throw new IOException("Failed to send challenge due to full buffer");
			}

			// Store future for completion by result message
			return awaitResult(id);
		}
	}
	
	@Override
	public <T extends ACell> CompletableFuture<T> acquire(Hash hash, AStore store) {
		CompletableFuture<T> f = new CompletableFuture<T>();
		new Thread(new Runnable() {
			@Override
			public void run() {
				Stores.setCurrent(store); // use store for calling thread
				try {
					Ref<T> ref = store.refForHash(hash);
					HashSet<Hash> missingSet = new HashSet<>();

					// Loop until future is complete or cancelled
					while (!f.isDone()) {
						missingSet.clear();

						if (ref == null) {
							missingSet.add(hash);
						} else {
							if (ref.getStatus() >= Ref.PERSISTED) {
								// we have everything!
								f.complete(ref.getValue());
								return;
							}
							ref.findMissing(missingSet);
						}
						for (Hash h : missingSet) {
							// send missing data requests until we fill pipeline
							log.debug("Request missing data: {}", h);
							boolean sent = connection.sendMissingData(h);
							if (!sent) {
								log.debug("Send Queue full!");
								break;
							}
						}
						// if too low, can send multiple requests, and then block the peer
						Thread.sleep(100);
						ref = store.refForHash(hash);
						if (ref != null) {
							if (ref.getStatus() >= Ref.PERSISTED) {
								// we have everything!
								f.complete(ref.getValue());
								return;
							}
							// maybe complete, but not sure
							try {
								ref = ref.persist();
								f.complete(ref.getValue());
							} catch (MissingDataException e) {
								Hash missing = e.getMissingHash();
								log.debug("Still missing: {}", missing);
								connection.sendMissingData(missing);
							}
						}
					}
				} catch (Throwable t) {
					// catch any errors, probably IO?
					f.completeExceptionally(t);
				}
			}
		}).start();
		return f;
	}
	
	/**
	 * Disconnects the client from the network, closing the underlying connection.
	 */
	public synchronized void close() {
		Connection c = this.connection;
		if (c != null) {
			c.close();
		}
		connection = null;
		awaiting.clear();
	}

	/**
	 * Wraps a connection as a Convex client instance
	 * 
	 * @param c Connection to wrap
	 * @return New Convex client instance using underlying connection
	 */
	public static ConvexRemote wrap(Connection c) {
		ConvexRemote convex = new ConvexRemote(null, null);
		convex.setConnection(c);
		return convex;
	}
}
