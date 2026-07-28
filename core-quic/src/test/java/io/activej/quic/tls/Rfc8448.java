/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.quic.tls;

import java.util.HexFormat;

/**
 * RFC 8448 §3 "Simple 1-RTT Handshake" trace constants: the exact handshake message bytes,
 * ephemeral keys, the ECDHE shared secret and every published key-schedule intermediate.
 * <p>
 * The trace negotiates {@code TLS_AES_128_GCM_SHA256} (SHA-256 transcript). The published
 * {@link #HANDSHAKE_TRANSCRIPT_HASH} pins the ClientHello/ServerHello constants byte-exact.
 */
final class Rfc8448 {
	private Rfc8448() {
	}

	// ---- handshake messages (message bytes including the 1-byte type + 3-byte length header) ----

	static final byte[] CLIENT_HELLO = hex(
		"010000c00303cb34ecb1e78163ba1c38c6dacb196a6dffa21a8d9912ec18a2ef6283024dece700000613011303130201" +
		"0000910000000b0009000006736572766572ff01000100000a00140012001d0017001800190100010101020103010400" +
		"230000003300260024001d002099381de560e4bd43d23d8e435a7dbafeb3c06e51c13cae4d5413691e529aaf2c002b00" +
		"03020304000d0020001e040305030603020308040805080604010501060102010402050206020202002d00020101001c" +
		"00024001");

	static final byte[] SERVER_HELLO = hex(
		"020000560303a6af06a4121860dc5e6e60249cd34c95930c8ac5cb1434dac155772ed3e2692800130100002e0033" +
		"0024001d0020c9828876112095fe66762bdbf7c672e156d6cc253b833df1dd69b1b04e751f0f002b00020304");

	static final byte[] ENCRYPTED_EXTENSIONS = hex(
		"080000240022000a00140012001d00170018001901000101010201030104001c0002400100000000");

	static final byte[] CERTIFICATE = hex(
		"0b0001b9000001b50001b0308201ac30820115a003020102020102300d06092a864886f70d01010b0500300e310c" +
		"300a06035504031303727361301e170d3136303733303031323335395a170d3236303733303031323335395a300e" +
		"310c300a0603550403130372736130819f300d06092a864886f70d010101050003818d0030818902818100b4bb49" +
		"8f8279303d980836399b36c6988c0c68de55e1bdb826d3901a2461eafd2de49a91d015abbc9a95137ace6c1af19e" +
		"aa6af98c7ced43120998e187a80ee0ccb0524b1b018c3e0b63264d449a6d38e22a5fda430846748030530ef0461c" +
		"8ca9d9efbfae8ea6d1d03e2bd193eff0ab9a8002c47428a6d35a8d88d79f7f1e3f0203010001a31a3018300906" +
		"03551d1304023000300b0603551d0f0404030205a0300d06092a864886f70d01010b05000381810085aad2a0e5b9" +
		"276b908c65f73a7267170618a54c5f8a7b337d2df7a594365417f2eae8f8a58c8f8172f9319cf36b7fd6c55b80f2" +
		"1a03015156726096fd335e5e67f2dbf102702e608ccae6bec1fc63a42a99be5c3eb7107c3c54e9b9eb2bd5203b1c" +
		"3b84e0a8b2f759409ba3eac9d91d402dcc0cc8f8961229ac9187b42b4de10000");

	static final byte[] CERTIFICATE_VERIFY = hex(
		"0f000084080400805a747c5d88fa9bd2e55ab085a61015b7211f824cd484145ab3ff52f1fda8477b0b7abc90db78" +
		"e2d33a5c141a078653fa6bef780c5ea248eeaaa785c4f394cab6d30bbe8d4859ee511f602957b15411ac02767145" +
		"9e46445c9ea58c181e818e95b8c3fb0bf3278409d3be152a3da5043e063dda65cdf5aea20d53dfacd42f74f3");

	static final byte[] SERVER_FINISHED = hex(
		"140000209b9b141d906337fbd2cbdce71df4deda4ab42c309572cb7fffee5454b78f0718");

	static final byte[] CLIENT_FINISHED = hex(
		"14000020a8ec436d677634ae525ac1fcebe11a039ec17694fac6e98527b642f2edd5ce61");

	/** The DER bytes of the trace's 432-byte self-signed RSA certificate (the single entry of {@link #CERTIFICATE}). */
	static final byte[] SERVER_CERTIFICATE_DER = hex(
		"308201ac30820115a003020102020102300d06092a864886f70d01010b0500300e310c300a06035504031303727361" +
		"301e170d3136303733303031323335395a170d3236303733303031323335395a300e310c300a06035504031303727361" +
		"30819f300d06092a864886f70d010101050003818d0030818902818100b4bb498f8279303d980836399b36c6988c0c68" +
		"de55e1bdb826d3901a2461eafd2de49a91d015abbc9a95137ace6c1af19eaa6af98c7ced43120998e187a80ee0ccb052" +
		"4b1b018c3e0b63264d449a6d38e22a5fda430846748030530ef0461c8ca9d9efbfae8ea6d1d03e2bd193eff0ab9a8002" +
		"c47428a6d35a8d88d79f7f1e3f0203010001a31a301830090603551d1304023000300b0603551d0f0404030205a0300d" +
		"06092a864886f70d01010b05000381810085aad2a0e5b9276b908c65f73a7267170618a54c5f8a7b337d2df7a594365417" +
		"f2eae8f8a58c8f8172f9319cf36b7fd6c55b80f21a03015156726096fd335e5e67f2dbf102702e608ccae6bec1fc63a42a" +
		"99be5c3eb7107c3c54e9b9eb2bd5203b1c3b84e0a8b2f759409ba3eac9d91d402dcc0cc8f8961229ac9187b42b4de1");

	/** The 128-byte {@code rsa_pss_rsae_sha256} signature carried by {@link #CERTIFICATE_VERIFY}. */
	static final byte[] CERTIFICATE_VERIFY_SIGNATURE = hex(
		"5a747c5d88fa9bd2e55ab085a61015b7211f824cd484145ab3ff52f1fda8477b0b7abc90db78e2d33a5c141a078653" +
		"fa6bef780c5ea248eeaaa785c4f394cab6d30bbe8d4859ee511f602957b15411ac027671459e46445c9ea58c181e818e" +
		"95b8c3fb0bf3278409d3be152a3da5043e063dda65cdf5aea20d53dfacd42f74f3");

	static final byte[] NEW_SESSION_TICKET = hex(
		"040000c90000001efad6aac502000000b22c035d829359ee5ff7af4ec900000000262a6494dc486d2c8a34cb33fa" +
		"90bf1b0070ad3c498883c9367c09a2be785abc55cd226097a3a982117283f82a03a143efd3ff5dd36d64e861be7f" +
		"d61d2827db279cce145077d454a3664d4e6da4d29ee03725a6a4dafcd0fc67d2aea70529513e3da2677fa5906c5b3" +
		"f7d8f92f228bda40dda721470f9fbf297b5aea617646fac5c03272e970727c621a79141ef5f7de6505e5bfbc388e" +
		"93343694093934ae4d3570008002a000400000400");

	// ---- client randomness and ephemeral x25519 keys ----

	static final byte[] CLIENT_RANDOM = hex(
		"cb34ecb1e78163ba1c38c6dacb196a6dffa21a8d9912ec18a2ef6283024dece7");

	static final byte[] SERVER_RANDOM = hex(
		"a6af06a4121860dc5e6e60249cd34c95930c8ac5cb1434dac155772ed3e26928");

	static final byte[] CLIENT_EPHEMERAL_PRIVATE = hex(
		"49af42ba7f7994852d713ef2784bcbcaa7911de26adc5642cb634540e7ea5005");

	static final byte[] CLIENT_EPHEMERAL_PUBLIC = hex(
		"99381de560e4bd43d23d8e435a7dbafeb3c06e51c13cae4d5413691e529aaf2c");

	static final byte[] SERVER_EPHEMERAL_PRIVATE = hex(
		"b1580eeadf6dd589b8ef4f2d5652578cc810e9980191ec8d058308cea216a21e");

	static final byte[] SERVER_EPHEMERAL_PUBLIC = hex(
		"c9828876112095fe66762bdbf7c672e156d6cc253b833df1dd69b1b04e751f0f");

	static final byte[] ECDHE_SHARED_SECRET = hex(
		"8bd4054fb55b9d63fdfbacf9f04b9f0d35e6d63f537563efd46272900f89492d");

	// ---- key-schedule intermediates (RFC 8446 §7.1 labels as printed by the trace) ----

	static final byte[] EARLY_SECRET = hex(
		"33ad0a1c607ec03b09e6cd9893680ce210adf300aa1f2660e1b22e10f170f92a");

	static final byte[] DERIVED_SECRET = hex(
		"6f2615a108c702c5678f54fc9dbab69716c076189c48250cebeac3576c3611ba");

	static final byte[] HANDSHAKE_SECRET = hex(
		"1dc826e93606aa6fdc0aadc12f741b01046aa6b99f691ed221a9f0ca043fbeac");

	/** Transcript hash over ClientHello..ServerHello (SHA-256). */
	static final byte[] HANDSHAKE_TRANSCRIPT_HASH = hex(
		"860c06edc07858ee8e78f0e7428c58edd6b43f2ca3e6e95f02ed063cf0e1cad8");

	static final byte[] CLIENT_HANDSHAKE_TRAFFIC_SECRET = hex(
		"b3eddb126e067f35a780b3abf45e2d8f3b1a950738f52e9600746a0e27a55a21");

	static final byte[] SERVER_HANDSHAKE_TRAFFIC_SECRET = hex(
		"b67b7d690cc16c4e75e54213cb2d37b4e9c912bcded9105d42befd59d391ad38");

	static final byte[] MASTER_SECRET = hex(
		"18df06843d13a08bf2a449844c5f8a478001bc4d4c627984d5a41da8d0402919");

	/** Transcript hash over ClientHello..server Finished (SHA-256). */
	static final byte[] SERVER_FINISHED_TRANSCRIPT_HASH = hex(
		"9608102a0f1ccc6db6250b7b7e417b1a000eaada3daae4777a7686c9ff83df13");

	static final byte[] CLIENT_APPLICATION_TRAFFIC_SECRET_0 = hex(
		"9e40646ce79a7f9dc05af8889bce6552875afa0b06df0087f792ebb7c17504a5");

	static final byte[] SERVER_APPLICATION_TRAFFIC_SECRET_0 = hex(
		"a11af9f05531f856ad47116b45a950328204b4f44bfb6b3a4b4f1f3fcb631643");

	static final byte[] EXPORTER_MASTER_SECRET = hex(
		"fe22f881176eda18eb8f44529e6792c50c9a3f89452f68d8ae311b4309d3cf50");

	static final byte[] SERVER_FINISHED_KEY = hex(
		"008d3b66f816ea559f96b537e885c31fc068bf492c652f01f288a1d8cdc19fc8");

	static final byte[] SERVER_VERIFY_DATA = hex(
		"9b9b141d906337fbd2cbdce71df4deda4ab42c309572cb7fffee5454b78f0718");

	static final byte[] CLIENT_FINISHED_KEY = hex(
		"b80ad01015fb2f0bd65ff7d4da5d6bf83f84821d1f87fdc7d3c75b5a7b42d9c4");

	static final byte[] CLIENT_VERIFY_DATA = hex(
		"a8ec436d677634ae525ac1fcebe11a039ec17694fac6e98527b642f2edd5ce61");

	/** Transcript hash over ClientHello..client Finished (SHA-256). */
	static final byte[] CLIENT_FINISHED_TRANSCRIPT_HASH = hex(
		"209145a96ee8e2a122ff810047cc952684658d6049e86429426db87c54ad143d");

	static final byte[] RESUMPTION_MASTER_SECRET = hex(
		"7df235f2031d2a051287d02b0241b0bfdaf86cc856231f2d5aba46c434ec196c");

	// ---- per-direction traffic key/IV material (RFC 8446 §7.3 "tls13 key"/"tls13 iv") ----

	static final byte[] SERVER_HANDSHAKE_KEY = hex("3fce516009c21727d0f2e4e86ee403bc");
	static final byte[] SERVER_HANDSHAKE_IV = hex("5d313eb2671276ee13000b30");
	static final byte[] CLIENT_HANDSHAKE_KEY = hex("dbfaa693d1762c5b666af5d950258d01");
	static final byte[] CLIENT_HANDSHAKE_IV = hex("5bd3c71b836e0b76bb73265f");
	static final byte[] SERVER_APPLICATION_KEY = hex("9f02283b6c9c07efc26bb9f2ac92e356");
	static final byte[] SERVER_APPLICATION_IV = hex("cf782b88dd83549aadf1e984");
	static final byte[] CLIENT_APPLICATION_KEY = hex("17422dda596ed5d9acd890e3c63f5051");
	static final byte[] CLIENT_APPLICATION_IV = hex("5b78923dee08579033e523d9");

	private static byte[] hex(String hex) {
		return HexFormat.of().parseHex(hex);
	}
}
