package convex.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.crypto.Ed25519KeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.PeerStatus;
import convex.core.data.RecordTest;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.Juice;
import convex.core.lang.RT;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Transfer;
import convex.core.util.Text;
import convex.core.util.Utils;

@Execution(value = ExecutionMode.CONCURRENT)
public class BeliefMergeTest {

	public static final int NUM_PEERS = 9;
	public static final int NUM_INITIAL_TRANS = 10;
	public static final int ROUNDS = 20;

	public static final Ed25519KeyPair[] KEY_PAIRS = new Ed25519KeyPair[NUM_PEERS];
	public static final Address[] ADDRESSES = new Address[NUM_PEERS];
	public static final AccountKey[] KEYS = new AccountKey[NUM_PEERS];
	public static final State INITIAL_STATE;
	private static final long TEST_TIMESTAMP = Instant.parse("1977-11-13T00:30:00Z").toEpochMilli();
	private static final long TOTAL_VALUE;

	static {
		// long seed=new Random().nextLong();
		long seed = 2654733563337952L;
		// System.out.println("Generating with seed: "+seed);
		AVector<AccountStatus> accounts = Vectors.empty();
		BlobMap<AccountKey, PeerStatus> peers = BlobMaps.empty();
		for (int i = 0; i < NUM_PEERS; i++) {
			Ed25519KeyPair kp = Ed25519KeyPair.createSeeded(seed + i * 17777);
			AccountKey key = kp.getAccountKey();
			// TODO numeric addresses
			Address address=Address.create(i);
			KEY_PAIRS[i] = kp;
			KEYS[i] = key;
			ADDRESSES[i] = address;
			AccountStatus accStatus = AccountStatus.create((i + 1) * 1000000,key);
			PeerStatus peerStatus = PeerStatus.create(address,(i + 1) * 100000);
			accounts = accounts.conj(accStatus);
			peers = peers.assoc(key, peerStatus);
		}

		AVector<ACell> globals = Constants.INITIAL_GLOBALS;
		globals = globals.assoc(State.GLOBAL_JUICE_PRICE, RT.cvm(1L)); // cheap juice for simplicity. USe CVM long
		INITIAL_STATE = State.create(accounts, peers, globals, BlobMaps.empty());
		TOTAL_VALUE = INITIAL_STATE.computeTotalFunds();
	}

	private Peer initialPeerState(int i) {
		return Peer.create(KEY_PAIRS[i], INITIAL_STATE);
	}

	private Peer[] initialBeliefs() {
		int n = NUM_PEERS;
		Peer[] result = new Peer[n];
		for (int i = 0; i < n; i++) {
			result[i] = initialPeerState(i);
		}
		return result;
	}

	public Peer[] shareBeliefs(Peer[] initial) throws BadSignatureException, InvalidDataException {
		int n = initial.length;
		Peer[] result = new Peer[n];

		// extract beliefs to share
		Belief[] sharedBeliefs = new Belief[n];
		for (int j = 0; j < n; j++)
			sharedBeliefs[j] = initial[j].getBelief();

		for (int i = 0; i < n; i++) {
			Peer ps = initial[i];
			result[i] = ps.mergeBeliefs(sharedBeliefs); // belief merge step
		}
		return result;
	}

	public Peer[] shareGossip(Peer[] initial, int numGossips, int round)
			throws BadSignatureException, InvalidDataException {
		Random r = new Random(107701 + round * 1337);
		int n = initial.length;
		Peer[] result = new Peer[n];

		Belief[] sharedBeliefs = new Belief[n];
		for (int j = 0; j < n; j++)
			sharedBeliefs[j] = initial[j].getBelief();

		for (int i = 0; i < n; i++) {
			Peer ps = initial[i];
			Belief[] sources = new Belief[numGossips];
			for (int j = 0; j < numGossips; j++) {
				sources[j] = sharedBeliefs[r.nextInt(n)];
			}
			result[i] = ps.mergeBeliefs(sources); // belief merge step
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private Peer[] proposeTransactions(Peer[] initial, int peerIndex, ATransaction... transactions)
			throws BadSignatureException {
		Peer[] result = initial.clone();
		Peer ps = initial[peerIndex]; // current per under consideration

		// create a block of transactions
		int tcount = transactions.length;
		SignedData<ATransaction>[] signedTransactions = (SignedData<ATransaction>[]) new SignedData[tcount];
		for (int ix = 0; ix < tcount; ix++) {
			signedTransactions[ix] = initial[peerIndex].sign(transactions[ix]);
		}
		long newTimeStamp = ps.getTimeStamp() + peerIndex + 100;
		Block block = Block.of(newTimeStamp, KEYS[peerIndex], signedTransactions);

		ps = ps.proposeBlock(block);
		result[peerIndex] = ps;
		return result;
	}

	@Test
	public void testBasicMerge() throws BadSignatureException, InvalidDataException {
		Peer b0 = initialPeerState(0);
		Peer b1 = initialPeerState(1);
		assertNotEquals(b0, b1); // should not be equal - no knowledge of other peer chains yet

		Peer bm0 = b0.mergeBeliefs(b1.getBelief());
		assertTrue(b0.getPeerOrder() == bm0.getPeerOrder());

		// propose a new block by peer 1, after 200ms
		long newTimestamp1 = TEST_TIMESTAMP + 200;
		b1 = b1.updateTimestamp(b1.getTimeStamp() + 200);
		assertEquals(0, b1.getPeerOrder().getBlocks().size());
		Peer b1a = b1.proposeBlock(Block.of(newTimestamp1,KEYS[1])); // empty block, just with timestamp
		assertEquals(1, b1a.getPeerOrder().getBlocks().size());

		// merge updated belief, new proposed block should be included
		Peer bm2 = b0.mergeBeliefs(b1a.getBelief());
		assertEquals(b1a.getPeerOrder().getBlocks(), bm2.getPeerOrder().getBlocks());
	}

	/**
	 * This test creates a set of peers, and a single transaction sending tokens
	 * from the first peers to the last peer Each round of peers updates is
	 * gossipped simultaneously and the results checked at each stage To validate
	 * correct propagation of the new block across the network
	 * @throws BadSignatureException 
	 * @throws InvalidDataException 
	 * @throws BadFormatException 
	 */
	@Test
	public void testSingleBlockConsensus() throws BadSignatureException, InvalidDataException, BadFormatException {
		boolean ANALYSIS = false;
		Peer[] bs0 = initialBeliefs();
		assertNotEquals(bs0[0].getBelief(), bs0[1].getBelief()); // only have own beliefs
		validateBeliefs(bs0);

		Peer[] bs1 = shareBeliefs(bs0); // sync all beliefs
		assertTrue(allBeliefsEqual(bs1)); // should share beliefs

		Peer[] bs2 = shareBeliefs(bs1); // sync again, should be idempotent
		assertEquals(bs1[0].getBelief(), bs2[0].getBelief()); // belief should not change for peer 0
		assertTrue(allBeliefsEqual(bs2)); // beliefs across peers should be equal

		int PROPOSER = 0;
		int RECEIVER = NUM_PEERS - 1;
		Address PADDRESS = ADDRESSES[PROPOSER];
		Address RADDRESS = ADDRESSES[RECEIVER];
		AccountKey PKEY = KEYS[PROPOSER];
		AccountKey RKEY = KEYS[RECEIVER];
		long INITIAL_BALANCE_PROPOSER = INITIAL_STATE.getBalance(PADDRESS);
		long INITIAL_BALANCE_RECEIVER = INITIAL_STATE.getBalance(RADDRESS);
		long TRANSFER_AMOUNT = 100;
		long TJUICE=Juice.TRANSFER;

		ATransaction trans = Transfer.create(PADDRESS, 1, RADDRESS, TRANSFER_AMOUNT); // note 1 = first sequence number required
		Peer[] bs3 = proposeTransactions(bs2, PROPOSER, trans);
		if (ANALYSIS) printAnalysis(bs3, "Make proposal");
		assertEquals(1, bs3[PROPOSER].getOrder(PKEY).getBlockCount());
		assertEquals(0, bs3[RECEIVER].getOrder(PKEY).getBlockCount());

		// New block should win vote for all peers, but not achieve enough support
		// for proposed consensus yet
		Peer[] bs4 = shareBeliefs(bs3);
		if (ANALYSIS) printAnalysis(bs4, "Share 1st round, each peer should adopt proposed block");
		assertEquals(1, bs4[PROPOSER].getOrder(PKEY).getBlockCount());
		assertEquals(1, bs4[RECEIVER].getOrder(RKEY).getBlockCount());
		assertEquals(0, bs4[PROPOSER].getOrder(RKEY).getBlockCount()); // proposer can't see block in receiver's
																			// chain yet

		// all peers should propose new consensus, but not confirmed yet
		assertEquals(0, bs4[PROPOSER].getOrder(PKEY).getProposalPoint());
		assertEquals(0, bs4[RECEIVER].getOrder(RKEY).getProposalPoint());
		Peer[] bs5 = shareBeliefs(bs4);
		if (ANALYSIS) printAnalysis(bs5,
				"Share 2nd round: each peer should propose consensus after seeing majority for new block");
		assertEquals(1, bs5[PROPOSER].getOrder(PKEY).getProposalPoint());
		assertEquals(1, bs5[RECEIVER].getOrder(RKEY).getProposalPoint());

		// all peers should now agree on consensus, but don't know each other's
		// consensus yet
		assertEquals(0, bs5[PROPOSER].getOrder(PKEY).getConsensusPoint());
		assertEquals(0, bs5[RECEIVER].getOrder(RKEY).getConsensusPoint());
		assertEquals(0, bs5[PROPOSER].getOrder(RKEY).getConsensusPoint());
		Peer[] bs6 = shareBeliefs(bs5);
		if (ANALYSIS) printAnalysis(bs6,
				"Share 3nd round: each peer should confirm consensus after seeing proposals from others");
		assertEquals(1, bs6[PROPOSER].getOrder(PKEY).getConsensusPoint());
		assertEquals(1, bs6[RECEIVER].getOrder(RKEY).getConsensusPoint());
		assertEquals(0, bs6[PROPOSER].getOrder(RKEY).getConsensusPoint());

		Peer[] bs7 = shareBeliefs(bs6);
		if (ANALYSIS) printAnalysis(bs7, "Share 4th round: should reach full consensus, confirmations shared");
		assertEquals(1, bs7[PROPOSER].getOrder(RKEY).getConsensusPoint()); // proposer now sees receivers consensus

		// final state checks
		assertTrue(allBeliefsEqual(bs7)); // beliefs across peers should be equal
		State finalState = bs7[0].getConsensusState();
		assertEquals(INITIAL_BALANCE_PROPOSER-TRANSFER_AMOUNT-TJUICE, finalState.getBalance(PADDRESS));
		assertEquals(INITIAL_BALANCE_RECEIVER+TRANSFER_AMOUNT, finalState.getBalance(RADDRESS));

		// matter cannot be created or destroyed....
		assertEquals(TOTAL_VALUE, finalState.computeTotalFunds());
	}

	/**
	 * This test creates a set of peers, and one transaction for each peer Each
	 * round of peers updates is gossipped simultaneously and the results checked at
	 * each stage To validate correct propagation of the new block across the
	 * network
	 * @throws BadSignatureException 
	 * @throws InvalidDataException 
	 * @throws BadFormatException 
	 */
	@Test
	public void testMultiBlockConsensus() throws BadSignatureException, InvalidDataException, BadFormatException {
		boolean ANALYSIS = false;
		Peer[] bs0 = initialBeliefs();
		assertFalse(allBeliefsEqual(bs0)); // only have own beliefs
		validateBeliefs(bs0);

		Peer[] bs1 = shareBeliefs(bs0); // sync all beliefs
		assertTrue(allBeliefsEqual(bs1)); // should see other beliefs

		Peer[] bs2 = shareBeliefs(bs1); // sync again, should be idempotent
		assertEquals(bs1[0].getBelief(), bs2[0].getBelief()); // belief should not change
		assertTrue(allBeliefsEqual(bs2)); // beliefs across peers should be equal

		int PROPOSER = 0;
		int RECEIVER = NUM_PEERS - 1;
		Address PADDRESS = ADDRESSES[PROPOSER];
		Address RADDRESS = ADDRESSES[RECEIVER];
		AccountKey PKEY = KEYS[PROPOSER];
		AccountKey RKEY = KEYS[RECEIVER];
		Long INITIAL_BALANCE_PROPOSER = INITIAL_STATE.getBalance(PADDRESS);
		Long INITIAL_BALANCE_RECEIVER = INITIAL_STATE.getBalance(RADDRESS);
		long TJUICE=Juice.TRANSFER;

		Peer[] bs3 = bs2;
		for (int i = 0; i < NUM_PEERS; i++) {
			long TRANSFER_AMOUNT = 100L;
			ATransaction trans = Transfer.create(ADDRESSES[i],1, ADDRESSES[NUM_PEERS - 1 - i], TRANSFER_AMOUNT); // note 1 = first
																									// sequence number
																									// required
			bs3 = proposeTransactions(bs3, i, trans);
		}
		if (ANALYSIS) printAnalysis(bs3, "Make proposals");
		assertEquals(1, bs3[0].getOrder(PKEY).getBlockCount());
		assertEquals(1, bs3[RECEIVER].getOrder(RKEY).getBlockCount());
		assertEquals(0, bs3[RECEIVER].getOrder(PKEY).getBlockCount());

		// New block should win vote for all peers, but not achieve enough support
		// for proposed consensus yet
		Peer[] bs4 = shareBeliefs(bs3);
		if (ANALYSIS)
			printAnalysis(bs4, "Share 1st round, each peer should see others chains, vote for same plus new blocks");
		assertEquals(NUM_PEERS, bs4[PROPOSER].getOrder(PKEY).getBlockCount());
		assertEquals(NUM_PEERS, bs4[RECEIVER].getOrder(RKEY).getBlockCount());
		assertEquals(1, bs4[PROPOSER].getOrder(RKEY).getBlockCount()); // proposer can only see 1st block from
																			// receiver
		assertEquals(0, bs4[PROPOSER].getOrder(PKEY).getProposalPoint());
		assertEquals(0, bs4[RECEIVER].getOrder(RKEY).getProposalPoint());

		// Next round
		Peer[] bs5 = shareBeliefs(bs4);
		if (ANALYSIS) printAnalysis(bs5, "Share 2nd round: ");

		// all peers should now agree on consensus
		Peer[] bs6 = shareBeliefs(bs5);
		if (ANALYSIS) printAnalysis(bs6, "Share 3nd round: ");

		Peer[] bs7 = shareBeliefs(bs6);
		if (ANALYSIS) printAnalysis(bs7, "Share 4th round: should reach full consensus?");
		assertEquals(NUM_PEERS, bs7[PROPOSER].getOrder(RKEY).getConsensusPoint()); // proposer now sees receivers
																						// consensus

		// final state checks
		assertTrue(allBeliefsEqual(bs7));
		State finalState = bs7[0].getConsensusState();
		// should have 1 transaction each
		assertEquals(1L, finalState.getAccount(PADDRESS).getSequence());
		assertEquals(1L, finalState.getAccount(RADDRESS).getSequence());
		assertEquals(INITIAL_BALANCE_PROPOSER-TJUICE, finalState.getBalance(PADDRESS));
		assertEquals(INITIAL_BALANCE_RECEIVER-TJUICE, finalState.getBalance(RADDRESS));

		// law of conservation of gil
		assertEquals(TOTAL_VALUE, finalState.computeTotalFunds());
	}

	private boolean allBeliefsEqual(Peer[] pss) {
		int n = pss.length;
		for (int i = 0; i < n - 1; i++) {
			if (!Utils.equals(pss[i].getBelief(), pss[i + 1].getBelief())) return false;
		}
		return true;
	}

	/**
	 * This test creates a set of peers, and one transaction for each peer Each
	 * round of peers updates is gossipped partially To validate correct propagation
	 * of the new block across the network
	 * @throws BadSignatureException 
	 * @throws InvalidDataException 
	 * @throws BadFormatException 
	 */
	@Test
	public void testGossipConsensus() throws BadSignatureException, InvalidDataException, BadFormatException {
		boolean ANALYSIS = false;
		int GOSSIP_NUM = 4;

		Peer[] bs0 = initialBeliefs();
		if (ANALYSIS) printAnalysis(bs0, "Initial beliefs");
		assertFalse(allBeliefsEqual(bs0)); // only have own beliefs
		validateBeliefs(bs0);

		Peer[] bs1 = shareBeliefs(bs0); // sync all beliefs
		assertTrue(allBeliefsEqual(bs1)); // should see other beliefs

		Peer[] bs2 = shareBeliefs(bs1); // sync again, should be idempotent
		assertEquals(bs1[0].getBelief(), bs2[0].getBelief()); // belief should not change
		assertTrue(allBeliefsEqual(bs2)); // beliefs across peers should be equal
		if (ANALYSIS) printAnalysis(bs2, "Shared beliefs");

		int PROPOSER = 0;
		int RECEIVER = NUM_PEERS - 1;
		Address PADDRESS = ADDRESSES[PROPOSER];
		Address RADDRESS = ADDRESSES[RECEIVER];
		AccountKey PKEY = KEYS[PROPOSER];
		AccountKey RKEY = KEYS[RECEIVER];
		long INITIAL_BALANCE_PROPOSER = INITIAL_STATE.getBalance(PADDRESS);
		long INITIAL_BALANCE_RECEIVER = INITIAL_STATE.getBalance(RADDRESS);
		long TJUICE=Juice.TRANSFER*NUM_INITIAL_TRANS;


		Peer[] bs3 = bs2;
		for (int i = 0; i < NUM_PEERS; i++) {
			// propose initial transactions
			for (int j = 1; j <= NUM_INITIAL_TRANS; j++) {
				long TRANSFER_AMOUNT = 100L;
				ATransaction trans = Transfer.create(ADDRESSES[i],j, ADDRESSES[NUM_PEERS - 1 - i], TRANSFER_AMOUNT); // note 1 =
																										// first
																										// sequence
																										// number
																										// required
				bs3 = proposeTransactions(bs3, i, trans);
			}
		}
		if (ANALYSIS) printAnalysis(bs3, "Make proposals");
		assertEquals(NUM_INITIAL_TRANS, bs3[0].getOrder(PKEY).getBlockCount());
		assertEquals(NUM_INITIAL_TRANS, bs3[RECEIVER].getOrder(RKEY).getBlockCount());
		assertEquals(0, bs3[RECEIVER].getOrder(PKEY).getBlockCount());

		Peer[] bs4 = bs3;
		for (int i = 1; i < ROUNDS; i++) {
			bs4 = shareGossip(bs4, GOSSIP_NUM, i);
			if (ANALYSIS) printAnalysis(bs4, "Share round: " + i);
		}
		bs4 = shareGossip(bs4, GOSSIP_NUM, ROUNDS);
		if (ANALYSIS) printAnalysis(bs4, "Share round: " + ROUNDS);

		// final state checks
		assertEquals(NUM_PEERS * NUM_INITIAL_TRANS, bs4[PROPOSER].getOrder(RKEY).getConsensusPoint()); // proposer
																											// now sees
																											// receivers
																											// consensus
		assertTrue(allBeliefsEqual(bs4));

		Order finalChain = bs4[0].getOrder(PKEY);
		AVector<Block> finalBlocks = finalChain.getBlocks();
		assertEquals(NUM_PEERS * NUM_INITIAL_TRANS, new HashSet<>(finalBlocks).size());

		State finalState = bs4[0].getConsensusState();
		AVector<AccountStatus> accounts = finalState.getAccounts();
		if (ANALYSIS) printAccounts(accounts);

		// should have correct number of transactions each
		for (int i = 0; i < NUM_PEERS; i++) {
			assertEquals(NUM_INITIAL_TRANS, accounts.get(ADDRESSES[i].longValue()).getSequence());
		}
		// should have equal balance
		assertEquals(INITIAL_BALANCE_PROPOSER-TJUICE, finalState.getBalance(PADDRESS));
		assertEquals(INITIAL_BALANCE_RECEIVER-TJUICE, finalState.getBalance(RADDRESS));

		// 100% of value still exists
		assertEquals(TOTAL_VALUE, finalState.computeTotalFunds());
		
		RecordTest.doRecordTests(bs4[0].getBelief());
		RecordTest.doRecordTests(finalState);
	}

	private void printAccounts(AVector<AccountStatus> accounts) {
		System.out.println("===== Accounts =====");
		for (int i = 0; i < NUM_PEERS; i++) {
			Address address = ADDRESSES[i];
			AccountStatus as = accounts.get(address);
			System.out.println(peerString(i) + " " + as);
		}
	}

	private void validateBeliefs(Peer[] pss) throws InvalidDataException, BadFormatException {
		for (Peer ps : pss) {
			ps.getBelief().validate();
		}
	}

	private static void printAnalysis(Peer[] beliefs, String msg) throws BadSignatureException {
		System.out.println("===== " + msg + " =====");
		for (int i = 0; i < NUM_PEERS; i++) {
			if ((i >= 5) && (i < NUM_PEERS - 5)) {
				System.out.println(".... (" + (NUM_PEERS - 10) + " peers skipped)");
				i = NUM_PEERS - 6;
				continue;
			}
			Peer ps = beliefs[i];
			Belief b = beliefs[i].getBelief();

			Order c = b.getOrder(KEYS[i]);
			int agreedPeers = 0;
			String mat = "";
			for (int j = 0; j < NUM_PEERS; j++) {
				if ((j >= 5) && (j < NUM_PEERS - 5)) {
					mat += " ....";
					j = NUM_PEERS - 6;
					continue;
				}
				Order jc = b.getOrder(KEYS[j]);
				mat += " " + ((jc == null) ? "----" : jc.getHash().toHexString(4));
				if (c.equals(jc)) agreedPeers++;
			}

			long clen = c.getBlockCount();
			String blockRep = "";
			for (int ix = 0; ix < clen; ix++) {
				blockRep += " " + c.getBlock(ix).getHash().toHexString(2);
			}

			long pp = c.getProposalPoint();
			long cp = c.getConsensusPoint();
			System.out.println(peerString(i) + " clen:" + Text.leftPad(clen, 3) + " prop:" + Text.leftPad(pp, 3)
					+ " cons:" + Text.leftPad(cp, 3) + " state:" + ps.getConsensusState().getHash().toHexString(4)
					+ " hash: " + c.getHash().toHexString(4) + " mat:" + mat + " agreed: " + agreedPeers + "/"
					+ b.getOrders().count() + " blks:" + blockRep);
		}
	}

	private static String peerString(int i) {
		return "Peer: " + Text.rightPad(i, 4) + " [" + ADDRESSES[i].toHexString(4) + "]";
	}

}
