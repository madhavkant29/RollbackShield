import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as events from 'aws-cdk-lib/aws-events';
import * as sqs from 'aws-cdk-lib/aws-sqs';

/**
 * Single DynamoDB table (§34 single-table design -- PK/SK as modeled in
 * backend's *DynamoDbItem classes, gsi1 for the two access patterns that
 * need it), the EventBridge bus domain events publish to, and the SQS
 * queue async work is enqueued on. PAY_PER_REQUEST billing: no capacity
 * planning needed for a hackathon's traffic, and nothing to pay for at
 * zero load.
 */
export class DataStack extends cdk.Stack {
  public readonly table: dynamodb.Table;
  public readonly eventBus: events.EventBus;
  public readonly workQueue: sqs.Queue;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    this.table = new dynamodb.Table(this, 'Table', {
      tableName: 'rollbackshield',
      partitionKey: { name: 'pk', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: cdk.RemovalPolicy.RETAIN, // see docs/operations/CLEANUP.md before destroying
      pointInTimeRecovery: true,
    });

    this.table.addGlobalSecondaryIndex({
      indexName: 'gsi1',
      partitionKey: { name: 'gsi1pk', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'gsi1sk', type: dynamodb.AttributeType.STRING },
    });

    this.eventBus = new events.EventBus(this, 'EventBus', {
      eventBusName: 'rollbackshield-events',
    });

    const deadLetterQueue = new sqs.Queue(this, 'WorkQueueDLQ', {
      queueName: 'rollbackshield-work-dlq',
      retentionPeriod: cdk.Duration.days(14),
    });

    this.workQueue = new sqs.Queue(this, 'WorkQueue', {
      queueName: 'rollbackshield-work',
      visibilityTimeout: cdk.Duration.seconds(30),
      deadLetterQueue: { queue: deadLetterQueue, maxReceiveCount: 5 },
    });
  }
}
