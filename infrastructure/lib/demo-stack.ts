import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as sqs from 'aws-cdk-lib/aws-sqs';

/**
 * Demo target environment for the live verification in
 * docs/operations/AWS_DEPLOYMENT.md §4.5. Everything the verification needs
 * is created here so nothing has to be hand-created in the console:
 *
 * - an ECR repository (empty; the verification pushes two tagged images),
 * - an ECS Fargate cluster and a service running task definition revision 2,
 * - task definition revision 1 already registered (same family, older image)
 *   so the runtime reports a real candidate/previous pair,
 * - a demo SQS queue and a CloudWatch log group so discovery has real
 *   resources to observe.
 *
 * It costs one small Fargate task. Destroy it with
 * `cdk destroy RollbackShield-Demo-<env>`; the ECR repository is created
 * with autoDeleteImages so teardown is clean.
 */
export interface DemoStackProps extends cdk.StackProps {
  vpc: ec2.Vpc;
  /** Control-plane task role: may update this service, so it must be able to pass the execution role. */
  controlPlaneTaskRole: cdk.aws_iam.IRole;
}

export class DemoStack extends cdk.Stack {
  public readonly cluster: ecs.Cluster;
  public readonly service: ecs.FargateService;
  public readonly repository: ecr.Repository;

  constructor(scope: Construct, id: string, props: DemoStackProps) {
    super(scope, id, props);

    this.repository = new ecr.Repository(this, 'PaymentsRepository', {
      repositoryName: 'rollbackshield-demo-payments',
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      emptyOnDelete: true,
      autoDeleteImages: true,
    });

    this.cluster = new ecs.Cluster(this, 'DemoCluster', {
      vpc: props.vpc,
      clusterName: 'rollbackshield-demo',
      containerInsights: false,
    });

    const logGroup = new logs.LogGroup(this, 'PaymentsLogs', {
      logGroupName: '/rollbackshield/demo-payments',
      retention: logs.RetentionDays.ONE_WEEK,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const executionRole = new cdk.aws_iam.Role(this, 'DemoExecutionRole', {
      assumedBy: new cdk.aws_iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        cdk.aws_iam.ManagedPolicy.fromAwsManagedPolicyName(
          'service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });
    // ECS UpdateService requires iam:PassRole on the task's execution role;
    // without this the control plane can call the API but AWS rejects it.
    executionRole.grantPassRole(props.controlPlaneTaskRole);

    // Two revisions of the same task-definition family, both referencing the
    // CDK-created repository by tag:
    // - revision 1 pulls :v1 (the rollback target),
    // - revision 2 pulls :v2 (the candidate).
    // The service starts at desiredCount 0 so `cdk deploy` succeeds before
    // any image exists; the runbook pushes v1/v2, then starts revision 2.
    // RollbackShield resolves the tags to immutable digests through ECR
    // during observation.
    const revisionOne = this.taskDefinition('PaymentsTaskV1',
      ecs.ContainerImage.fromEcrRepository(this.repository, 'v1'), executionRole, logGroup);
    const revisionTwo = this.taskDefinition('PaymentsTaskV2',
      ecs.ContainerImage.fromEcrRepository(this.repository, 'v2'), executionRole, logGroup);
    revisionTwo.node.addDependency(revisionOne);
    // ECS UpdateService must pass BOTH the execution role and the task role.
    revisionOne.taskRole.grantPassRole(props.controlPlaneTaskRole);
    revisionTwo.taskRole.grantPassRole(props.controlPlaneTaskRole);

    this.service = new ecs.FargateService(this, 'PaymentsService', {
      cluster: this.cluster,
      taskDefinition: revisionTwo,
      serviceName: 'payments',
      desiredCount: 0,
      assignPublicIp: true,
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
    });
    this.service.node.addDependency(revisionTwo);

    new sqs.Queue(this, 'DemoJobsQueue', {
      queueName: 'rollbackshield-demo-jobs',
      visibilityTimeout: cdk.Duration.seconds(30),
    });

    new cdk.CfnOutput(this, 'DemoClusterName', { value: this.cluster.clusterName });
    new cdk.CfnOutput(this, 'DemoServiceName', { value: this.service.serviceName });
    new cdk.CfnOutput(this, 'DemoRepositoryUri', { value: this.repository.repositoryUri });
    new cdk.CfnOutput(this, 'DemoLogGroup', { value: logGroup.logGroupName });
  }

  private taskDefinition(id: string, image: ecs.ContainerImage, executionRole: cdk.aws_iam.Role,
                         logGroup: logs.LogGroup): ecs.FargateTaskDefinition {
    const taskDefinition = new ecs.FargateTaskDefinition(this, id, {
      family: 'rollbackshield-demo-payments',
      cpu: 256,
      memoryLimitMiB: 512,
      executionRole,
    });
    taskDefinition.addContainer('app', {
      image,
      portMappings: [{ containerPort: 80 }],
      logging: ecs.LogDrivers.awsLogs({ streamPrefix: 'demo-payments', logGroup }),
      healthCheck: {
        command: ['CMD-SHELL', 'curl -f http://localhost/ || exit 1'],
        interval: cdk.Duration.seconds(30),
        timeout: cdk.Duration.seconds(5),
        retries: 3,
        startPeriod: cdk.Duration.seconds(30),
      },
    });
    return taskDefinition;
  }
}
