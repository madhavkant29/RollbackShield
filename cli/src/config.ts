import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

export interface CliConfig {
  apiUrl: string;
  token: string | null;
}

const DEFAULT_API_URL = 'http://localhost:8080';

function configPath(): string {
  return process.env.ROLLBACKSHIELD_CONFIG ?? join(homedir(), '.rollbackshield', 'config.json');
}

export function loadConfig(): CliConfig {
  let stored: Partial<CliConfig> = {};
  try {
    stored = JSON.parse(readFileSync(configPath(), 'utf8')) as Partial<CliConfig>;
  } catch {
    stored = {};
  }
  return {
    apiUrl: process.env.ROLLBACKSHIELD_API_URL ?? stored.apiUrl ?? DEFAULT_API_URL,
    token: process.env.ROLLBACKSHIELD_TOKEN ?? stored.token ?? null,
  };
}

export function saveConfig(config: CliConfig): string {
  const path = configPath();
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(config, null, 2), { mode: 0o600 });
  return path;
}

export function clearToken(): string {
  const config = loadConfig();
  saveConfig({ ...config, token: null });
  return configPath();
}
