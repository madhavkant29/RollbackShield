import { ControlPlaneClient, Release } from './client.js';
import { loadConfig, saveConfig } from './config.js';

export interface CommandIo {
  out: (line: string) => void;
  err: (line: string) => void;
}

export class CommandError extends Error {}

interface ParsedArgs {
  positional: string[];
  flags: Map<string, string[]>;
}

function parseArgs(argv: string[]): ParsedArgs {
  const positional: string[] = [];
  const flags = new Map<string, string[]>();
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg.startsWith('--')) {
      const key = arg.slice(2);
      const next = argv[i + 1];
      const value = next !== undefined && !next.startsWith('--') ? next : 'true';
      if (value !== 'true') {
        i++;
      }
      flags.set(key, [...(flags.get(key) ?? []), value]);
    } else {
      positional.push(arg);
    }
  }
  return { positional, flags };
}

function flag(parsed: ParsedArgs, name: string): string | undefined {
  return parsed.flags.get(name)?.at(-1);
}

function requireFlag(parsed: ParsedArgs, name: string): string {
  const value = flag(parsed, name);
  if (value === undefined) {
    throw new CommandError(`--${name} is required`);
  }
  return value;
}

export async function run(argv: string[], io: CommandIo): Promise<number> {
  const parsed = parseArgs(argv);
  const json = flag(parsed, 'json') === 'true';
  const [group, ...rest] = parsed.positional;

  try {
    switch (group) {
      case 'login':
        return login(parsed, io);
      case 'logout':
        return logout(io);
      case 'integrations':
        return await integrations(rest, parsed, json, io);
      case 'services':
        return await services(rest, parsed, json, io);
      case 'status':
        return await status(requirePositional(rest, 0, 'serviceId'), json, io);
      case 'observe':
        return await observe(requirePositional(rest, 0, 'serviceId'), json, io);
      case 'release':
        return await release(rest, parsed, json, io);
      case 'preflight':
        return await preflight(requirePositional(rest, 0, 'service or release id'), json, io);
      case 'rollback':
        return await rollback(requirePositional(rest, 0, 'service or release id'), flag(parsed, 'reason')
          ?? 'requested via CLI', json, io);
      case 'commit':
        return await commit(requirePositional(rest, 0, 'service or release id'), json, io);
      case 'audit':
        return await audit(requirePositional(rest, 0, 'release id'), io);
      case 'help':
      case undefined:
        io.out(HELP);
        return 0;
      default:
        throw new CommandError(`Unknown command '${group}'. Run 'rollbackshield help'.`);
    }
  } catch (error) {
    if (error instanceof CommandError) {
      io.err(`error: ${error.message}`);
      return 2;
    }
    io.err(`error: ${(error as Error).message}`);
    return 1;
  }
}

const HELP = `rollbackshield <command> [arguments] [--json]

  login --api-url <url> [--token <token>]   store control-plane connection
  logout                                    remove the stored token
  integrations list
  integrations connect --type AWS|GITHUB|KUBERNETES|POSTGRESQL|FLYWAY --name <n> --endpoint <e>
                       [--credential-kind <kind>] [--secret-ref <envName>] [--role-arn <arn>]
                       [--external-id <id>] [--config key=value ...]
  integrations test <integrationId>
  integrations sync <integrationId>
  integrations services <integrationId>     runtime resources available to import
  services list
  services import <integrationId> <resourceExternalId> [--name <serviceName>] [--migrate]
  services show <serviceId>
  status <serviceId>                        observation + latest release + verdict
  observe <serviceId>                       observe the connected runtime now
  release create <serviceId>                create a release from the latest observation
  release list <serviceId>
  release inspect <releaseId>
  preflight <serviceId|releaseId>
  rollback <serviceId|releaseId> [--reason <text>]
  commit <serviceId|releaseId>
  audit <releaseId>`;

function requirePositional(values: string[], index: number, label: string): string {
  const value = values[index];
  if (value === undefined) {
    throw new CommandError(`${label} is required`);
  }
  return value;
}

function login(parsed: ParsedArgs, io: CommandIo): number {
  const apiUrl = requireFlag(parsed, 'api-url');
  const token = flag(parsed, 'token') ?? null;
  const path = saveConfig({ apiUrl, token });
  io.out(`Saved control-plane connection to ${path} (api-url: ${apiUrl}${token ? ', token stored' : ''})`);
  return 0;
}

function logout(io: CommandIo): number {
  const config = loadConfig();
  const path = saveConfig({ ...config, token: null });
  io.out(`Removed stored token from ${path}`);
  return 0;
}

async function integrations(positional: string[], parsed: ParsedArgs, json: boolean,
                            io: CommandIo): Promise<number> {
  const client = new ControlPlaneClient(loadConfig());
  switch (positional[0]) {
    case 'list': {
      const list = await client.listIntegrations();
      if (json) {
        io.out(JSON.stringify(list, null, 2));
        return 0;
      }
      if (list.length === 0) {
        io.out('no integrations connected');
        return 0;
      }
      for (const integration of list) {
        io.out([
          integration.name.padEnd(24),
          integration.connectorType.padEnd(12),
          integration.connectionState.padEnd(10),
          integration.healthState.padEnd(10),
          integration.lastSuccessfulSyncAt ?? 'never synced',
        ].join('  '));
      }
      return 0;
    }
    case 'connect': {
      const configPairs = parsed.flags.get('config') ?? [];
      const configuration: Record<string, string> = {};
      for (const pair of configPairs) {
        const [key, ...value] = pair.split('=');
        if (!key || value.length === 0) {
          throw new CommandError(`--config expects key=value, got '${pair}'`);
        }
        configuration[key] = value.join('=');
      }
      const integration = await client.createIntegration({
        name: requireFlag(parsed, 'name'),
        type: requireFlag(parsed, 'type').toUpperCase(),
        endpoint: requireFlag(parsed, 'endpoint'),
        credential: {
          kind: (flag(parsed, 'credential-kind') ?? 'NONE').toUpperCase(),
          secretReference: flag(parsed, 'secret-ref'),
          roleArn: flag(parsed, 'role-arn'),
          externalId: flag(parsed, 'external-id'),
        },
        configuration,
      });
      io.out(JSON.stringify(integration, null, 2));
      return 0;
    }
    case 'test': {
      const result = await client.testIntegration(requirePositional(positional, 1, 'integrationId'));
      io.out(`${result.success ? 'CONNECTED' : 'ERROR'}: ${result.message}`);
      return result.success ? 0 : 1;
    }
    case 'sync': {
      const result = await client.syncIntegration(requirePositional(positional, 1, 'integrationId'));
      io.out(`discovered ${result.discoveredCount} resources, ${result.errorCount} errors`);
      for (const error of result.errors) {
        io.err(`  ${error}`);
      }
      return result.errorCount === 0 ? 0 : 1;
    }
    case 'services': {
      const resources = await client.listIntegrationServices(
        requirePositional(positional, 1, 'integrationId'));
      if (json) {
        io.out(JSON.stringify(resources, null, 2));
        return 0;
      }
      if (resources.length === 0) {
        io.out('no importable runtimes discovered; run integrations sync first');
        return 0;
      }
      for (const resource of resources) {
        io.out([resource.resourceType.padEnd(24), resource.externalId.padEnd(40),
          resource.displayName].join('  '));
      }
      return 0;
    }
    default:
      throw new CommandError('integrations expects list|connect|test|sync|services');
  }
}

async function services(positional: string[], parsed: ParsedArgs, json: boolean,
                        io: CommandIo): Promise<number> {
  const client = new ControlPlaneClient(loadConfig());
  switch (positional[0]) {
    case 'list': {
      const list = await client.listServices();
      if (json) {
        io.out(JSON.stringify(list, null, 2));
        return 0;
      }
      if (list.length === 0) {
        io.out('no services imported');
        return 0;
      }
      for (const service of list) {
        io.out(`${service.serviceId}  ${service.name}`);
      }
      return 0;
    }
    case 'import': {
      const integrationId = requirePositional(positional, 1, 'integrationId');
      const resourceExternalId = requirePositional(positional, 2, 'resourceExternalId');
      const imported = await client.importService(integrationId, resourceExternalId,
        flag(parsed, 'name'));
      io.out(json ? JSON.stringify(imported, null, 2)
        : `imported ${imported.name} (${imported.serviceId})`);
      return 0;
    }
    case 'show': {
      const mapping = await client.getMapping(requirePositional(positional, 1, 'serviceId'));
      if (json) {
        io.out(JSON.stringify(mapping, null, 2));
        return 0;
      }
      if (mapping.bindings.length === 0) {
        io.out('no bindings');
        return 0;
      }
      for (const binding of mapping.bindings) {
        io.out([binding.role.padEnd(20), binding.confidence.padEnd(22), binding.externalId].join('  '));
        io.out(`  ${binding.evidence}`);
      }
      return 0;
    }
    default:
      throw new CommandError('services expects list|import|show');
  }
}

async function releaseIdFor(client: ControlPlaneClient, reference: string): Promise<string> {
  const releases: Release[] = await client.listReleases(reference);
  if (releases.length > 0) {
    const active = releases.find((release) =>
      ['DRAFT', 'PREPARING', 'READY', 'PROTECTED_ROLLOUT', 'AT_RISK'].includes(release.state));
    return (active ?? releases[0]).releaseId;
  }
  // Not a service id; assume it is already a release id and let the API validate it.
  const release = await client.getRelease(reference);
  return release.releaseId;
}

async function status(serviceId: string, json: boolean, io: CommandIo): Promise<number> {
  const client = new ControlPlaneClient(loadConfig());
  const releases = await client.listReleases(serviceId);
  const latest = releases.find((release) =>
    ['DRAFT', 'PREPARING', 'READY', 'PROTECTED_ROLLOUT', 'AT_RISK'].includes(release.state))
    ?? releases[0];
  let preflight = null;
  if (latest) {
    try {
      preflight = await client.preflight(latest.releaseId);
    } catch {
      preflight = null;
    }
  }
  const payload = { serviceId, latestRelease: latest ?? null, preflight };
  if (json) {
    io.out(JSON.stringify(payload, null, 2));
    return 0;
  }
  if (!latest) {
    io.out(`service ${serviceId}: no releases`);
    return 0;
  }
  io.out(`${latest.previousVersionLabel} -> ${latest.candidateVersionLabel}  ${latest.state}`);
  if (preflight) {
    io.out(`reversibility: ${preflight.status}  verdict: ${preflight.verdict}`);
    for (const check of preflight.checks.filter((entry) => !entry.passed)) {
      io.out(`  blocked: ${check.name} [${check.blockerCode}] ${check.blockerDescription}`);
    }
  }
  return 0;
}

async function observe(serviceId: string, json: boolean, io: CommandIo): Promise<number> {
  const client = new ControlPlaneClient(loadConfig());
  const observation = await client.observe(serviceId);
  if (json) {
    io.out(JSON.stringify(observation, null, 2));
    return 0;
  }
  io.out(`candidate ${observation.candidateRevision}, previous ${observation.previousRevision ?? 'none'}`);
  return 0;
}

async function release(positional: string[], parsed: ParsedArgs, json: boolean,
                       io: CommandIo): Promise<number> {
  const client = new ControlPlaneClient(loadConfig());
  switch (positional[0]) {
    case 'create': {
      const observed = await client.createReleaseFromObservation(
        requirePositional(positional, 1, 'serviceId'));
      io.out(json ? JSON.stringify(observed, null, 2)
        : `release ${observed.releaseId}: ${observed.previousVersionLabel} -> `
          + `${observed.candidateVersionLabel} (${observed.state})`);
      return 0;
    }
    case 'list': {
      const releases = await client.listReleases(requirePositional(positional, 1, 'serviceId'));
      if (json) {
        io.out(JSON.stringify(releases, null, 2));
        return 0;
      }
      for (const entry of releases) {
        io.out([entry.releaseId, entry.state.padEnd(18),
          `${entry.previousVersionLabel} -> ${entry.candidateVersionLabel}`].join('  '));
      }
      return 0;
    }
    case 'inspect': {
      const releaseId = await releaseIdFor(client,
        requirePositional(positional, 1, 'service or release id'));
      const [release, preflight, audit] = await Promise.all([
        client.getRelease(releaseId),
        client.preflight(releaseId),
        client.audit(releaseId),
      ]);
      if (json) {
        io.out(JSON.stringify({ release, preflight, audit }, null, 2));
        return 0;
      }
      io.out(`${release.previousVersionLabel} -> ${release.candidateVersionLabel}  ${release.state}`);
      io.out(`reversibility: ${preflight.status}  verdict: ${preflight.verdict}`);
      for (const check of preflight.checks) {
        io.out(`  ${check.passed ? 'PASS' : 'FAIL'}  ${check.name}`
          + (check.blockerCode ? ` [${check.blockerCode}] ${check.blockerDescription}` : ''));
      }
      if (preflight.evidence.length > 0) {
        io.out('evidence:');
        for (const edge of preflight.evidence) {
          io.out(`  ${edge.subject} -${edge.relation}-> ${edge.object}  (${edge.source})`);
        }
      }
      if (audit.length > 0) {
        io.out('audit:');
        for (const entry of audit) {
          io.out(`  ${entry.timestamp}  ${entry.action.padEnd(28)} ${entry.actor}`
            + (entry.reason ? `  ${entry.reason}` : ''));
        }
      }
      return 0;
    }
    default:
      throw new CommandError('release expects create|list|inspect');
  }
}

async function preflight(reference: string, json: boolean, io: CommandIo): Promise<number> {
  const client = new ControlPlaneClient(loadConfig());
  const releaseId = await releaseIdFor(client, reference);
  const report = await client.preflight(releaseId);
  if (json) {
    io.out(JSON.stringify(report, null, 2));
    return report.verdict === 'CANNOT_ROLLBACK' ? 1 : 0;
  }
  io.out(`${report.status}  verdict: ${report.verdict}`);
  for (const check of report.checks) {
    io.out(`  ${check.passed ? 'PASS' : 'FAIL'}  ${check.name}`
      + (check.blockerCode ? ` [${check.blockerCode}] ${check.blockerDescription}` : ''));
  }
  return report.verdict === 'CANNOT_ROLLBACK' ? 1 : 0;
}

async function rollback(reference: string, reason: string, json: boolean, io: CommandIo): Promise<number> {
  const client = new ControlPlaneClient(loadConfig());
  const releaseId = await releaseIdFor(client, reference);
  const release = await client.rollback(releaseId, reason);
  io.out(json ? JSON.stringify(release, null, 2) : `release ${release.releaseId} is ${release.state}`);
  return release.state === 'ROLLED_BACK' ? 0 : 1;
}

async function commit(reference: string, json: boolean, io: CommandIo): Promise<number> {
  const client = new ControlPlaneClient(loadConfig());
  const releaseId = await releaseIdFor(client, reference);
  const release = await client.commit(releaseId);
  io.out(json ? JSON.stringify(release, null, 2) : `release ${release.releaseId} is ${release.state}`);
  return 0;
}

async function audit(releaseId: string, io: CommandIo): Promise<number> {
  const client = new ControlPlaneClient(loadConfig());
  const entries = await client.audit(releaseId);
  for (const entry of entries) {
    io.out(`${entry.timestamp}  ${entry.action.padEnd(28)} ${entry.actor}`
      + (entry.reason ? `  ${entry.reason}` : ''));
  }
  return 0;
}
