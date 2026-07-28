# TLS test certificate fixtures

Development-only identities for `io.activej.quic.tls` tests. Every chain is self-signed,
100-year validity, with SANs covering `localhost` and `example.test`. Never use outside tests.

## Regeneration commands (OpenSSL 3.x + keytool)

```bash
cd core-quic/src/test/resources/io/activej/quic/tls

# RSA chain + unencrypted PKCS#8 key
openssl req -x509 -newkey rsa:2048 -keyout rsa-key.pem -out rsa-cert.pem \
  -days 36500 -nodes -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,DNS:example.test"

# ECDSA P-256 chain + unencrypted PKCS#8 key
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 \
  -keyout ecdsa-key.pem -out ecdsa-cert.pem \
  -days 36500 -nodes -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,DNS:example.test"

# Ed25519 chain + unencrypted PKCS#8 key
openssl req -x509 -newkey ed25519 -keyout ed25519-key.pem -out ed25519-cert.pem \
  -days 36500 -nodes -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,DNS:example.test"

# Negative fixture: a valid RSA PKCS#8 key that matches none of the certificates
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out mismatched-key.pem

# Negative fixture: encrypted PKCS#8 (must fail at load time), password "activej-test"
openssl pkcs8 -topk8 -in ecdsa-key.pem -out encrypted-key.pem -passout pass:activej-test

# PKCS12 keystore from the RSA pair, alias "server", password "activej-test"
openssl pkcs12 -export -in rsa-cert.pem -inkey rsa-key.pem \
  -out rsa-keystore.p12 -name server -passout pass:activej-test

# Equivalent via keytool (import the openssl-produced pair):
# keytool -importkeystore -srckeystore rsa-keystore.p12 -srcstoretype PKCS12 \
#   -srcstorepass activej-test -destkeystore rsa-keystore.jks \
#   -deststorepass activej-test
```

## Files

| File | Contents |
|------|----------|
| `rsa-cert.pem` / `rsa-key.pem` | RSA-2048 self-signed chain + unencrypted PKCS#8 key |
| `ecdsa-cert.pem` / `ecdsa-key.pem` | ECDSA P-256 self-signed chain + unencrypted PKCS#8 key |
| `ed25519-cert.pem` / `ed25519-key.pem` | Ed25519 self-signed chain + unencrypted PKCS#8 key |
| `encrypted-key.pem` | Encrypted PKCS#8 key (negative fixture; password `activej-test`) |
| `mismatched-key.pem` | RSA PKCS#8 key matching no fixture certificate (negative fixture) |
| `rsa-keystore.p12` | PKCS12 keystore of the RSA pair, alias `server`, password `activej-test` |
