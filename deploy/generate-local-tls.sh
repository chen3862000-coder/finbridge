#!/bin/sh
set -eu

TLS_DIR="$(dirname "$0")/tls"
mkdir -p "$TLS_DIR"
openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 \
  -keyout "$TLS_DIR/localhost.key" -out "$TLS_DIR/localhost.crt" \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
keytool -importcert -noprompt -alias finbridge-localhost \
  -file "$TLS_DIR/localhost.crt" -keystore "$TLS_DIR/truststore.p12" \
  -storetype PKCS12 -storepass changeit
chmod 600 "$TLS_DIR/localhost.key" "$TLS_DIR/truststore.p12"
