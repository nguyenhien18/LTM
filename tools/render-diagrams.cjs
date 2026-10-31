// Documentation only. Requires Node.js and sharp; does not change Java sources.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
(async () => {
  for (const name of ['architecture', 'protocol']) {
    const source = path.join(root, 'statics', `${name}.svg`);
    const destination = path.join(root, 'statics', `${name}.png`);
    await sharp(fs.readFileSync(source), { density: 120 }).png().toFile(destination);
    const meta = await sharp(destination).metadata();
    process.stdout.write(`${name}.png: ${meta.width} x ${meta.height}\n`);
  }
})().catch(error => { process.stderr.write(`${error.stack}\n`); process.exitCode = 1; });
