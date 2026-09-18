# Cost Model

Personal AWS account — this matters more than it would for a company
account. All costs below are v0.1's actual footprint, not projections.

## Fixed costs (accrue even at zero traffic)
- **ECS Fargate task** (512 CPU / 1024 MB, `desiredCount: 1`): the
  dominant fixed cost, on the order of $10–15/month running continuously.
  Stop the service (`desiredCount: 0`) between demo sessions to avoid
  this.
- **ALB**: ~$16/month base, regardless of traffic.
- **NAT Gateway**: **$0 — deliberately never created** (see
  `AWS_ARCHITECTURE.md`). This is usually the biggest accidental cost in
  a hackathon AWS account; this deployment doesn't have one.
- **DynamoDB, EventBridge, SQS** (`PAY_PER_REQUEST`): effectively $0 at
  hackathon-demo traffic levels.
- **Cognito**: free tier covers a hackathon's worth of users.
- **CloudWatch Logs**: 2-week retention, a few cents/month at this
  volume.
- **ECR storage**: a few cents/month per image.

## Usage-variable costs
DynamoDB read/write request units, EventBridge `PutEvents` calls, SQS
requests, data transfer out of the ALB — all negligible below real
production traffic.

## Biggest lever if cost matters
Stop the ECS service (`desiredCount: 0`) or `cdk destroy` entirely
between working sessions — see `CLEANUP.md`. The ALB and Fargate task are
where a personal account bleeds money sitting idle; DynamoDB/SQS/
EventBridge do not.
