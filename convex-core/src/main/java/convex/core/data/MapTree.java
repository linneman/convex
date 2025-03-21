package convex.core.data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;
import convex.core.util.Bits;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;

/**
 * Persistent Map for large hash maps requiring tree structure.
 * 
 * Internally implemented as a radix tree, indexed by key hash. Uses an array of
 * child Maps, with a bitmap mask indicating which hex digits are present, i.e.
 * have non-empty children.
 *
 * @param <K> Type of map keys
 * @param <V> Type of map values
 */
public class MapTree<K extends ACell, V extends ACell> extends AHashMap<K, V> {
	/**
	 * Child maps, one for each present bit in the mask, max 16
	 */
	private final Ref<AHashMap<K, V>>[] children;

	/**
	 * Shift position of this treemap node in number of hex digits
	 */
	private final int shift;

	/**
	 * Mask indicating which hex digits are present in the child array e.g. 0x0001
	 * indicates all children are in the '0' digit. e.g. 0xFFFF indicates there are
	 * children for every digit.
	 */
	private final short mask;

	private MapTree(Ref<AHashMap<K, V>>[] blocks, int shift, short mask, long count) {
		super(count);
		this.children = blocks;
		this.shift = shift;
		this.mask = mask;
	}

	/**
	 * Computes the total count from an array of Refs to maps Ignores null Refs in
	 * child array
	 * 
	 * @param children
	 * @return The total count of all child maps
	 */
	private static <K extends ACell, V extends ACell> long computeCount(Ref<AHashMap<K, V>>[] children) {
		long n = 0;
		for (Ref<AHashMap<K, V>> cref : children) {
			if (cref == null) continue;
			AMap<K, V> m = cref.getValue();
			n += m.count();
		}
		return n;
	}

	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapTree<K, V> create(MapEntry<K, V>[] newEntries, int shift) {
		int n = newEntries.length;
		if (n <= MapLeaf.MAX_ENTRIES) {
			throw new IllegalArgumentException(
					"Insufficient distinct entries for TreeMap construction: " + newEntries.length);
		}

		// construct full child array
		Ref<AHashMap<K, V>>[] children = new Ref[16];
		for (int i = 0; i < n; i++) {
			MapEntry<K, V> e = newEntries[i];
			int ix = e.getKeyHash().getHexDigit(shift);
			Ref<AHashMap<K, V>> ref = children[ix];
			if (ref == null) {
				children[ix] = MapLeaf.create(e).getRef();
			} else {
				AHashMap<K, V> newChild=ref.getValue().assocEntry(e, shift + 1);
				children[ix] = newChild.getRef();
			}
		}
		return (MapTree<K, V>) createFull(children, shift);
	}

	/**
	 * Creates a Tree map given child refs for each digit
	 * 
	 * @param children An array of children, may refer to nulls or empty maps which
	 *                 will be filtered out
	 * @return
	 */
	private static <K extends ACell, V extends ACell> AHashMap<K, V> createFull(Ref<AHashMap<K, V>>[] children, int shift, long count) {
		if (children.length != 16) throw new IllegalArgumentException("16 children required!");
		Ref<AHashMap<K, V>>[] newChildren = Utils.filterArray(children, a -> {
			if (a == null) return false;
			AMap<K, V> m = a.getValue();
			return ((m != null) && !m.isEmpty());
		});

		if (children != newChildren) {
			return create(newChildren, shift, Utils.computeMask(children, newChildren), count);
		} else {
			return create(children, shift, (short) 0xFFFF, count);
		}
	}

	/**
	 * Create a MapTree with a full compliment of children.
	 * @param <K>
	 * @param <V>
	 * @param newChildren
	 * @param shift
	 * @return
	 */
	private static <K extends ACell, V extends ACell> AHashMap<K, V> createFull(Ref<AHashMap<K, V>>[] newChildren, int shift) {
		return createFull(newChildren, shift, computeCount(newChildren));
	}

	/**
	 * Creates a Map with the specified child map Refs. Removes empty maps passed as
	 * children.
	 * 
	 * Returns a ListMap for small maps.
	 * 
	 * @param children Array of Refs to child maps for each bit in mask
	 * @param shift    Shift position (hex digit of key hashes for this map)
	 * @param mask     Mask specifying the hex digits included in the child array at
	 *                 this shift position
	 * @return A new map as specified @
	 */
	@SuppressWarnings("unchecked")
	private static <K extends ACell, V extends ACell> AHashMap<K, V> create(Ref<AHashMap<K, V>>[] children, int shift, short mask, long count) {
		int cLen = children.length;
		if (Integer.bitCount(mask & 0xFFFF) != cLen) {
			throw new IllegalArgumentException(
					"Invalid child array length " + cLen + " for bit mask " + Utils.toHexString(mask));
		}

		// compress small counts to ListMap
		if (count <= MapLeaf.MAX_ENTRIES) {
			MapEntry<K, V>[] entries = new MapEntry[Utils.checkedInt(count)];
			int ix = 0;
			for (Ref<AHashMap<K, V>> childRef : children) {
				AMap<K, V> child = childRef.getValue();
				long cc = child.count();
				for (long i = 0; i < cc; i++) {
					entries[ix++] = child.entryAt(i);
				}
			}
			assert (ix == count);
			return MapLeaf.create(entries);
		}
		int sel = (1 << cLen) - 1;
		short newMask = mask;
		for (int i = 0; i < cLen; i++) {
			AMap<K, V> child = children[i].getValue();
			if (child.isEmpty()) {
				newMask = (short) (newMask & ~(1 << digitForIndex(i, mask))); // remove from mask
				sel = sel & ~(1 << i); // remove from selection
			}
		}
		if (mask != newMask) {
			return new MapTree<K, V>(Utils.filterSmallArray(children, sel), shift, newMask, count);
		}
		return new MapTree<K, V>(children, shift, mask, count);
	}

	@Override
	public boolean containsKey(ACell key) {
		return containsKeyRef(Ref.get(key));
	}

	@Override
	public MapEntry<K, V> getEntry(ACell k) {
		return getKeyRefEntry(Ref.get(k));
	}

	@Override
	public MapEntry<K, V> getKeyRefEntry(Ref<ACell> ref) {
		int digit = Utils.extractDigit(ref.getHash(), shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return null; // -1 case indicates not found
		return children[i].getValue().getKeyRefEntry(ref);
	}

	@Override
	public boolean containsValue(Object value) {
		for (Ref<AHashMap<K, V>> b : children) {
			if (b.getValue().containsValue(value)) return true;
		}
		return false;
	}

	@Override
	public V get(ACell key) {
		MapEntry<K, V> me = getKeyRefEntry(Ref.get(key));
		if (me == null) return null;
		return me.getValue();
	}

	@Override
	public MapEntry<K, V> entryAt(long i) {
		long pos = i;
		for (Ref<AHashMap<K, V>> c : children) {
			AHashMap<K, V> child = c.getValue();
			long cc = child.count();
			if (pos < cc) return child.entryAt(pos);
			pos -= cc;
		}
		throw new IndexOutOfBoundsException("Entry index: " + i);
	}

	@Override
	protected MapEntry<K, V> getEntryByHash(Hash hash) {
		int digit = Utils.extractDigit(hash, shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return null; // not present
		return children[i].getValue().getEntryByHash(hash);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AHashMap<K, V> dissoc(ACell key) {
		return dissocRef((Ref<K>) Ref.get(key));
	}

	@Override
	@SuppressWarnings("unchecked")
	public AHashMap<K, V> dissocRef(Ref<K> keyRef) {
		int digit = Utils.extractDigit(keyRef.getHash(), shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return this; // not present

		// dissoc entry from child
		AHashMap<K, V> child = children[i].getValue();
		AHashMap<K, V> newChild = child.dissocRef(keyRef);
		if (child == newChild) return this; // no removal, no change

		if (count - 1 == MapLeaf.MAX_ENTRIES) {
			// reduce to a ListMap
			HashSet<Entry<K, V>> eset = entrySet();
			boolean removed = eset.removeIf(e -> Utils.equals(((MapEntry<K, V>) e).getKeyRef(), keyRef));
			if (!removed) throw new Error("Expected to remove at least one entry!");
			return MapLeaf.create(eset.toArray((MapEntry<K, V>[]) MapLeaf.EMPTY_ENTRIES));
		} else {
			// replace child
			if (newChild.isEmpty()) return dissocChild(i);
			return replaceChild(i, newChild.getRef());
		}
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> dissocChild(int i) {
		int bsize = children.length;
		AHashMap<K, V> child = children[i].getValue();
		Ref<AHashMap<K, V>>[] newBlocks = (Ref<AHashMap<K, V>>[]) new Ref<?>[bsize - 1];
		System.arraycopy(children, 0, newBlocks, 0, i);
		System.arraycopy(children, i + 1, newBlocks, i, bsize - i - 1);
		short newMask = (short) (mask & (~(1 << digitForIndex(i, mask))));
		long newCount = count - child.count();
		return create(newBlocks, shift, newMask, newCount);
	}

	@SuppressWarnings("unchecked")
	private MapTree<K, V> insertChild(int digit, Ref<AHashMap<K, V>> newChild) {
		int bsize = children.length;
		int i = Bits.positionForDigit(digit, mask);
		short newMask = (short) (mask | (1 << digit));
		if (mask == newMask) throw new Error("Digit already present!");

		Ref<AHashMap<K, V>>[] newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[bsize + 1];
		System.arraycopy(children, 0, newChildren, 0, i);
		System.arraycopy(children, i, newChildren, i + 1, bsize - i);
		newChildren[i] = newChild;
		long newCount = count + newChild.getValue().count();
		return (MapTree<K, V>) create(newChildren, shift, newMask, newCount);
	}

	/**
	 * Replaces the child ref at a given index position. Will return the same
	 * TreeMap if no change
	 * 
	 * @param i
	 * @param newChild
	 * @return @
	 */
	private MapTree<K, V> replaceChild(int i, Ref<AHashMap<K, V>> newChild) {
		if (children[i] == newChild) return this;
		AHashMap<K, V> oldChild = children[i].getValue();
		Ref<AHashMap<K, V>>[] newChildren = children.clone();
		newChildren[i] = newChild;
		long newCount = count + newChild.getValue().count() - oldChild.count();
		return (MapTree<K, V>) create(newChildren, shift, mask, newCount);
	}

	public static int digitForIndex(int index, short mask) {
		// scan mask for specified index
		int found = 0;
		for (int i = 0; i < 16; i++) {
			if ((mask & (1 << i)) != 0) {
				if (found++ == index) return i;
			}
		}
		throw new IllegalArgumentException("Index " + index + " not available in mask map: " + Utils.toHexString(mask));
	}

	@SuppressWarnings("unchecked")
	@Override
	public MapTree<K, V> assoc(ACell key, ACell value) {
		K k= (K)key;
		Ref<K> keyRef = Ref.get(k);
		return assocRef(keyRef, (V) value, shift);
	}

	@Override
	public MapTree<K, V> assocRef(Ref<K> keyRef, V value) {
		return assocRef(keyRef, value, shift);
	}

	@Override
	protected MapTree<K, V> assocRef(Ref<K> keyRef, V value, int shift) {
		if (this.shift != shift) {
			throw new Error("Invalid shift!");
		}
		int digit = Utils.extractDigit(keyRef.getHash(), shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) {
			// location not present, need to insert new child
			AHashMap<K, V> newChild = MapLeaf.create(MapEntry.createRef(keyRef, Ref.get(value)));
			return insertChild(digit, newChild.getRef());
		} else {
			// child exists, so assoc in new ref at lower shift level
			AHashMap<K, V> child = children[i].getValue();
			AHashMap<K, V> newChild = child.assocRef(keyRef, value, shift + 1);
			return replaceChild(i, newChild.getRef());
		}
	}

	@Override
	public AHashMap<K, V> assocEntry(MapEntry<K, V> e) {
		assert (this.shift == 0); // should never call this on a different shift
		return assocEntry(e, 0);
	}

	@Override
	public MapTree<K, V> assocEntry(MapEntry<K, V> e, int shift) {
		assert (this.shift == shift); // should always be correct shift
		Ref<K> keyRef = e.getKeyRef();
		int digit = Utils.extractDigit(keyRef.getHash(), shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) {
			// location not present
			AHashMap<K, V> newChild = MapLeaf.create(e);
			return insertChild(digit, newChild.getRef());
		} else {
			// location needs update
			AHashMap<K, V> child = children[i].getValue();
			AHashMap<K, V> newChild = child.assocEntry(e, shift + 1);
			if (child == newChild) return this;
			return replaceChild(i, newChild.getRef());
		}
	}

	@Override
	public Set<K> keySet() {
		int len = size();
		HashSet<K> h = new HashSet<K>(len);
		accumulateKeySet(h);
		return h;
	}

	@Override
	protected void accumulateKeySet(HashSet<K> h) {
		for (Ref<AHashMap<K, V>> mr : children) {
			mr.getValue().accumulateKeySet(h);
		}
	}

	@Override
	protected void accumulateValues(ArrayList<V> al) {
		for (Ref<AHashMap<K, V>> mr : children) {
			mr.getValue().accumulateValues(al);
		}
	}

	@Override
	public HashSet<Entry<K, V>> entrySet() {
		int len = size();
		HashSet<Map.Entry<K, V>> h = new HashSet<Map.Entry<K, V>>(len);
		accumulateEntrySet(h);
		return h;
	}

	@Override
	protected void accumulateEntrySet(HashSet<Entry<K, V>> h) {
		for (Ref<AHashMap<K, V>> mr : children) {
			mr.getValue().accumulateEntrySet(h);
		}
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.MAP;
		return encodeRaw(bs,pos);
	}

	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		int ilength = children.length;
		pos = Format.writeVLCLong(bs,pos, count);
		
		bs[pos++] = (byte) shift;
		pos = Utils.writeShort(bs, pos,mask);

		for (int i = 0; i < ilength; i++) {
			pos = children[i].encode(bs,pos);
		}
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		// allow space for tag, shift byte byte, 2 byte mask, embedded child refs
		return 4 + Format.MAX_EMBEDDED_LENGTH * children.length;
	}
	
	public static int MAX_ENCODING_LENGTH = 4 + Format.MAX_EMBEDDED_LENGTH * 16;

	/**
	 * Reads a ListMap from the provided ByteBuffer Assumes the header byte and count is
	 * already read.
	 * 
	 * @param bb ByteBuffer to read from
	 * @param count Count of map entries
	 * @return TreeMap instance as read from ByteBuffer
	 * @throws BadFormatException If encoding is invalid
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapTree<K, V> read(ByteBuffer bb, long count) throws BadFormatException {
		int shift = bb.get();
		short mask = bb.getShort();

		int ilength = Integer.bitCount(mask & 0xFFFF);
		Ref<AHashMap<K, V>>[] blocks = (Ref<AHashMap<K, V>>[]) new Ref<?>[ilength];

		for (int i = 0; i < ilength; i++) {
			// need to read as a Ref
			Ref<AHashMap<K, V>> ref = Format.readRef(bb);
			blocks[i] = ref;
		}
		// create directly, we have all values
		MapTree<K, V> result = new MapTree<K, V>(blocks, shift, mask, count);
		if (!result.isValidStructure()) throw new BadFormatException("Problem with TreeMap invariants");
		return result;
	}

	@Override
	public void forEach(BiConsumer<? super K, ? super V> action) {
		for (Ref<AHashMap<K, V>> sub : children) {
			sub.getValue().forEach(action);
		}
	}

	@Override
	public boolean isCanonical() {
		if (count <= MapLeaf.MAX_ENTRIES) return false;
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return shift==0;
	}

	@Override
	public int getRefCount() {
		return children.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		return (Ref<R>) children[i];
	}

	@SuppressWarnings("unchecked")
	@Override
	public MapTree<K,V> updateRefs(IRefFunction func) {
		int n = children.length;
		if (n == 0) return this;
		Ref<AHashMap<K, V>>[] newChildren = children;
		for (int i = 0; i < n; i++) {
			Ref<AHashMap<K, V>> child = children[i];
			Ref<AHashMap<K, V>> newChild = (Ref<AHashMap<K, V>>) func.apply(child);
			if (child != newChild) {
				if (children == newChildren) {
					newChildren = children.clone();
				}
				newChildren[i] = newChild;
			}
		}
		if (newChildren == children) return this;
		// Note: we assume no key hashes have changed, so structure is the same
		return new MapTree<>(newChildren, shift, mask, count);
	}

	@Override
	public AHashMap<K, V> mergeWith(AHashMap<K, V> b, MergeFunction<V> func) {
		return mergeWith(b, func, this.shift);
	}

	@Override
	protected AHashMap<K, V> mergeWith(AHashMap<K, V> b, MergeFunction<V> func, int shift) {
		if ((b instanceof MapTree)) {
			MapTree<K, V> bt = (MapTree<K, V>) b;
			if (this.shift != bt.shift) throw new Error("Misaligned shifts!");
			return mergeWith(bt, func, shift);
		}
		if ((b instanceof MapLeaf)) return mergeWith((MapLeaf<K, V>) b, func, shift);
		throw new Error("Unrecognised map type: " + b.getClass());
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> mergeWith(MapTree<K, V> b, MergeFunction<V> func, int shift) {
		// assume two TreeMaps with identical prefix and shift
		assert (b.shift == shift);
		int fullMask = mask | b.mask;
		// We are going to build full child list only if needed
		Ref<AHashMap<K, V>>[] newChildren = null;
		for (int digit = 0; digit < 16; digit++) {
			int bitMask = 1 << digit;
			if ((fullMask & bitMask) == 0) continue; // nothing to merge at this index
			AHashMap<K, V> ac = childForDigit(digit).getValue();
			AHashMap<K, V> bc = b.childForDigit(digit).getValue();
			AHashMap<K, V> rc = ac.mergeWith(bc, func, shift + 1);
			if (ac != rc) {
				if (newChildren == null) {
					newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[16];
					for (int ii = 0; ii < digit; ii++) { // copy existing children up to this point
						int chi = Bits.indexForDigit(ii, mask);
						if (chi >= 0) newChildren[ii] = children[chi];
					}
				}
			}
			if (newChildren != null) newChildren[digit] = rc.getRef();
		}
		if (newChildren == null) return this;
		return createFull(newChildren, shift);
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> mergeWith(MapLeaf<K, V> b, MergeFunction<V> func, int shift) {
		Ref<AHashMap<K, V>>[] newChildren = null;
		int ix = 0;
		for (int i = 0; i < 16; i++) {
			int imask = (1 << i); // mask for this digit
			if ((mask & imask) == 0) continue;
			Ref<AHashMap<K, V>> cref = children[ix++];
			AHashMap<K, V> child = cref.getValue();
			MapLeaf<K, V> bSubset = b.filterHexDigits(shift, imask); // filter only relevant elements in b
			AHashMap<K, V> newChild = child.mergeWith(bSubset, func, shift + 1);
			if (child != newChild) {
				if (newChildren == null) {
					newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[16];
					for (int ii = 0; ii < children.length; ii++) { // copy existing children
						int chi = digitForIndex(ii, mask);
						newChildren[chi] = children[ii];
					}
				}
			}
			if (newChildren != null) {
				newChildren[i] = newChild.getRef();
			}
		}
		assert (ix == children.length);
		// if any new children created, create a new Map, else use this
		AHashMap<K, V> result = (newChildren == null) ? this : createFull(newChildren, shift);

		MapLeaf<K, V> extras = b.filterHexDigits(shift, ~mask);
		int en = extras.size();
		for (int i = 0; i < en; i++) {
			MapEntry<K, V> e = extras.entryAt(i);
			V value = func.merge(null, e.getValue());
			if (value != null) {
				// include only new keys where function result is not null. Re-use existing
				// entry if possible.
				result = result.assocEntry(e.withValue(value), shift);
			}
		}
		return result;
	}

	@Override
	public AHashMap<K, V> mergeDifferences(AHashMap<K, V> b, MergeFunction<V> func) {
		return mergeDifferences(b, func,0);
	}
	
	@Override
	protected AHashMap<K, V> mergeDifferences(AHashMap<K, V> b, MergeFunction<V> func,int shift) {
		if ((b instanceof MapTree)) {
			MapTree<K, V> bt = (MapTree<K, V>) b;
			// this is OK, top levels should both have shift 0 and be aligned down the tree.
			if (this.shift != bt.shift) throw new Error("Misaligned shifts!");
			return mergeDifferences(bt, func,shift);
		} else {
			// must be ListMap
			return mergeDifferences((MapLeaf<K, V>) b, func,shift);
		}
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> mergeDifferences(MapTree<K, V> b, MergeFunction<V> func, int shift) {
		// assume two treemaps with identical prefix and shift
		if (this.equals(b)) return this; // no differences to merge
		int fullMask = mask | b.mask;
		Ref<AHashMap<K, V>>[] newChildren = null; // going to build new full child list if needed
		for (int i = 0; i < 16; i++) {
			int bitMask = 1 << i;
			if ((fullMask & bitMask) == 0) continue; // nothing to merge at this index
			Ref<AHashMap<K, V>> aref = childForDigit(i);
			Ref<AHashMap<K, V>> bref = b.childForDigit(i);
			if (aref.equalsValue(bref)) continue; // identical children, no differences
			AHashMap<K, V> ac = aref.getValue();
			AHashMap<K, V> bc = bref.getValue();
			AHashMap<K, V> newChild = ac.mergeDifferences(bc, func,shift+1);
			if (newChild != ac) {
				if (newChildren == null) {
					newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[16];
					for (int ii = 0; ii < 16; ii++) { // copy existing children
						int chi = Bits.indexForDigit(ii, mask);
						if (chi >= 0) newChildren[ii] = children[chi];
					}
				}
			}
			if (newChildren != null) newChildren[i] = (newChild == bc) ? bref : newChild.getRef();
		}
		if (newChildren == null) return this;
		return createFull(newChildren, shift);
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> mergeDifferences(MapLeaf<K, V> b, MergeFunction<V> func, int shift) {
		Ref<AHashMap<K, V>>[] newChildren = null;
		int ix = 0;
		for (int i = 0; i < 16; i++) {
			int imask = (1 << i); // mask for this digit
			if ((mask & imask) == 0) continue;
			Ref<AHashMap<K, V>> cref = children[ix++];
			AHashMap<K, V> child = cref.getValue();
			MapLeaf<K, V> bSubset = b.filterHexDigits(shift, imask); // filter only relevant elements in b
			AHashMap<K, V> newChild = child.mergeDifferences(bSubset, func,shift+1);
			if (child != newChild) {
				if (newChildren == null) {
					newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[16];
					for (int ii = 0; ii < children.length; ii++) { // copy existing children
						int chi = digitForIndex(ii, mask);
						newChildren[chi] = children[ii];
					}
				}
			}
			if (newChildren != null) newChildren[i] = newChild.getRef();
		}
		assert (ix == children.length);
		AHashMap<K, V> result = (newChildren == null) ? this : createFull(newChildren, shift);

		MapLeaf<K, V> extras = b.filterHexDigits(shift, ~mask);
		int en = extras.size();
		for (int i = 0; i < en; i++) {
			MapEntry<K, V> e = extras.entryAt(i);
			V value = func.merge(null, e.getValue());
			if (value != null) {
				// include only new keys where function result is not null. Re-use existing
				// entry if possible.
				result = result.assocEntry(e.withValue(value), shift);
			}
		}
		return result;
	}

	/**
	 * Gets the Ref for the child at the given digit, or an empty map if not found
	 * 
	 * @param digit The hex digit to query at this TreeMap's shift position
	 * @return The child map for this digit, or an empty map if the child does not
	 *         exist
	 */
	private Ref<AHashMap<K, V>> childForDigit(int digit) {
		int ix = Bits.indexForDigit(digit, mask);
		if (ix < 0) return Maps.emptyRef();
		return children[ix];
	}

	@Override
	public <R> R reduceValues(BiFunction<? super R, ? super V, ? extends R> func, R initial) {
		int n = children.length;
		R result = initial;
		for (int i = 0; i < n; i++) {
			result = children[i].getValue().reduceValues(func, result);
		}
		return result;
	}

	@Override
	public <R> R reduceEntries(BiFunction<? super R, MapEntry<K, V>, ? extends R> func, R initial) {
		int n = children.length;
		R result = initial;
		for (int i = 0; i < n; i++) {
			result = children[i].getValue().reduceEntries(func, result);
		}
		return result;
	}

	@Override
	public boolean equalsKeys(AMap<K, V> a) {
		if (a instanceof MapTree) return equalsKeys((MapTree<K, V>) a);
		// different map type cannot possibly be equal
		return false;
	}

	boolean equalsKeys(MapTree<K, V> a) {
		if (this == a) return true;
		if (this.count != a.count) return false;
		if (this.mask != a.mask) return false;
		int n = children.length;
		for (int i = 0; i < n; i++) {
			if (!children[i].getValue().equalsKeys(a.children[i].getValue())) return false;
		}
		return true;
	}

	@Override
	public boolean equals(AMap<K, V> a) {
		if (!(a instanceof MapTree)) return false;
		return equals((MapTree<K, V>) a);
	}

	boolean equals(MapTree<K, V> b) {
		if (this == b) return true;
		long n = count;
		if (n != b.count) return false;
		if (mask != b.mask) return false;
		if (shift != b.shift) return false;

		// Fall back to comparing hashes. Probably most efficient in general.
		if (getHash().equals(b.getHash())) return true;
		return false;
	}

	@Override
	public AHashMap<K, V> mapEntries(Function<MapEntry<K, V>, MapEntry<K, V>> func) {
		int n = children.length;
		if (n == 0) return this;
		Ref<AHashMap<K, V>>[] newChildren = children;
		for (int i = 0; i < n; i++) {
			AHashMap<K, V> child = children[i].getValue();
			AHashMap<K, V> newChild = child.mapEntries(func);
			if (child != newChild) {
				if (children == newChildren) {
					newChildren = children.clone();
				}
				newChildren[i] = newChild.getRef();
			}
		}
		if (newChildren == children) return this;

		// Note: creation should remove any empty children. Need to recompute count
		// since
		// entries may have been removed.
		return create(newChildren, shift, mask, computeCount(newChildren));
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		// Perform validation for this tree position
		validateWithPrefix("");
	}

	@Override
	protected void validateWithPrefix(String prefix) throws InvalidDataException {
		if (mask == 0) throw new InvalidDataException("TreeMap must have children!", this);
		if (shift != prefix.length()) {
			throw new InvalidDataException("Invalid prefix [" + prefix + "] for TreeMap with shift=" + shift, this);
		}
		int bsize = children.length;

		long childCount=0;;
		for (int i = 0; i < bsize; i++) {
			if (children[i] == null)
				throw new InvalidDataException("Null child ref at " + prefix + Utils.toHexChar(digitForIndex(i, mask)),
						this);
			ACell o = children[i].getValue();
			if (!(o instanceof AHashMap)) {
				throw new InvalidDataException(
						"Expected map child at " + prefix + Utils.toHexChar(digitForIndex(i, mask)), this);
			}
			@SuppressWarnings("unchecked")
			AHashMap<K, V> child = (AHashMap<K, V>) o;
			if (child.isEmpty())
				throw new InvalidDataException("Empty child at " + prefix + Utils.toHexChar(digitForIndex(i, mask)),
						this);
			int d = digitForIndex(i, mask);
			child.validateWithPrefix(prefix + Utils.toHexChar(d));
			
			childCount += child.count();
		}
		
		if (count != childCount) {
			throw new InvalidDataException("Bad child count, expected " + count + " but children had: " + childCount, this);
		}
	}

	private boolean isValidStructure() {
		if (count <= MapLeaf.MAX_ENTRIES) return false;
		if (children.length != Integer.bitCount(mask & 0xFFFF)) return false;
		for (int i = 0; i < children.length; i++) {
			if (children[i] == null) return false;
		}
		return true;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (!isValidStructure()) throw new InvalidDataException("Bad structure", this);
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean containsAllKeys(AHashMap<K, V> map) {
		if (map instanceof MapTree) {
			return containsAllKeys((MapTree<K,V>)map);
		}
		// must be a MapLeaf
		long n=map.count;
		for (long i=0; i<n; i++) {
			MapEntry<K,V> me=map.entryAt(i);
			if (!this.containsKeyRef((Ref<ACell>) me.getKeyRef())) return false;
		}
		return true;
	}
	
	protected boolean containsAllKeys(MapTree<K, V> map) {
		// fist check this mask contains all of target mask
		if ((this.mask|map.mask)!=this.mask) return false;
		
		for (int i=0; i<16; i++) {
			Ref<AHashMap<K,V>> child=this.childForDigit(i);
			if (child==null) continue;
			
			Ref<AHashMap<K,V>> mchild=map.childForDigit(i);
			if (mchild==null) continue;
			
			if (!(child.getValue().containsAllKeys(mchild.getValue()))) return false; 
		}
		return true;
	}

	@Override
	public byte getTag() {
		return Tag.MAP;
	}

	@Override
	public AHashMap<K,V> toCanonical() {
		if (count > MapLeaf.MAX_ENTRIES) return this;
		// shouldn't be possible?
		throw new TODOException();
	}

}
