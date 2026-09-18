import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';

/**
 * Public-subnets-only VPC: no NAT Gateway (§49 cost control -- NAT Gateway
 * is one of the most common surprise-cost sources for a personal AWS
 * account). Fargate tasks get a public IP directly and reach ECR/DynamoDB/
 * EventBridge/SQS over the public AWS network, protected by security
 * groups rather than network isolation. Acceptable for a v0.1 hackathon
 * deployment; documented as a tradeoff in docs/operations/COST_MODEL.md --
 * a private-subnet + NAT (or VPC endpoints) topology is the natural
 * upgrade path once cost is less of a constraint.
 */
export class NetworkStack extends cdk.Stack {
  public readonly vpc: ec2.Vpc;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    this.vpc = new ec2.Vpc(this, 'Vpc', {
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        {
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
      ],
    });
  }
}
