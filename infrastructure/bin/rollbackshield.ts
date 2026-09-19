#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { NetworkStack } from '../lib/network-stack';
import { DataStack } from '../lib/data-stack';
import { IdentityStack } from '../lib/identity-stack';
import { ControlPlaneStack } from '../lib/control-plane-stack';
import { ObservabilityStack } from '../lib/observability-stack';
import { DemoStack } from '../lib/demo-stack';

const app = new cdk.App();

const envName = app.node.tryGetContext('envName') ?? 'dev';
const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'us-east-1',
};

const tags = { Project: 'RollbackShield', Environment: envName };

const network = new NetworkStack(app, `RollbackShield-Network-${envName}`, { env, tags });

const data = new DataStack(app, `RollbackShield-Data-${envName}`, { env, tags });

const identity = new IdentityStack(app, `RollbackShield-Identity-${envName}`, { env, tags });

const controlPlane = new ControlPlaneStack(app, `RollbackShield-ControlPlane-${envName}`, {
  env,
  tags,
  vpc: network.vpc,
  table: data.table,
  eventBus: data.eventBus,
  workQueue: data.workQueue,
  userPool: identity.userPool,
});

new ObservabilityStack(app, `RollbackShield-Observability-${envName}`, {
  env,
  tags,
  service: controlPlane.service,
  loadBalancer: controlPlane.loadBalancer,
});

// Demo target for the live verification (docs/operations/AWS_DEPLOYMENT.md
// §4.5). Deployed by default so `cdk deploy --all` leaves nothing to create
// by hand; destroy with `cdk destroy RollbackShield-Demo-<envName>`.
new DemoStack(app, `RollbackShield-Demo-${envName}`, {
  env,
  tags,
  vpc: network.vpc,
  controlPlaneTaskRole: controlPlane.taskRole,
});
