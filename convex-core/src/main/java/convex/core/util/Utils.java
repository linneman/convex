package convex.core.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import convex.core.Constants;
import convex.core.State;
import convex.core.data.AArrayBlob;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AObject;
import convex.core.data.ASequence;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.TODOException;
import convex.core.lang.RT;

public class Utils {
	public static final byte[] EMPTY_BYTES = new byte[0];

	/**
	 * Converts an array of bytes into an unsigned BigInteger
	 *
	 * Assumes big-endian format as per new BigInteger(int, byte[]);
	 *
	 * @param data Array of bytes containing an unsigned integer (big-endian)
	 * @return A new non-negative BigInteger
	 */
	public static BigInteger toBigInteger(byte[] data) {
		return new BigInteger(1, data);
	}

	/**
	 * Converts an array of bytes into a signed BigInteger
	 *
	 * Assumes two's-complement big-endian binary representation format as per new
	 * BigInteger(byte[]);
	 *
	 * @param data Byte array to convert to BigInteger
	 * @return A signed BigInteger
	 */
	public static BigInteger toSignedBigInteger(byte[] data) {
		return new BigInteger(data);
	}

	/**
	 * Converts an int to a hex string e.g. "80cafe80"
	 *
	 * @param val Value to convert
	 * @return Lowercase hex string
	 */
	public static String toHexString(int val) {
		StringBuffer sb = new StringBuffer(8);
		for (int i = 0; i < 8; i++) {
			sb.append(Utils.toHexChar((val >> ((7 - i) * 4)) & 0xf));
		}
		return sb.toString();
	}

	public static String toHexString(short val) {
		StringBuffer sb = new StringBuffer(4);
		for (int i = 0; i < 4; i++) {
			sb.append(Utils.toHexChar((val >> ((3 - i) * 4)) & 0xf));
		}
		return sb.toString();
	}

	/**
	 * Converts a byte to a two-character hex string
	 *
	 * @param value Value to convert
	 * @return Lowercase hex string
	 */
	public static String toHexString(byte value) {
		StringBuffer sb = new StringBuffer(2);
		sb.append(toHexChar((((int) value) & 0xF0) >>> 4));
		sb.append(toHexChar(((int) value) & 0xF));
		return sb.toString();
	}

	/**
	 * Converts a long value to a 16 character hex string
	 *
	 * @param x Value to convert
	 * @return Hex string for the given long
	 */
	public static String toHexString(long x) {
		StringBuffer sb = new StringBuffer(16);
		for (int i = 15; i >= 0; i--) {
			sb.append(toHexChar(((int) (x >> (4 * i))) & 0xF));
		}
		return sb.toString();
	}

	/**
	 * Converts a hex string to a friendly version ( first x chars).
	 * SECURITY; do not use this output for any comparison.
	 *
	 * @param hexString String to show in friendly format.
     * @param size Number of hex chars to output.
	 * @return Hex String
	 */
	public static String toFriendlyHexString(String hexString, int size) {
		String cleanHexString = hexString.replaceAll("^0[Xx]", "");
		String result = cleanHexString.substring(0, size);
		// + ".." + cleanHexString.substring(cleanHexString.length() - size);
		return result;
	}
	/**
	 * Reads an int from a specified location in a byte array Assumes 4-byte
	 * big-endian representation
	 *
	 * @param data Byte array from which to read the 4-byte int representation
	 * @param offset Offset into byte array to read
	 * @return int value from array
	 */
	public static int readInt(byte[] data, int offset) {
		int result = data[offset];
		for (int i = 1; i <= 3; i++) {
			result = (result << 8) + (data[offset + i] & 0xFF);
		}
		return result;
	}

	public static long readLong(byte[] data, int offset) {
		long result = data[offset];
		for (int i = 1; i <= 7; i++) {
			result = (result << 8) + (data[offset + i] & 0xFF);
		}
		return result;
	}

	/**
	 * Reads a short from a specified location in a byte array Assumes 2-byte
	 * big-endian representation
	 *
	 * @param data Byte array from which to read the 2-byte short representation
	 * @param offset Offset into byte array to read
	 * @return short value from array
	 */
	public static short readShort(byte[] data, int offset) {
		int result = ((data[offset] & 0xFF) << 8) + (data[offset + 1] & 0xFF);
		return (short) result;
	}

	/**
	 * Writes an char to a byte array in 2 byte big-endian representation
	 *
	 * @param value  int value to write to the array
	 * @param data   Byte array into which to write the given int
	 * @param offset Offset into the array at which the int will be written
	 * @return Offset after writing
	 */
	public static int writeChar(byte[] data, int offset,char value) {
		data[offset++]=(byte)(value>>8);
		data[offset++]=(byte)(value);
		return offset;
	}

	/**
	 * Writes an char to a byte array in 2 byte big-endian representation
	 *
	 * @param value  int value to write to the array
	 * @param data   Byte array into which to write the given int
	 * @param offset Offset into the array at which the int will be written
	 * @return Offset after writing
	 */
	public static int writeShort(byte[] data, int offset,short value) {
		data[offset++]=(byte)(value>>8);
		data[offset++]=(byte)(value);
		return offset;
	}

	/**
	 * Writes an int to a byte array in 4 byte big-endian representation
	 *
	 * @param value  int value to write to the array
	 * @param data   Byte array into which to write the given int
	 * @param offset Offset into the array at which the int will be written
	 * @return Offset after writing
	 */
	public static int writeInt(byte[] data, int offset,int value) {
		for (int i = 0; i <= 3; i++) {
			data[offset + i] = (byte) ((value >> (8 * (3 - i))) & 0xFF);
		}
		return offset+4;
	}

	/**
	 * Writes a long to a byte array in 8 byte big-endian representation.
	 *
	 * @param value  long value to write to the array
	 * @param data   Byte array into which to write the given long
	 * @param offset Offset into the array at which the long will be written
	 *
	 * @throws IndexOutOfBoundsException If long reaches outside the destination
	 *                                   byte array
	 * @return Offset after writing 8 bytes
	 */
	public static int writeLong(byte[] data, int offset,long value) {
		for (int i = 0; i <= 7; i++) {
			data[offset + i] = (byte) (value >> (8 * (7 - i)));
		}
		return offset+8;
	}

	/**
	 * Reads ByteBuffer contents into a new byte array
	 *
	 * @param bb ByteBuffer
	 * @return New byte array
	 */
	public static byte[] toByteArray(ByteBuffer bb) {
		int len = bb.remaining();
		byte[] bytes = new byte[len];
		bb.get(bytes);
		return bytes;
	}

	/**
	 * Reads ByteBuffer contents into a new Data object
	 *
	 * @param bb ByteBuffer
	 * @return Blob extracted from ByteBuffer
	 */
	public static AArrayBlob toData(ByteBuffer bb) {
		return Blob.wrap(toByteArray(bb));
	}

	/**
	 * Converts an int value in the range 0..15 to a hexadecimal character
	 *
	 * @param i Value to convert
	 * @return Hex digit value (lowercase)
	 */
	public static char toHexChar(int i) {
		if (i >= 0) {
			if (i <= 9) return (char) (i + 48);
			if (i <= 15) return (char) (i + 87);
		}
		throw new IllegalArgumentException("Unable to convert to single hex char: " + i);
	}

	/**
	 * Converts a hex string to a byte array. Must contain an even number of hex
	 * digits, or else null will be returned
	 *
	 * @param hex String containing Hex digits
	 * @return byte array with the given hex value, or null if string is not valid
	 */
	public static byte[] hexToBytes(String hex) {
		byte[] bs= hexToBytes(hex, hex.length());
		return bs;
	}

	/**
	 * Converts a hex string to a byte array. Must contain an the expected number of
	 * hex digits, or else null will be returned
	 *
	 * @param hex          String containing Hex digits
	 * @param stringLength number of hex digits in the string to use
	 * @return byte array with the given hex value, or null if not valud
	 */
	public static byte[] hexToBytes(String hex, int stringLength) {
		if (hex.length() != stringLength) {
			return null;
		}
		int N = stringLength / 2;
		if (N * 2 != stringLength) {
			return null;
		}
		byte[] result = new byte[N];

		for (int i = 0; i < N; i++) {
			char high = hex.charAt(2 * i);
			char low = hex.charAt(2 * i + 1);
			int lowD = Utils.hexVal(low);
			if (lowD < 0) return null;
			int highD = Utils.hexVal(high);
			if (highD < 0) return null;
			result[i] = (byte) (highD * 16 + lowD);
		}

		return result;
	}

	/**
	 * Converts a hex string to an unsigned big Integer
	 *
	 * @param hex Value to convert
	 * @return BigInteger
	 */
	public static BigInteger hexToBigInt(String hex) {
		return new BigInteger(1, hexToBytes(hex));
	}

	/**
	 * Gets the value of a single hex car e.g. hexVal('c') => 12
	 *
	 * @param c Character representing a hex digit
	 * @return int in the range 0..15 inclusive, or -1 if not a hex char
	 */
	public static int hexVal(char c) {
		int v = (int) c;
		if (v <= 102) {
			if (v >= 97) return v - 87; // lowercase
			if ((v >= 65) && (v <= 70)) return v - 55; // uppercase
			if ((v >= 48) && (v <= 57)) return v - 48; // digit
		}
		return -1;
	}

	/**
	 * Converts a byte array of length N to a hex string of length 2N
	 *
	 * @param data Array of bytes
	 * @return Hex String
	 */
	public static String toHexString(byte[] data) {
		return toHexString(data, 0, data.length);
	}

	/**
	 * Converts a slice of a byte array to a hex string of length 2N
	 *
	 * @param data Array of bytes
	 * @param offset Start offset to read from byte array
	 * @param length Length in bytes to read from byte array
	 * @return Hex String
	 */
	public static String toHexString(byte[] data, int offset, int length) {
		char[] hexDigits = new char[length * 2];
		for (int i = 0; i < length; i++) {
			int v = ((int) data[i + offset]) & 0xFF;
			hexDigits[i * 2] = toHexChar(v >>> 4);
			hexDigits[i * 2 + 1] = toHexChar(v & 0xF);
		}
		return new String(hexDigits);
	}

	/**
	 * Gets the Java hashCode of any value.
	 *
	 * The hashCode of null is defined as zero
	 *
	 * @param a Any Java Object, may be null
	 * @return hash code
	 */
	public static int hashCode(Object a) {
		if (a == null) return 0;
		return a.hashCode();
	}

	/**
	 * Tests if two byte array regions are identical
	 *
	 * @param a First array
	 * @param aOffset Offset into first array
	 * @param b Second array
	 * @param bOffset Offset into second array
	 * @param length Number of bytes to compare
	 * @return true if array regions are equal, false otherwise
	 */
	public static boolean arrayEquals(byte[] a, int aOffset, byte[] b, int bOffset, int length) {
		return Arrays.equals(a, aOffset, aOffset+length, b, bOffset, bOffset+length);
	}

	/**
	 * Compares two byte arrays on an unsigned basis. Shorter arrays will be
	 * considered "smaller" if they match in all other positions.
	 *
	 * @param a First array
	 * @param aOffset Offset into first array
	 * @param b Second array
	 * @param bOffset Offset into second array
	 * @param maxLength The maximum size for comparison. If arrays are equal up to
	 *                  this length, will return 0
	 * @return Negative if a is 'smaller', 0 if a 'equals' b, positive if a is
	 *         'larger'.
	 */
	public static int compareByteArrays(byte[] a, int aOffset, byte[] b, int bOffset, int maxLength) {
		int length = Math.min(maxLength, a.length - aOffset);
		length = Math.min(maxLength, b.length - bOffset);
		for (int i = 0; i < length; i++) {
			int ai = 0xFF & a[aOffset + i];
			int bi = 0xFF & b[bOffset + i];
			if (ai < bi) return -1;
			if (ai > bi) return 1;
		}
		if (length < a.length) return 1; // longer a considered larger
		if (length < b.length) return -1; // shorter a considered smaller
		return 0;
	}

	/**
	 * Converts an unsigned BigInteger to a hex string with the given number of
	 * digits Truncates any high bytes beyond the given digits.
	 *
	 * @param a Value to convert
	 * @param digits Number of hex digits to produce
	 * @return String containing the hex representation
	 */
	public static String toHexString(BigInteger a, int digits) {
		if (a.signum() < 0)
			throw new IllegalArgumentException("toHexString requires a non-negative BigInteger, got :" + a);
		String s = a.toString(16); // note: only works with unsigned big integers otherwise we get "-2a" etc.
		int slen = s.length();
		if (slen > digits) throw new IllegalArgumentException("toHexString number of digits exceeded, got :" + slen);
		if (slen == digits) return s;
		StringBuffer sb = new StringBuffer(digits);
		while (slen < digits) {
			sb.append('0');
			slen++;
		}
		sb.append(s);
		return sb.toString();
	}

	/**
	 * Writes an unsigned big integer to a specific segment of a byte[] array. Pads
	 * with zeros if necessary to fill the specified length.
	 *
	 * @param a Value to write
	 * @param dest Destination array
	 * @param offset Offset into destination array
	 * @param length Length to write
	 */
	public static void writeUInt(BigInteger a, byte[] dest, int offset, int length) {
		if (a.signum() < 0) throw new IllegalArgumentException("Non-negative big integer expected!");
		if ((offset + length) > dest.length) {
			throw new IllegalArgumentException(
					"Insufficient buffer space in byte array, available = " + (dest.length - offset));
		}
		byte[] bs = a.toByteArray();
		int bl = bs.length;
		if (bl == length) {
			// expected case, correct number of bytes in unsigned representation
			System.arraycopy(bs, 0, dest, offset, length);
		} else if ((bl == (length + 1)) && (bs[0] == 0)) {
			// OK because this is just an overflow of sign bit
			// We just need to skip the zero bute that includes the sign
			System.arraycopy(bs, 1, dest, offset, length);
		} else if (bl < length) {
			// rare case, our representation is too short, so need to pad
			int pad = length - bl;
			Arrays.fill(dest, offset, offset + pad, (byte) 0);
			System.arraycopy(bs, 0, dest, offset + pad, bl);
		} else {
			throw new IllegalArgumentException("Insufficient buffer size, was " + length + " but needed " + bl);
		}
	}

	/**
	 * Converts a String to a byte array using UTF-8 encoding
	 *
	 * @param s Any String
	 * @return Byte array
	 */
	public static byte[] toByteArray(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Converts any array to an Object[] array
	 *
	 * @param anyArray Array to convert
	 * @return Object[] array
	 */
	public static Object[] toObjectArray(Object anyArray) {
		if (anyArray instanceof Object[]) return (Object[]) anyArray;
		int n = Array.getLength(anyArray);
		Object[] result = new Object[n];
		for (int i = 0; i < n; i++) {
			result[i] = Array.get(anyArray, i);
		}
		return result;
	}

	/**
	 * Converts any array to an ACell[] array. Elements must be Cells.
	 *
	 * @param anyArray Array to convert
	 * @return ACell[] array
	 */
	public static ACell[] toCellArray(Object anyArray) {
		int n = Array.getLength(anyArray);
		ACell[] result = new ACell[n];
		for (int i = 0; i < n; i++) {
			result[i] = (ACell) Array.get(anyArray, i);
		}
		return result;
	}

	/**
	 * Equality method allowing for nulls
	 *
	 * @param a First value
	 * @param b Second value
	 * @return true if arguments are equal, false otherwise
	 */
	public static boolean equals(Object a, Object b) {
		if (a == b) return true;
		if (a == null) return false; // b can't be null because of above line
		return a.equals(b); // fall back to Object equality
	}

	/**
	 * Equality method allowing for nulls
	 *
	 * @param a First value
	 * @param b Second value
	 * @return true if arguments are equal, false otherwise
	 */
	public static boolean equals(ACell a, ACell b) {
		if (a == b) return true;
		if (a == null) return false; // b can't be null because of above line
		return a.equals(b); // fall back to Object equality
	}

	/**
	 * Gets a hex digit as an integer 0-15 value from a Data object
	 *
	 * @param data     Blob containing byte values
	 * @param hexDigit Position of hex digit to extract (from start of blob)
	 * @return Hex digit value as an integer 0..15 inclusive
	 */
	public static int extractDigit(ABlob data, int hexDigit) {
		return data.getHexDigit(hexDigit);
	}

	/**
	 * Gets the class of an Object, or null if the value is null
	 *
	 * @param o Object to examine
	 * @return Class of the Object
	 */
	public static Class<?> getClass(Object o) {
		if (o == null) return null;
		return o.getClass();
	}

	/**
	 * Gets the class name of an Object, or "null" if the value is null
	 *
	 * @param o Object to examine
	 * @return Class name of the Object
	 */
	public static String getClassName(Object o) {
		Class<?> klass = getClass(o);
		return (klass == null) ? "null" : klass.getName();
	}

	/**
	 * Converts a long to an int, throws error if out of allowable range.
	 *
	 * @param a Value to convert
	 * @return int value of the long if in valid Integer range
	 */
	public static int checkedInt(long a) {
		int i = (int) a;
		if (a != i) throw new IllegalArgumentException(Errors.sizeOutOfRange(a));
		return i;
	}

	/**
	 * Converts a long to a short, throws error if out of allowable range.
	 *
	 * @param a Value to convert
	 * @return short value of the long if in valid Short range
	 */
	public static short checkedShort(long a) {
		short s = (short) a;
		if (s != a) throw new IllegalArgumentException(Errors.sizeOutOfRange(a));
		return s;
	}

	/**
	 * Converts a long to a byte, throws error if out of allowable range.
	 *
	 * @param a Value to convert
	 * @return byte value of the long if in valid Byte range
	 */
	public static byte checkedByte(long a) {
		byte b = (byte) a;
		if (b != a) throw new IllegalArgumentException(Errors.sizeOutOfRange(a));
		return b;
	}

	/**
	 * Writes an unsigned BigInteger as 32 bytes into a ByteBuffer
	 *
	 * @param b A ByteBuffer with at least 32 bytes capacity
	 * @param v A BigInteger in the unsigned 256 bit integer range
	 * @return The ByteBuffer with 32 bytes written
	 */
	public static ByteBuffer writeUInt256(ByteBuffer b, BigInteger v) {
		if (v.signum() < 0) throw new IllegalArgumentException("Non-negative integer expected");
		byte[] bs = v.toByteArray();
		byte[] buf = new byte[32];
		int blen = bs.length; // length to use
		if (blen <= 32) {
			System.arraycopy(bs, 0, buf, 32 - blen, blen);
		} else if ((blen == 33) && (bs[0] == 0)) {
			// OK since this is UInt256 range, take last 32 bytes
			System.arraycopy(bs, blen - 32, buf, 0, 32);
		} else {
			throw new IllegalArgumentException("BigInteger too large for UInt256, length in bytes=" + blen);
		}
		return b.put(buf);
	}

	/**
	 * Reads an unsigned BigInteger as 32 bytes from a ByteBuffer
	 *
	 * @param b ByteBuffer from which to extract 32 bytes
	 * @return A non-negative BigInteger containing the unsigned big-endian value
	 *         from the 32 bytes read
	 */
	public static BigInteger readUInt256(ByteBuffer b) {
		byte[] buf = new byte[32];
		b.get(buf);
		return new BigInteger(1, buf);
	}

	/**
	 * Returns the minimal number of bits to represent the signed twos complement
	 * long value. Return value will be at least 1, max 64
	 *
	 * @param x Long value
	 * @return Number of bits required for representation, in the range 1..64
	 *         inclusive
	 */
	public static int bitLength(long x) {
		long ux = (x >= 0) ? x : -x - 1;
		return 1 + (64 - Bits.leadingZeros(ux)); // sign bit plus number of used bits in positive representation
	}

	/**
	 * Converts an object to an int value, handling Strings and arbitrary numbers.
	 *
	 * @param v An object representing a valid int value
	 * @return The converted int value of the object
	 * @throws IllegalArgumentException If the argument cannot be converted to an
	 *                                  int
	 */
	public static int toInt(Object v) {
		if (v instanceof Integer) return (Integer) v;
		if (v instanceof String) {
			return Integer.parseInt((String) v);
		}
		if (v instanceof Number) {
			Number number = (Number) v;
			int value = (int) number.longValue();
			// following is safe, because double can represent any int
			if (value != number.doubleValue()) throw new IllegalArgumentException("Cannot coerce to int without loss:");
			return value;
		}
		throw new IllegalArgumentException("Can't convert to int: " + v);
	}

	/**
	 * Gets a resource as a String.
	 *
	 * @param path Path to resource, e.g "actors/token.con"
	 * @return String content of resource file
	 * @throws IOException If an IO error occurs
	 */
	public static String readResourceAsString(String path) throws IOException {
		ClassLoader classLoader = ClassLoader.getSystemClassLoader();
		try (InputStream inputStream = classLoader.getResourceAsStream(path)) {
			if (inputStream == null) throw new IOException("Resource not found: " + path);
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				return reader.lines().collect(Collectors.joining(System.lineSeparator()));
			}
		}
	}

	/**
	 * Extract a number of bits (up to 32) from a big-endian byte array, shifting
	 * right by the specified amount. Sign extends for bits beyond range of array.
	 * @param bs Source byte array
	 * @param numBits Number of bits to extract (0-32)
	 * @param shift Number of bits to shift
	 * @return Bits returned
	 */
	public static int extractBits(byte[] bs, int numBits, int shift) {
		if ((numBits < 0) || (numBits > 32)) throw new IllegalArgumentException("Invalid number of bits: " + numBits);

		if (numBits > 8) {
			return extractBits(bs, 8, shift) | (extractBits(bs, numBits - 8, shift + 8) << 8);
		}
		if (shift < 0) throw new IllegalArgumentException("Negative shift: " + shift);
		int bslen = bs.length;

		int bshift = shift >> 3; // shift in number of bytes

		if (bshift >= bslen) {
			// beyond end of array, so sign extend last byte
			return ((bs[0] >= 0) ? 0 : -1) & Bits.lowBitMask(numBits);
		}

		int lowShift = (shift - (bshift << 3));
		int ix = bslen - bshift - 1; // index of low byte

		int val = bs[ix]; // low byte from array into val, sign extend
		if (ix > 0) {
			val = val & 0xFF; // clear top 3 bytes of val
			val = val | (((int) bs[ix - 1]) << 8); // high byte, sign extended
		}
		val = val >> lowShift; // shift val to position low bits correctly
		return val & Bits.lowBitMask(numBits); // return just the requested bits
	}

	/**
	 * Sets a number of bits (up to 32) in a big-endian byte array, shifting by the
	 * specified amount Ignores bits set outside the byte array
	 * @param bs Target byte array
	 * @param numBits Number of bits to set (0-32)
	 * @param shift Number of bits to shift
	 * @param bits Bits to set
	 */
	public static void setBits(byte[] bs, int numBits, int shift, int bits) {
		if ((numBits < 0) || (numBits > 32)) {
			throw new IllegalArgumentException("Invalid number of bits: " + numBits);
		}
		if (numBits > 8) {
			setBits(bs, 8, shift, bits);
			setBits(bs, numBits - 8, shift + 8, bits >> 8);
			return;
		}
		if (shift < 0) throw new IllegalArgumentException("Negative shift: " + shift);
		int bslen = bs.length;
		int bshift = shift >> 3; // shift in number of bytes
		if (bshift >= bslen) return; // nothing to do, beyond end of byte array
		int ix = bslen - bshift - 1; // index of low byte

		// setup val with bits to set, others zero
		int lowShift = (shift - (bshift << 3));
		int lowBitMask = Bits.lowBitMask(numBits);
		int val = (bits & lowBitMask) << lowShift;

		// setup keep with bits to keep in low 16 bits
		int keepBitMask = ~(lowBitMask << lowShift); // bits to keep from original array
		int keep = (bs[ix] & 0xFF);
		if (ix > 0) {
			keep = keep | (((bs[ix - 1]) & 0xFF) << 8);
		}
		keep = keep & keepBitMask;

		val = val | keep;
		bs[ix] = (byte) (val & 0xFF);
		if (ix > 0) {
			bs[ix - 1] = (byte) ((val >> 8) & 0xFF);
		}
	}

	/**
	 * Reads data from the Byte Buffer buffer, up to the limit.
	 * @param bb ByteBuffer to read from
	 * @return Blob containing bytes read from buffer
	 */
	public static AArrayBlob readBufferData(ByteBuffer bb) {
		bb.position(0);
		int len = bb.remaining();
		byte[] bytes = new byte[len];
		bb.get(bytes);
		return Blob.wrap(bytes);
	}

	/**
	 * Prints an Object in readable String representation
	 * @param v Object to print
	 * @return String representation of value
	 */
	public static String print(Object v) {
		StringBuilder sb=new StringBuilder();
		print(sb,v);
		return sb.toString();
	}

	/**
	 * Prints an Object in readable String representation
	 * @param sb StringBuilder to append to
	 * @param v Object to print
	 */
	public static void print(StringBuilder sb,Object v) {
		if (v == null) {
			sb.append("nil");
		} else if (v instanceof AObject) {
			((AObject)v).print(sb);
		} else if (v instanceof Boolean || v instanceof Number){
			sb.append(v.toString());
		} else if (v instanceof String) {
			sb.append('"');
			sb.append((String)v);
			sb.append('"');
		} else if (v instanceof Instant) {
			sb.append(((Instant)v).toEpochMilli());
		} else if (v instanceof Character) {
			CVMChar.create((long)v).print(sb);
		} else {
			throw new TODOException("Can't print: " + Utils.getClass(v));
		}
	}

	/**
	 * Converts a Object to an InetSocketAddress
	 *
	 * @param o An Object to convert to a socket address. May be a String or existing InetSocketAddress
	 * @return A valid InetSocketAddress, or null if not in valid format
	 */
	public static InetSocketAddress toInetSocketAddress(Object o) {
		if (o instanceof InetSocketAddress) {
			return (InetSocketAddress) o;
		} else if (o instanceof String) {
			return toInetSocketAddress((String)o);
		} else if (o instanceof URL) {
			return toInetSocketAddress((URL)o);
		} else {
			return null;
		}
	}

	/**
	 * Converts a String to an InetSocketAddress
	 *
	 * @param s A string in the format of a valid URL or "myhost.com:17888"
	 * @return A valid InetSocketAddress, or null if not in valid format
	 */
	public static InetSocketAddress toInetSocketAddress(String s) {
		if (s==null) return null;
		try {
			// Try URL parsing first
			URL url=new URL(s);
			return toInetSocketAddress(url);
		} catch (MalformedURLException ex) {
			// Try to parse as host:port
			int colon = s.lastIndexOf(':');
			if (colon < 0) return null;
			try {
				String hostName = s.substring(0, colon); // up to last colon
				int port = Utils.toInt(s.substring(colon + 1)); // after last colon
				InetSocketAddress addr = new InetSocketAddress(hostName, port);
				return addr;
			} catch (Exception e) {
				return null;
			}
		}
	}

	/**
	 * Converts a URL to an InetSocketAddress. Will assume default port if not specified.
	 *
	 * @param url A valid URL
	 * @return A valid InetSocketAddress for the URL
	 */
	public static InetSocketAddress toInetSocketAddress(URL url) {
		String host=url.getHost();
		int port=url.getPort();
		if (port<0) port=Constants.DEFAULT_PEER_PORT;
		return new InetSocketAddress(host,port);
	}

	/**
	 * Filters the array, returning an array containing only the elements where the
	 * predicate returns true. May return the same array if all elements are
	 * included.
	 *
	 * @param arr Array to filter
	 * @param predicate Predicate to test array elements
	 * @return Filtered array.
	 */
	public static <T> T[] filterArray(T[] arr, Predicate<T> predicate) {
		if (arr.length <= 32) return filterSmallArray(arr, predicate);
		throw new TODOException("Filter large arrays");
	}

	/**
	 * Return a list of values, sorted according to the score computed using the
	 * provided function, in ascending order. Ignores elements where score is null
	 * (will not be included in the resulting list)
	 *
	 * @param scorer a Function mapping collection elements to Long values
	 * @param coll   Collection of values to compare
	 * @return The sorted collection values as an ArrayList, in ascending score
	 *         order.
	 */
	public static <T> ArrayList<T> sortListBy(Function<T, Long> scorer, Collection<T> coll) {
		// TODO can probably improve efficiency
		ArrayList<T> result = new ArrayList<>(coll.size());
		HashMap<T, Long> scores = new HashMap<>(coll.size());
		for (T c : coll) {
			Long score = scorer.apply(c);
			if (score == null) continue;
			scores.put(c, score);
			result.add(c);
		}
		result.sort(new Comparator<>() {
			@Override
			public int compare(T a, T b) {
				long comp = scores.get(a) - scores.get(b);
				return Long.signum(comp);
			}
		});
		return result;
	}

	/**
	 * Filters the array, returning an array containing only the elements where the
	 * predicate returns true. May return the same array if all elements are
	 * included.
	 *
	 * Array must have a maximum of 32 elements
	 *
	 * @param arr
	 * @param predicate
	 * @return
	 */
	private static <T> T[] filterSmallArray(T[] arr, Predicate<T> predicate) {
		int mask = 0;
		int n = arr.length;
		for (int i = 0; i < n; i++) {
			if (predicate.test(arr[i])) mask |= (1 << i);
		}
		return filterSmallArray(arr, mask);
	}

	@SuppressWarnings("unchecked")
	public static <T> T[] filterSmallArray(T[] arr, int mask) {
		int n = arr.length;
		if (n > 32) throw new IllegalArgumentException("Array too long to filter: " + n);
		int fullMask = (1 << n) - 1;
		if (mask == fullMask) return arr;
		int nn = Integer.bitCount(mask);
		T[] result = (T[]) Array.newInstance(arr.getClass().getComponentType(), nn);
		if (nn == 0) return result;
		int ix = 0;
		for (int i = 0; i < n; i++) {
			if ((mask & (1 << i)) != 0) {
				result[ix++] = arr[i];
			}
		}
		assert (ix == nn);
		return result;
	}

	/**
	 * Computes a bit mask of up to 16 bits by scanning a full array for which
	 * elements are included in the subset, comparing using object identity
	 *
	 * Subset must be an ordered subset of of the full array
	 *
	 * @param set Array of elements
	 * @param subset Array of element subset (must be identical)
	 * @return Bit mask as a short
	 */
	public static <T> short computeMask(T[] set, T[] subset) {
		int n = set.length;
		if (n > 16) throw new IllegalArgumentException("Max length of 16 for mask computation, got: " + n);
		int mask = 0;
		int ix = 0;
		int subsetLength = subset.length;
		for (int i = 0; i < n; i++) {
			if (ix == subsetLength) break; // no more items to find
			if (set[i] == subset[ix]) {
				mask |= (1 << i);
				ix++;
			}
		}
		if (ix != subsetLength) throw new IllegalArgumentException("Subset not all found");
		return (short) mask;
	}

	/**
	 * Hack to convert a checked exception into an unchecked exception.
	 *
	 * @param <T> Type of exception to return
	 * @param t   Any Throwable instance
	 * @return Throwable instance
	 * @throws T In all cases
	 */
	@SuppressWarnings("unchecked")
	public static <T extends Throwable> T sneakyThrow(Throwable t) throws T {
		throw (T) t;
	}

	@SuppressWarnings("unchecked")
	public static <T> T[] copyOfRangeExcludeNulls(T[] entries, int offset, int length) {
		int newLen = length;
		for (int i = 0; i < length; i++) {
			if (entries[offset + i] == null) newLen--;
		}
		if (newLen < length) {
			T[] result = (T[]) Array.newInstance(entries.getClass().getComponentType(), newLen);
			int ix = 0;
			for (int i = 0; i < length; i++) {
				T v = entries[offset + i];
				if (v != null) {
					result[ix++] = v;
				}
			}
			assert (ix == newLen);
			return result;
		} else {
			return Arrays.copyOfRange(entries, offset, offset + length);
		}
	}

	/**
	 * Reverse an array in place
	 * @param arr Array to reverse
	 */
	public static <T> void reverse(T[] arr) {
		reverse(arr, arr.length);
	}

	/**
	 * Reverse the first n elements of an array in place
	 * @param arr Array to reverse
	 * @param n Number of elements to reverse
	 */
	public static <T> void reverse(T[] arr, int n) {
		for (int i = 0; i < (n / 2); i++) {
			T val = arr[i];
			arr[i] = arr[n - i - 1];
			arr[n - i - 1] = val;
		}
	}

	/**
	 * Reads the full contents of an input stream into a new byte array.
	 *
	 * @param is An arbitrary InputStream
	 * @return A byte array containing the full contents of the given InputStream
	 * @throws IOException If IO error occurs
	 */
	public static byte[] readBytes(InputStream is) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int bytesRead;
		while ((bytesRead = is.read(buf)) >= 0) {
			bos.write(buf, 0, bytesRead);
		}
		return bos.toByteArray();
	}

	public static boolean isOdd(long x) {
		return (x & 1L) != 0;
	}

	/**
	 * Displays a String representing the given Object, printing null as "nil"
	 *
	 * SECURITY: should *not* be used in Actor code, use RT.str(...) instead.
	 *
	 * @param o Object to convert
	 * @return String representation of object
	 */
	public static String toString(Object o) {
		if (o == null) return "nil";
		return o.toString();
	}

	/**
	 * Removes all spaces from a String
	 * @param s String to strip
	 * @return String without spaces
	 */
	public static String stripWhiteSpace(String s) {
		return s.replaceAll("\\s+", "");
	}

	/**
	 * Gets the number of Refs directly contained in a Cell (will be zero if the
	 * Cell is not a Ref container)
	 *
	 * @param a Cell to check (may be null)
	 * @return Number of Refs in the object.
	 */
	public static int refCount(ACell a) {
		if (a==null) return 0;
		return a.getRefCount();
	}

	/**
	 * Counts the total number of Refs contained in a data object recursively. Will
	 * count duplicate children multiple times.
	 *
	 * @param a Object to count Refs in
	 * @return Total number of Refs found
	 */
	public static long totalRefCount(Object a) {
		if (!(a instanceof ACell)) return 0;

		ACell ra = (ACell) a;
		long[] count = new long[] { 0L };

		ACell ra2;
		ra2 = ra.updateRefs(r -> {
			count[0] += 1 + totalRefCount(r.getValue());

			return r;
		});
		assert (ra == ra2); // check we didn't change anything!
		return count[0];
	}

	public static <R extends ACell> Ref<R> getRef(ACell o, int i) {
		if (o ==null) throw new IllegalArgumentException("Bad ref index: " + i+ " called on null");
		return o.getRef(i);
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> T updateRefs(T o, IRefFunction func) {
		if (o==null) return o;
		return (T) o.updateRefs(func);
	}

	public static int bitCount(short mask) {
		return Integer.bitCount(mask & 0xFFFF);
	}

	/**
	 * Runs test repeatedly, until it returns true or the timeout has elapsed
	 *
	 * @param timeoutMillis Timeout interval
	 * @param test Test to run until true
	 * @return True if the operation timed out, false otherwise
	 */
	public static boolean timeout(int timeoutMillis, Supplier<Boolean> test) {
		long start = getTimeMillis();
		long end=start+timeoutMillis;
		long now = start;

		// loop until either test succeeds (return false) or the timeout happens (return true)
		while (true) {
			if (test.get()) return false;

			// test failed, so sleep
			try {
				// compute sleep time
				long nextInterval=(long) ((now - start) * 0.3 + 1);
				long sleepTime=Math.min(nextInterval, end-now);
				if (sleepTime<0L) return true;
				Thread.sleep(sleepTime);
			} catch (InterruptedException e) {
				// ignore? Probably shouldn't happen though
				// But should set interrupt flag as below;
				Thread.currentThread().interrupt();
			}
			now = getTimeMillis();
		}
	}

	private static long lastTimestamp = Instant.now().toEpochMilli();

	/**
	 * Gets the current system timestamp. Guaranteed monotonic within this JVM.
	 *
	 * Should be used for timestamps that need to be persisted or communicated
	 * Should not be used for timing - use Utils.getTimeMillis() instead
	 *
	 *
	 * @return Long representation of Timestamp
	 */
	public static long getCurrentTimestamp() {
		// Use Instant milliseconds
		long ts = Instant.now().toEpochMilli();
		if (ts > lastTimestamp) {
			lastTimestamp = ts;
			return ts;
		} else {
			return lastTimestamp;
		}
	}

	private static final long startupTimestamp=getCurrentTimestamp();
	private static final long startupNanos=System.nanoTime();

	/**
	 * Gets the a millisecond accurate time suitable for use in timing.
	 *
	 * Should not be used for timestamps
	 *
	 *
	 * @return long
	 */
	public static long getTimeMillis() {
		// Use nanoTime() for precision and guaranteed monotonicity
		long elapsedMillis = (System.nanoTime()-startupNanos)/1000000;
		return startupTimestamp+elapsedMillis;
	}

	/**
	 * Test if the first hex digits of two bytes match
	 *
	 * @param a Any byte value
	 * @param b Any byte value
	 * @return true if the first hex digit (high nibble) of the two bytes is equal,
	 *         false otherwise.
	 */
	public static boolean firstDigitMatch(byte a, byte b) {
		return (a & 0xF0) == (b & 0xF0);
	}

	/**
	 * Leftmost Binary Search.
	 *
	 * Generic method to search for an exact or approximate (leftmost) value.
	 *
	 * Examples:
	 * Given a vector [1, 2, 3] and target 2: returns 2.
	 * Given a vector [1, 2, 3] and target 5: returns 3.
	 * Given a vector [1, 2, 3] and target 0: returns null.
	 *
	 * @param L Items.
	 * @param value Function to get the value for comparison with target.
	 * @param comparator How to compare value with target.
	 * @param target Value being searched for.
	 * @param <T> Type of the elements in L.
	 * @param <U> Type of the target value.
	 * @return Target, or leftmost value, or null if there isn't a match.
	 */
	public static <T extends ACell, U> T binarySearchLeftmost(ASequence<T> L, Function<T, U> value, Comparator<U> comparator, U target) {
		long min = 0;
		long max = L.count();

		while (min < max) {
			long midpoint = (min + max) / 2;

			if (comparator.compare(value.apply(L.get(midpoint)), target) < 0)
				min = midpoint + 1;
			else
				max = midpoint;
		}

		// Match can be exact or approximate.
		// In case there isn't an exact match,
		// a leftmost search returns a rank (min)
		// which is used to get the leftmost value.
		if (min < L.count() && comparator.compare(value.apply(L.get(min)), target) == 0) {
			return L.get(min);
		} else {
			if (min - 1 == -1)
				return null;

			return L.get(min - 1);
		}

	}

	@SuppressWarnings("unchecked")
	public static <T> CompletableFuture<java.util.List<T>> completeAll(java.util.List<CompletableFuture<T>> futures) {
	    CompletableFuture<T>[] fs = futures.toArray(new CompletableFuture[futures.size()]);

	    return CompletableFuture.allOf(fs).thenApply(e -> futures.stream()
	    				.map(CompletableFuture::join)
	    				.collect(Collectors.toList())
	    				);
	}

	public static State stateAsOf(AVector<State> states, CVMLong timestamp) {
		return binarySearchLeftmost(states, State::getTimeStamp, Comparator.comparingLong(CVMLong::longValue), timestamp);
	}

	public static AVector<State> statesAsOfRange(AVector<State> states, CVMLong timestamp, long interval, int count) {
		AVector<State> v = Vectors.empty();

		for (int i = 0; i < count; i++) {
			v = v.conj(stateAsOf(states, timestamp));

			timestamp = CVMLong.create(timestamp.longValue() + interval);
		}

		return v;
	}

	public static boolean bool(Object a) {
		if (a==null) return false;
		if (a instanceof ACell) return (RT.bool((ACell)a));
		if (a instanceof Boolean) return ((Boolean)a);
		return true; // consider other values truthy
	}

	@SafeVarargs
	public static <T> List<T> listOf(T... values) {
		return Arrays.asList(values);
	}

	private static final ExecutorService executor=Executors.newCachedThreadPool();

	/**
	 * Executes functions on a thread pool for each element of a collection
	 * @param <R> Result type of function
	 * @param <T> Argument type
	 * @param func Function to run
	 * @param items Collection of items to run futures on
	 * @return List of futures for each item
	 */
	public static <R,T> ArrayList<Future<R>> futureMap(Function<T,R> func, Collection<T> items) {
		ArrayList<Future<R>> futures=new ArrayList<>(items.size());
		for (T item: items) {
			futures.add(executor.submit(()->func.apply(item)));
		}
		return futures;
	}

}
