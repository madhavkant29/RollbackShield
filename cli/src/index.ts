#!/usr/bin/env node
import { run } from './commands.js';

const exitCode = await run(process.argv.slice(2), {
  out: (line) => process.stdout.write(`${line}\n`),
  err: (line) => process.stderr.write(`${line}\n`),
});

// Set the exit code and let Node exit naturally: calling process.exit()
// while HTTP keep-alive sockets are closing crashes libuv on some
// Node/Windows combinations, which would corrupt the documented CLI gate.
process.exitCode = exitCode;
