import { crc32, deflateSync } from 'node:zlib'

// Small real files for the end-to-end tests, made in memory (no files to keep in the repository).

function pngChunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const checksum = Buffer.alloc(4)
  checksum.writeUInt32BE(crc32(body))
  return Buffer.concat([length, body, checksum])
}

// A valid PNG of the given size filled with one colour (the browser can really decode it, naturalWidth > 0).
export function makePng(width = 64, height = 48, rgb: [number, number, number] = [30, 144, 255]): Buffer {
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  header[8] = 8 // bit depth
  header[9] = 2 // colour type: RGB
  // every line starts with a filter byte (0 = none), then the pixels
  const line = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: width }, () => rgb).flat())])
  const raw = Buffer.concat(Array.from({ length: height }, () => line))
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', header),
    pngChunk('IDAT', deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ])
}

// A tiny but valid PDF with one empty page.
export function makePdf(): Buffer {
  return Buffer.from(
    [
      '%PDF-1.4',
      '1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj',
      '2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj',
      '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj',
      'trailer<</Root 1 0 R/Size 4>>',
      '%%EOF',
      '',
    ].join('\n'),
    'ascii',
  )
}

// The start of a Windows program: what a virus pretending to be a picture looks like.
export function makeFakePicture(): Buffer {
  return Buffer.concat([Buffer.from('MZ'), Buffer.alloc(2048, 0x90)])
}
