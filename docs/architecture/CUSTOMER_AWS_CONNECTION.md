# Customer AWS connection

How RollbackShield observes a customer's AWS account without ever holding
customer static credentials.

## Roles, kept distinct

| Role | Lives in | Purpose |
| --- | --- | --- |
| RollbackShield deployment role | RollbackShield account | Runs the control plane: its own DynamoDB/EventBridge/SQS, plus hackathon-mode observation. Never used to imply customer access. |
| `RollbackShieldObservationRole` | Customer account | Trusted by the deployment role; read-only observation plus `ecs:UpdateService` if the customer delegates rollback execution. |

They are different security boundaries and different trust policies; a
change to one must not silently widen the other.

## Customer setup

Create in the customer account:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"AWS": "arn:aws:iam::<rollbackshield-account>:role/<task-role-name>"},
    "Action": "sts:AssumeRole",
    "Condition": {"StringLike": {"sts:ExternalId": "rollbackshield-*"}}
  }]
}
```

Attach a policy with the read-only observation actions (ECS describe/list,
ECR describe, SQS list/attributes, events list, logs describe/filter) and
`ecs:UpdateService` only if the customer wants delegated rollback
execution. RollbackShield's task-role policy already restricts
`sts:AssumeRole` to that role name with an external-id condition.

## In RollbackShield

Create the integration with `credential.kind = AWS_ASSUME_ROLE`,
`roleArn = arn:aws:iam::<customer>:role/RollbackShieldObservationRole`,
`externalId = rollbackshield-<something-unique>`, `endpoint = <region>`.
Optionally set `configuration.expectedAccountId` so the connection test
fails if the role resolves to the wrong account (confused-deputy guard).

`AwsClients` mints sessions through `StsAssumeRoleCredentialsProvider`
with session name `rollbackshield-observation`, so the customer's
CloudTrail shows exactly which assumption performed each observation.

## Hackathon mode

`AWS_CONTROL_PLANE_ROLE` uses the control plane's own credentials and is
intended for observing the account RollbackShield runs in. It is not the
customer path and is documented as such everywhere it appears.

## Failure behavior

- Bad role/trust/external id → connection test fails with STS's message;
  integration shows ERROR; sync refuses to run against an unverified
  connection.
- Missing permissions on one service → that provider's sync fails, the
  previous resource set is kept, errors are reported per provider.
- Credentials expiring mid-session → the SDK provider refreshes
  automatically; a hard expiry surfaces as a normal provider error and a
  failed sync, never as fabricated state.
