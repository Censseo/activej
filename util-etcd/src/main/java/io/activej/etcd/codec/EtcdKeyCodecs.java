package io.activej.etcd.codec;

import io.activej.common.exception.MalformedDataException;
import io.activej.common.function.DecoderFunction;
import io.etcd.jetcd.ByteSequence;

import java.nio.ByteBuffer;
import java.util.function.Function;

import static io.activej.etcd.EtcdUtils.byteSequenceFrom;
import static java.nio.ByteOrder.BIG_ENDIAN;

public class EtcdKeyCodecs {

	public static EtcdKeyCodec<String> ofString() {
		return new EtcdKeyCodec<>() {
			@Override
			public ByteSequence encodeKey(String key) {
				return byteSequenceFrom(key);
			}

			@Override
			public String decodeKey(ByteSequence byteSequence) {
				return byteSequence.toString();
			}
		};
	}

	public static EtcdKeyCodec<Integer> ofInteger() {
		return new EtcdKeyCodec<>() {
			@Override
			public ByteSequence encodeKey(Integer key) {
				ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES).order(BIG_ENDIAN);
				buffer.putInt(key);
				return ByteSequence.from(buffer.array());
			}

			@Override
			public Integer decodeKey(ByteSequence byteSequence) throws MalformedDataException {
				if (byteSequence.size() != Integer.BYTES) throw new MalformedDataException();
				return ByteBuffer.wrap(byteSequence.getBytes()).order(BIG_ENDIAN).getInt();
			}
		};
	}

	public static EtcdKeyCodec<Long> ofLong() {
		return new EtcdKeyCodec<>() {
			@Override
			public ByteSequence encodeKey(Long key) {
				ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(BIG_ENDIAN);
				buffer.putLong(key);
				return ByteSequence.from(buffer.array());
			}

			@Override
			public Long decodeKey(ByteSequence byteSequence) throws MalformedDataException {
				if (byteSequence.size() != Long.BYTES) throw new MalformedDataException();
				return ByteBuffer.wrap(byteSequence.getBytes()).order(BIG_ENDIAN).getLong();
			}
		};
	}

	public static <T, R> EtcdKeyCodec<R> transform(EtcdKeyCodec<T> codec, Function<R, T> encodeFn, DecoderFunction<T, R> decodeFn) {
		return new EtcdKeyCodec<>() {
			@Override
			public R decodeKey(ByteSequence byteSequence) throws MalformedDataException {
				return decodeFn.decode(codec.decodeKey(byteSequence));
			}

			@Override
			public ByteSequence encodeKey(R item) {
				return codec.encodeKey(encodeFn.apply(item));
			}
		};
	}

}
