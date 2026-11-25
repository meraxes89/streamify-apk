#!/usr/bin/env node

/**
 * Sign docs/announcements/manifest.json with the pinned Streamify Release Ed25519 private key.
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const MANIFEST_PATH = path.resolve(__dirname, '../docs/announcements/manifest.json');
const rawPrivHex = process.env.STREAMIFY_ANNOUNCEMENT_KEY;

if (!rawPrivHex) {
  console.error('STREAMIFY_ANNOUNCEMENT_KEY environment variable is required to sign manifest.json');
  process.exit(1);
}

const privBytes = Buffer.from(rawPrivHex, 'hex');
const pkcs8Prefix = Buffer.from('302e020100300506032b657004220420', 'hex');
const privateKey = crypto.createPrivateKey({
  key: Buffer.concat([pkcs8Prefix, privBytes]),
  format: 'der',
  type: 'pkcs8'
});
const publicKey = crypto.createPublicKey(privateKey);
const spkiDer = publicKey.export({ type: 'spki', format: 'der' });
const pubHex = spkiDer.subarray(spkiDer.length - 32).toString('hex');

const content = fs.readFileSync(MANIFEST_PATH, 'utf8');

// Canonicalize: replace signatureHex with empty string while preserving numbers and formatting
const canonical = content.replace(/"signatureHex":\s*"[^"]*"/, '"signatureHex": ""');

const signature = crypto.sign(null, Buffer.from(canonical, 'utf8'), privateKey);
const signatureHex = signature.toString('hex');

// Verify
const verified = crypto.verify(null, Buffer.from(canonical, 'utf8'), publicKey, signature);
if (!verified) {
  console.error('Self-verification failed');
  process.exit(1);
}

// Write back manifest with signature
const finalContent = content.replace(/"signatureHex":\s*"[^"]*"/, '"signatureHex": "' + signatureHex + '"');
fs.writeFileSync(MANIFEST_PATH, finalContent, 'utf8');

console.log('Successfully signed manifest.json');
console.log('Public Key (hex):', pubHex);
console.log('Signature (hex):', signatureHex);
