#!/usr/bin/env node

/**
 * Sign release/version.json (or --path) with the pinned Streamify Release Ed25519 private key.
 * Mirrors scripts/sign_manifest.js canonicalization ("signatureHex": "").
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const targetArgIdx = process.argv.indexOf('--path');
const targetPath = targetArgIdx !== -1 && process.argv[targetArgIdx + 1]
  ? path.resolve(process.argv[targetArgIdx + 1])
  : path.resolve(__dirname, '../release/version.json');

const rawPrivHex = process.env.STREAMIFY_ANNOUNCEMENT_KEY;

if (!rawPrivHex) {
  console.error('STREAMIFY_ANNOUNCEMENT_KEY environment variable is required to sign version.json');
  process.exit(1);
}

const privBytes = Buffer.from(rawPrivHex, 'hex');
const pkcs8Prefix = Buffer.from('302e020100300506032b657004220420', 'hex');
const privateKey = crypto.createPrivateKey({
  key: Buffer.concat([pkcs8Prefix, privBytes]),
  format: 'der',
  type: 'pkcs8',
});
const publicKey = crypto.createPublicKey(privateKey);
const spkiDer = publicKey.export({ type: 'spki', format: 'der' });
const pubHex = spkiDer.subarray(spkiDer.length - 32).toString('hex');

const content = fs.readFileSync(targetPath, 'utf8');
if (!/"signatureHex":\s*"[^"]*"/.test(content)) {
  console.error('Missing signatureHex field in ' + targetPath);
  process.exit(1);
}

const canonical = content.replace(/"signatureHex":\s*"[^"]*"/, '"signatureHex": ""');
const signature = crypto.sign(null, Buffer.from(canonical, 'utf8'), privateKey);
const signatureHex = signature.toString('hex');

if (!crypto.verify(null, Buffer.from(canonical, 'utf8'), publicKey, signature)) {
  console.error('Self-verification failed');
  process.exit(1);
}

const finalContent = content.replace(/"signatureHex":\s*"[^"]*"/, '"signatureHex": "' + signatureHex + '"');
fs.writeFileSync(targetPath, finalContent, 'utf8');

console.log('Successfully signed ' + targetPath);
console.log('Public Key (hex):', pubHex);
console.log('Signature (hex):', signatureHex);
