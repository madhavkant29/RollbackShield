import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as events from 'aws-cdk-lib/aws-events';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';

export interface ControlPlaneStackProps extends cdk.StackProps {
  vpc: ec2.Vpc;
  table: dynamodb.Table;
  eventBus: events.EventBus;
  workQueue: sqs.Queue;
  userPool: cognito.UserPool;
}

/**
 * The Spring Boot control plane on ECS Fargate behind an ALB (§32, §33 --
 * long-running JVM service, not forced into Lambda). Execution role vs.
 * task role are separate (§46): the execution role only pulls the image and
 * writes logs; the task role is the ONLY identity with access to
 * DynamoDB/EventBridge/SQS, scoped to exactly this table/bus/queue.
 */
export class ControlPlaneStack extends cdk.Stack {
  public readonly service: ecs.FargateService;
  public readonly loadBalancer: elbv2.ApplicationLoadBalancer;
  public readonly repository: ecr.Repository;
  public readonly taskRole: iam.Role;

  constructor(scope: Construct, id: string, props: ControlPlaneStackProps) {
    super(scope, id, props);

    this.repository = new ecr.Repository(this, 'Repository', {
      repositoryName: 'rollbackshield-backend',
      imageScanOnPush: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const cluster = new ecs.Cluster(this, 'Cluster', {
      vpc: props.vpc,
      clusterName: 'rollbackshield',
      containerInsights: true,
    });

    const executionRole = new iam.Role(this, 'ExecutionRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });

    this.taskRole = new iam.Role(this, 'TaskRole', {
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      description: 'Runtime identity for the RollbackShield backend -- scoped to exactly its own resources',
    });
    const taskRole = this.taskRole;
    props.table.grantReadWriteData(taskRole);
    props.eventBus.grantPutEventsTo(taskRole);
    props.workQueue.grantSendMessages(taskRole);
    props.workQueue.grantConsumeMessages(taskRole);

    // Connector observation permissions. These let the control plane observe
    // the hackathon account's own resources (control-plane role mode). The
    // production customer path uses STS AssumeRole with a customer-provided
    // role and external id -- never customer static keys; see
    // docs/operations/IAM_AND_ACCESS.md.
    taskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'ObserveEcsRuntimes',
      actions: [
        'ecs:ListClusters', 'ecs:DescribeClusters',
        'ecs:ListServices', 'ecs:DescribeServices',
        'ecs:ListTaskDefinitions', 'ecs:DescribeTaskDefinition',
      ],
      resources: ['*'],
    }));
    // Rollback execution is a distinct, more dangerous capability: it is the
    // only mutating call the control plane makes against a customer runtime.
    taskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'ExecuteControlledRollback',
      actions: ['ecs:UpdateService'],
      resources: [`arn:aws:ecs:${this.region}:${this.account}:service/*/*`],
    }));
    taskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'VerifyArtifacts',
      actions: ['ecr:DescribeRepositories', 'ecr:DescribeImages', 'ecr:BatchGetImage'],
      resources: [`arn:aws:ecr:${this.region}:${this.account}:repository/*`],
    }));
    taskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'DiscoverQueues',
      actions: ['sqs:ListQueues', 'sqs:GetQueueAttributes', 'sqs:GetQueueUrl'],
      resources: ['*'],
    }));
    taskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'DiscoverEventBuses',
      actions: ['events:ListEventBuses', 'events:DescribeEventBus'],
      resources: ['*'],
    }));
    taskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'OperationalLogEvidence',
      actions: ['logs:DescribeLogGroups', 'logs:FilterLogEvents'],
      resources: ['*'],
    }));
    // Customer integration credentials live in Secrets Manager; the task role
    // reads only the rollbackshield/* namespace, never customer-wide secrets.
    taskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'ReadIntegrationCredentialReferences',
      actions: ['secretsmanager:GetSecretValue'],
      resources: [`arn:aws:secretsmanager:${this.region}:${this.account}:secret:rollbackshield/*`],
    }));
    // Production customer-account observation: assume only roles named for
    // this integration boundary and only with an external id. External ids
    // are enforced when the customer role's trust policy is created.
    taskRole.addToPolicy(new iam.PolicyStatement({
      sid: 'AssumeCustomerObservationRoles',
      actions: ['sts:AssumeRole'],
      resources: ['arn:aws:iam::*:role/RollbackShieldObservationRole'],
      conditions: {
        StringLike: { 'sts:ExternalId': 'rollbackshield-*' },
      },
    }));

    const logGroup = new logs.LogGroup(this, 'LogGroup', {
      logGroupName: '/rollbackshield/backend',
      retention: logs.RetentionDays.TWO_WEEKS,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    // Shared worker/SDK credential for the service-credential endpoints
    // (/work/poll, /work/*/redeem, /contracts/*/policy). Generated once and
    // injected as a container secret; blank would fail closed.
    const serviceCredential = new secretsmanager.Secret(this, 'ServiceCredential', {
      secretName: 'rollbackshield/service-credential',
      generateSecretString: { passwordLength: 48, excludePunctuation: true },
    });
    serviceCredential.grantRead(taskRole);

    const taskDefinition = new ecs.FargateTaskDefinition(this, 'TaskDef', {
      cpu: 512,
      memoryLimitMiB: 1024,
      executionRole,
      taskRole,
    });

    taskDefinition.addContainer('backend', {
      image: ecs.ContainerImage.fromEcrRepository(this.repository, 'latest'),
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'backend', logGroup }),
      environment: {
        SPRING_PROFILES_ACTIVE: 'aws',
        AWS_REGION: this.region,
        ROLLBACKSHIELD_TABLE_NAME: props.table.tableName,
        ROLLBACKSHIELD_EVENT_BUS: props.eventBus.eventBusName,
        ROLLBACKSHIELD_WORK_QUEUE_URL: props.workQueue.queueUrl,
        COGNITO_ISSUER_URI: `https://cognito-idp.${this.region}.amazonaws.com/${props.userPool.userPoolId}`,
        // Rollback convergence budget for the connector executor.
        ROLLBACKSHIELD_ROLLBACK_MONITOR_TIMEOUT_SECONDS: '600',
        ROLLBACKSHIELD_ROLLBACK_MONITOR_INTERVAL_SECONDS: '5',
      },
      portMappings: [{ containerPort: 8080 }],
      secrets: {
        ROLLBACKSHIELD_SERVICE_CREDENTIAL: ecs.Secret.fromSecretsManager(serviceCredential),
      },
      healthCheck: {
        command: ['CMD-SHELL', 'curl -f http://localhost:8080/actuator/health || exit 1'],
        interval: cdk.Duration.seconds(30),
        timeout: cdk.Duration.seconds(5),
        retries: 3,
        startPeriod: cdk.Duration.seconds(60),
      },
    });

    const serviceSecurityGroup = new ec2.SecurityGroup(this, 'ServiceSecurityGroup', { vpc: props.vpc });

    this.service = new ecs.FargateService(this, 'Service', {
      cluster,
      taskDefinition,
      desiredCount: 1,
      assignPublicIp: true, // public subnets, no NAT Gateway -- see NetworkStack
      securityGroups: [serviceSecurityGroup],
    });

    this.loadBalancer = new elbv2.ApplicationLoadBalancer(this, 'Alb', {
      vpc: props.vpc,
      internetFacing: true,
    });

    const listener = this.loadBalancer.addListener('HttpListener', { port: 80, open: true });
    const targetGroup = listener.addTargets('BackendTargets', {
      port: 8080,
      targets: [this.service],
      healthCheck: { path: '/actuator/health' },
    });

    serviceSecurityGroup.addIngressRule(
      ec2.Peer.securityGroupId(this.loadBalancer.connections.securityGroups[0].securityGroupId),
      ec2.Port.tcp(8080),
      'ALB to backend',
    );

    new cdk.CfnOutput(this, 'LoadBalancerDns', { value: this.loadBalancer.loadBalancerDnsName });
    new cdk.CfnOutput(this, 'RepositoryUri', { value: this.repository.repositoryUri });
  }
}
