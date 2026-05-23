#!/usr/bin/env node
/**
 * Generate placeholder PNG icons (shield emoji on gradient background)
 * for the extension. No external image libraries — emits minimal valid PNGs.
 *
 * Each icon is a solid purple gradient square with a white shield outline.
 * If you have ImageMagick/Inkscape, replace these with proper artwork.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SIZES = [16, 32, 48, 128];
const OUT_DIR = path.join(__dirname, '../icons');

// CRC table for PNG chunks
const crcTable = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcInput = Buffer.concat([typeBuf, data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(crcInput), 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

function makeIcon(size) {
  // Create RGBA pixel buffer with gradient + shield
  const pixels = Buffer.alloc(size * size * 4);

  const centerX = size / 2, centerY = size / 2;
  const shieldRadius = size * 0.35;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const idx = (y * size + x) * 4;

      // Background: purple gradient (#667eea → #764ba2)
      const tGrad = (x + y) / (2 * size);
      const r = Math.round(0x66 * (1 - tGrad) + 0x76 * tGrad);
      const g = Math.round(0x7e * (1 - tGrad) + 0x4b * tGrad);
      const b = Math.round(0xea * (1 - tGrad) + 0xa2 * tGrad);

      // Shield shape (rounded diamond/heart-ish)
      const dx = x - centerX;
      const dy = y - centerY;
      const dist = Math.sqrt(dx * dx + dy * dy);
      const shield = dist < shieldRadius;

      if (shield) {
        // White shield
        pixels[idx] = 255;
        pixels[idx + 1] = 255;
        pixels[idx + 2] = 255;
      } else {
        pixels[idx] = r;
        pixels[idx + 1] = g;
        pixels[idx + 2] = b;
      }
      pixels[idx + 3] = 255;
    }
  }

  // Build PNG: filter byte (0) before each row
  const rows = [];
  for (let y = 0; y < size; y++) {
    rows.push(Buffer.from([0]));
    rows.push(pixels.slice(y * size * 4, (y + 1) * size * 4));
  }
  const raw = Buffer.concat(rows);
  const idatData = zlib.deflateSync(raw);

  // Build chunks
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

  const ihd = Buffer.alloc(13);
  ihd.writeUInt32BE(size, 0);   // width
  ihd.writeUInt32BE(size, 4);   // height
  ihd[8]  = 8;                  // bit depth
  ihd[9]  = 6;                  // color type RGBA
  ihd[10] = 0;                  // compression
  ihd[11] = 0;                  // filter
  ihd[12] = 0;                  // interlace

  return Buffer.concat([
    sig,
    chunk('IHDR', ihd),
    chunk('IDAT', idatData),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true });

for (const size of SIZES) {
  const png = makeIcon(size);
  const file = path.join(OUT_DIR, `icon${size}.png`);
  fs.writeFileSync(file, png);
  console.log(`✓ Generated ${file} (${png.length} bytes)`);
}
console.log('\nDone. Replace icons/*.png with custom artwork if desired.');
