package io.activej.etcd.codec;

import io.activej.common.exception.MalformedDataException;
import io.etcd.jetcd.ByteSequence;

public interface EtcdPrefixCodec<K> extends EtcdPrefixEncoder<K>, EtcdPrefixDecoder<K> {

	static <K> EtcdPrefixCodec<K> of(EtcdPrefixEncoder<K> encoder, EtcdPrefixDecoder<K> decoder) {
		return new EtcdPrefixCodec<>() {
			@Override
			public ByteSequence encodePrefix(Prefix<K> prefix) {
				return encoder.encodePrefix(prefix);
			}

			@Override
			public Prefix<K> decodePrefix(ByteSequence byteSequence) throws MalformedDataException {
				return decoder.decodePrefix(byteSequence);
			}
		};
	}

}
