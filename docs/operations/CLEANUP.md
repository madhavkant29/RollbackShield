# Cleanup

## Tear down infrastructure

```
cd infrastructure
npx cdk destroy --all --profile <profile>
```
Confirm each stack with `y`. This removes the ECS service/cluster, ALB,
Fargate task definitions, security groups, VPC, and the CloudWatch
dashboard/alarms.

## What `cdk destroy` does NOT remove (by design)

These are set to `RemovalPolicy.RETAIN` specifically so a mistaken
`destroy` never silently deletes your data:

- **DynamoDB table** (`rollbackshield`) -- all releases, contracts, and
  audit history live here.
- **Cognito user pool** -- all registered users.
- **ECR repository and its images** -- delete manually if you want to stop
  paying for image storage (typically a few cents/month for a hackathon's
  worth of images).

Delete these by hand once you're actually done with the project:
```
aws dynamodb delete-table --table-name rollbackshield --profile <profile> --region <aws-region>
aws cognito-idp delete-user-pool --user-pool-id <pool-id> --profile <profile> --region <aws-region>
aws ecr delete-repository --repository-name rollbackshield-backend --force --profile <profile> --region <aws-region>
```

## Verify nothing billable is left

```
aws ecs list-clusters --profile <profile> --region <aws-region>
aws elbv2 describe-load-balancers --profile <profile> --region <aws-region>
aws ec2 describe-nat-gateways --profile <profile> --region <aws-region>
```
Expect empty/absent results for all three. NAT Gateways in particular are
the most common source of a surprise bill -- this deployment never
creates one (see `docs/architecture/AWS_ARCHITECTURE.md`), but it's worth
checking if you experimented with the VPC config.

CloudWatch Logs (`/rollbackshield/backend`) are set to a 2-week retention
and cost a small, bounded amount even after `destroy`, since the log
group itself isn't part of the CDK app's removal in this version --
delete it manually if desired:
```
aws logs delete-log-group --log-group-name /rollbackshield/backend --profile <profile> --region <aws-region>
```
