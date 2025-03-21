package convex.core.data;

import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;

/**
 * Abstract base class for vectors.
 * 
 * Vectors are immutable sequences of values, with efficient appends to the tail
 * of the list.
 * 
 * This is a hierarchy with multiple implementations for different vector types,
 * but all should conform to the general AVector interface. We use an abstract
 * base class in preference to an interface because we control the hierarchy and
 * it offers some mild performance advantages.
 * 
 * General design goals: - Immutability - Cell structure breakdown for larger
 * vectors, while keeping a shallow tree - Optimised performance for end of
 * vector (conj, pop, last etc.) - Fast prefix comparisons to support consensus
 * algorithm
 *
 * "If I had any recommendation to you at all, it's just if you're thinking
 * about designing a system and you're not sure, whether you can answer all that
 * questions in the forward direction, choose immutability. You can almost back
 * into a little more than 50% of this design just by haven taken immutability
 * as a constraint, saying 'oh my god now what am I gonna do? I cannot change
 * this. I better do this!' And keep forcing you into good answers. So if I had
 * any architectural guidance from this: Just do it. Choose immutability and see
 * where it takes you." - Rich Hickey
 *
 * @param <T> Type of element in Vector
 */
public abstract class AVector<T extends ACell> extends ASequence<T> {

	
	public AVector(long count) {
		super(count);
	}

	@Override
	public AType getType() {
		return Types.VECTOR;
	}
	
	/**
	 * Gets the element at the specified index in this vector
	 * 
	 * @param i The index of the element to get
	 * @return The element value at the specified index
	 */
	@Override
	public abstract T get(long i);

	/**
	 * Appends a ListVector chunk to this vector. This vector must contain a whole
	 * number of chunks
	 * 
	 * @param listVector A chunk to append. Must be a ListVector of maximum size
	 * @return The updated vector, of the same type as this vector @
	 */
	public abstract AVector<T> appendChunk(VectorLeaf<T> listVector);

	/**
	 * Gets the VectorLeaf chunk at a given offset
	 * 
	 * @param offset Offset into this vector. Must be a valid chunk start position
	 * @return The chunk referenced
	 */
	public abstract VectorLeaf<T> getChunk(long offset);

	/**
	 * Appends a single element to this vector
	 * 
	 * @param value Value to append
	 * @return Updated vector
	 */
	public abstract AVector<T> append(T value);

	/**
	 * Returns true if this Vector is a single fully packed tree. i.e. a full
	 * ListVector or TreeVector.
	 * 
	 * @return true is fully packed, flase otherwise
	 */
	public abstract boolean isPacked();

	@Override
	public void print(StringBuilder sb) {
		sb.append('[');
		int size = size();
		for (int i = 0; i < size; i++) {
			if (i > 0) sb.append(' ');
			Utils.print(sb,get(i));
		}
		sb.append(']');
	}



	@Override
	public T get(int index) {
		return get((long) index);
	}

	public abstract boolean anyMatch(Predicate<? super T> pred);

	public abstract boolean allMatch(Predicate<? super T> pred);

	@Override
	public abstract <R extends ACell> AVector<R> map(Function<? super T, ? extends R> mapper);

	@Override
	@SuppressWarnings("unchecked")
	public <R extends ACell> AVector<R> flatMap(Function<? super T, ? extends ASequence<R>> mapper) {
		ASequence<ASequence<R>> vals = this.map(mapper);
		AVector<R> result = (AVector<R>) this.empty();
		for (ASequence<R> seq : vals) {
			result = result.concat(seq);
		}
		return result;
	}

	@Override
	public abstract <R extends ACell> AVector<R> concat(ASequence<R> b);

	public abstract <R> R reduce(BiFunction<? super R, ? super T, ? extends R> func, R value);

	@Override
	public Spliterator<T> spliterator() {
		return spliterator(0);
	}

	public abstract Spliterator<T> spliterator(long position);

	@Override
	public Iterator<T> iterator() {
		return listIterator();
	}

	@Override
	public Object[] toArray() {
		int s = size();
		Object[] result = new Object[s];
		copyToArray(result, 0);
		return result;
	}

	@Override
	public final int indexOf(Object o) {
		return Utils.checkedInt(longIndexOf(o));
	}

	@Override
	public final int lastIndexOf(Object o) {
		return Utils.checkedInt(longLastIndexOf(o));
	}

	@Override
	public final ListIterator<T> listIterator(int index) {
		return listIterator((long) index);
	}

	@Override
	public abstract ListIterator<T> listIterator(long index);

	/**
	 * Returns true if this vector is in canonical format, i.e. suitable as
	 * top-level serialised representation of a vector.
	 * 
	 * @return true if the vector is in canonical format, false otherwise
	 */
	@Override
	public abstract boolean isCanonical();

	@Override
	public abstract AVector<T> updateRefs(IRefFunction func);

	/**
	 * Computes the length of the longest common prefix of this vector and another
	 * vector.
	 * 
	 * @param b Any vector
	 * @return Length of the longest common prefix
	 */
	public abstract long commonPrefixLength(AVector<T> b);

	public AVector<T> appendAll(List<T> list) {
		// TODO Optimise with chunks
		AVector<T> result = this;
		for (T value : list) {
			result = result.append(value);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public final <R extends ACell> AVector<R> conj(R value) {
		return (AVector<R>) append((T) value);
	}
	
	public <R extends ACell> AVector<R> conjAll(ACollection<R> xs) {
		if (xs instanceof ASequence) {
			return concat((ASequence<R>)xs);
		}
		return concat(Vectors.create(xs));
	}

	@Override
	public AList<T> cons(T x) {
		return Lists.create(this).cons(x);
	}

	@Override
	public abstract AVector<T> next();

	@Override
	public final AVector<T> slice(long start, long length) {
		return subVector(start, length);
	}

	@Override
	public abstract <R extends ACell> AVector<R> assoc(long i, R value);

	@Override
	public AVector<T> empty() {
		return Vectors.empty();
	}
	
	@Override
	public AList<T> reverse() {
		return convex.core.data.List.reverse(this);
	}

	/**
	 * Merges this vector with another vector, using the provided merge function.
	 * 
	 * Returns the same vector if the result is equal to this vector, or the other
	 * vector if the result is exactly equal to the other vector.
	 * 
	 * The merge function is passed null for elements where one vector is shorter
	 * than the other.
	 * 
	 * @param b    Another vector
	 * @param func A merge function to apply to all elements of this and the other
	 *             vector
	 * @return A new vector, equal in length to the largest of the two vectors
	 *         passed @
	 */
	public AVector<T> mergeWith(AVector<T> b, MergeFunction<T> func) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public byte getTag() {
		return Tag.VECTOR;
	}
}
