#!/usr/bin/env bash
# Generates the DEVELOPMENT-ONLY C2PA signing identity used by AndroidC2paService.
#
# This produces a two-certificate chain: a local root CA, and an end-entity signing
# certificate issued by it. That structure is not optional -- c2pa-rs rejects a bare
# self-signed certificate with "Signature: the certificate is invalid", because the C2PA
# certificate profile requires a true end-entity certificate (CA:FALSE, issued by
# someone else) rather than one that is simultaneously its own issuer.
#
# The chain still chains to nothing anyone trusts, so validators will report Content
# Credentials signed with it as *Valid* but not *Trusted*. That is the correct posture for
# a development build: the tamper-evidence is real, but nothing vouches for who signed.
#
# The private key is git-ignored. Never place a production signing key at this path --
# production signing needs a CA-issued certificate and a key held in hardware, reached
# through Signer.withCallback so the key never enters the process.
#
# Usage:  bash tools/generate-dev-cert.sh
set -euo pipefail

# Loaded as classloader resources by AndroidC2paService, not Android res/raw entries, so
# the domain module stays free of generated R references.
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/shared/src/androidMain/resources/c2pa"
mkdir -p "$OUT_DIR"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/ca.cnf" <<'EOF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = OpenVeil Development Root CA
O  = OpenVeil
OU = Development -- NOT FOR PRODUCTION
C  = US
[v3_ca]
basicConstraints     = critical, CA:TRUE, pathlen:0
keyUsage             = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF

cat > "$WORK/leaf.cnf" <<'EOF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = OpenVeil Development Signer
O  = OpenVeil
OU = Development -- NOT FOR PRODUCTION
C  = US
[v3_ee]
# C2PA's certificate profile. Every line here is load-bearing:
#   CA:FALSE          - this must be an end-entity certificate
#   digitalSignature  - required; nonRepudiation matches the CAI reference cert
#   emailProtection   - one of the EKUs C2PA permits for a signing certificate
basicConstraints       = critical, CA:FALSE
keyUsage               = critical, digitalSignature, nonRepudiation
extendedKeyUsage       = critical, emailProtection
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid:always
EOF

# --- Root CA (ES256 => NIST P-256) ---
openssl ecparam -name prime256v1 -genkey -noout -out "$WORK/ca.key"
openssl req -new -x509 -sha256 -days 3650 \
    -key "$WORK/ca.key" -config "$WORK/ca.cnf" -extensions v3_ca \
    -out "$WORK/ca.crt"

# --- End-entity signing certificate, issued by the root ---
openssl ecparam -name prime256v1 -genkey -noout -out "$WORK/leaf.sec1.key"
openssl req -new -sha256 -key "$WORK/leaf.sec1.key" -config "$WORK/leaf.cnf" -out "$WORK/leaf.csr"
openssl x509 -req -sha256 -days 3650 \
    -in "$WORK/leaf.csr" \
    -CA "$WORK/ca.crt" -CAkey "$WORK/ca.key" -CAcreateserial \
    -extfile "$WORK/leaf.cnf" -extensions v3_ee \
    -out "$WORK/leaf.crt"

# c2pa-rs expects PKCS#8, not the SEC1 "EC PRIVATE KEY" form.
openssl pkcs8 -topk8 -nocrypt -in "$WORK/leaf.sec1.key" -out "$OUT_DIR/dev_signing_key.pem"

# Chain order matters: end-entity certificate first, then its issuer.
cat "$WORK/leaf.crt" "$WORK/ca.crt" > "$OUT_DIR/dev_signing_cert.pem"

echo "Wrote:"
echo "  $OUT_DIR/dev_signing_cert.pem  ($(grep -c 'BEGIN CERTIFICATE' "$OUT_DIR/dev_signing_cert.pem") certificates)"
echo "  $OUT_DIR/dev_signing_key.pem   (git-ignored)"
echo
openssl x509 -in "$WORK/leaf.crt" -noout -subject -issuer -dates \
    -ext basicConstraints,keyUsage,extendedKeyUsage
