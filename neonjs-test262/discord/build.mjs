// Bundles a script that uses discord.js (or another library written for Node) into one file that NeonJS runs as a
// script, with neonjs-node and neonjs-net installed: node build.mjs ENTRY OUTFILE
import { build } from 'esbuild';
import { builtinModules } from 'node:module';

const [entry, outfile] = process.argv.slice(2);
if (!entry || !outfile) {
  console.error('usage: node build.mjs ENTRY OUTFILE');
  process.exit(2);
}
await build({
  entryPoints: [entry],
  outfile,
  bundle: true,
  // neither 'node' nor 'browser': packages resolve to their "default" exports, which are their builds over fetch and
  // WebSocket where they have one (@discordjs/rest's web.js rather than its undici one)
  platform: 'neutral',
  mainFields: ['main', 'module'],
  // a script: NeonJS scripts have require, but no module or exports
  format: 'iife',
  // Node's modules come from neonjs-node, undici and ws from neonjs-net; zlib-sync, erlpack, bufferutil and
  // utf-8-validate are native add-ons that the libraries do without
  external: [
    ...builtinModules,
    ...builtinModules.map((m) => `node:${m}`),
    'undici', 'ws', 'zlib-sync', 'erlpack', 'bufferutil', 'utf-8-validate',
  ],
  logLevel: 'warning',
});
