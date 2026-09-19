# AWS Deployment Guide

This assumes: you have an AWS account, AWS Budgets is already configured,
and you may currently be doing everything through the root account. That's
fine -- this guide gets you off root for daily work before touching any
RollbackShield infrastructure. No AWS account details are assumed beyond
what you provide; every command below uses placeholders:

- `<aws-account-id>` -- your 12-digit account ID
- `<aws-region>` -- e.g. `us-east-1`
- `<profile>` -- the AWS CLI profile name you'll create below

Do not substitute real values into files you commit to git. Use environment
variables or your local AWS CLI profile.

---

## 0. Root account hygiene (do this first, once)

1. Sign in to the AWS root account (console.aws.amazon.com, root user email).
2. **Verify your root email/recovery is current** -- Account settings.
3. **Set a strong, unique root password**, stored in a password manager.
4. **Enable MFA on the root user** (IAM -> root user -> Security credentials
   -> Assign MFA device). Prefer an authenticator app or hardware key over
   SMS. If AWS offers a backup/recovery MFA option, register it too.
5. **Never create root access keys.** If any exist (IAM -> root -> Security
   credentials -> Access keys), delete them now.
6. After this section, you should never sign in as root for day-to-day
   work -- only for the handful of account-level actions that genuinely
   require it (e.g. closing the account, some Billing preferences).

Verify: IAM console -> "Security recommendations" for the root user should
show MFA enabled and no active access keys.

---

## 1. Recommended: IAM Identity Center (human access)

This is the AWS-recommended path for a human developer identity today, and
is what the rest of this guide's CLI steps assume by default.

1. Console -> IAM Identity Center -> Enable (choose the region prompted;
   Identity Center is a single region per account).
2. Identity Center -> Users -> Add user. Use your own email. Complete the
   email verification.
3. Identity Center -> Permission sets -> Create permission set ->
   "Custom permission set". For RollbackShield deployment work, attach:
   - `AdministratorAccess` **only if** you want the fastest path and
     accept broad permissions for this hackathon account, **or**
   - a scoped custom policy covering: CloudFormation, IAM (role/policy
     create limited to `rollbackshield-*` names), ECS, ECR, DynamoDB,
     EventBridge, SQS, Cognito, CloudWatch Logs, EC2 (VPC), ELB, S3
     (CDK's bootstrap bucket). Scoped is more work to assemble correctly;
     for a hackathon on a personal account, many people accept
     AdministratorAccess here and rely on Budgets (already configured)
     as the safety net. Your call -- name it e.g. `RollbackShieldDeploy`.
4. Identity Center -> AWS accounts -> select your account -> Assign users
   -> pick your user -> attach the permission set from step 3.
5. Identity Center -> Dashboard -> note the **AWS access portal URL**
   (looks like `https://<something>.awsapps.com/start`).

### Configure the CLI for Identity Center

```
aws configure sso
```
When prompted:
- SSO session name: `rollbackshield`
- SSO start URL: the access portal URL from step 5
- SSO region: the region you enabled Identity Center in
- (accept the default scopes)
- Select your account and the `RollbackShieldDeploy` permission set
- CLI default client Region: `<aws-region>` (where you'll deploy)
- CLI profile name: `<profile>` (e.g. `rollbackshield`)

Verify:
```
aws sts get-caller-identity --profile <profile>
```
Expected output: JSON with your `Account`, and an `Arn` containing
`assumed-role/AWSReservedSSO_RollbackShieldDeploy_.../<your-email>`.

Re-authenticate when the SSO session expires:
```
aws sso login --profile <profile>
```

---

## 2. Alternative: IAM user + AssumeRole (if you'd rather not set up Identity Center yet)

Identity Center (temporary, federated credentials) is the preferred
long-term model -- prefer it if you can. This path is a documented
fallback, not the recommendation.

1. IAM -> Users -> Create user: `rollbackshield-deployer`. No console
   access needed if you'll only use the CLI.
2. IAM -> Users -> `rollbackshield-deployer` -> Security credentials ->
   Assign MFA device. Register an authenticator app.
3. Give this user **only** `sts:AssumeRole` on the deployment role you're
   about to create -- nothing else directly. Attach an inline policy:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": "sts:AssumeRole",
       "Resource": "arn:aws:iam::<aws-account-id>:role/RollbackShieldDeploymentRole"
     }]
   }
   ```
4. IAM -> Roles -> Create role -> Custom trust policy:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Principal": { "AWS": "arn:aws:iam::<aws-account-id>:user/rollbackshield-deployer" },
       "Action": "sts:AssumeRole",
       "Condition": { "Bool": { "aws:MultiFactorAuthPresent": "true" } }
     }]
   }
   ```
   Name it `RollbackShieldDeploymentRole`. Attach the same permission
   scope you'd have chosen in step 3 of the Identity Center path
   (AdministratorAccess or the scoped custom policy).
5. Create an access key for `rollbackshield-deployer` (IAM -> user ->
   Security credentials -> Create access key -> "Command Line Interface").
   Store the secret key in a password manager -- never in a repo, `.env`,
   Docker image, or CI config.
6. Configure two CLI profiles in `~/.aws/config`:
   ```
   [profile rollbackshield-user]
   region = <aws-region>

   [profile <profile>]
   role_arn = arn:aws:iam::<aws-account-id>:role/RollbackShieldDeploymentRole
   source_profile = rollbackshield-user
   mfa_serial = arn:aws:iam::<aws-account-id>:mfa/rollbackshield-deployer
   region = <aws-region>
   ```
   Put the access key/secret in `~/.aws/credentials` under
   `[rollbackshield-user]` via `aws configure --profile rollbackshield-user`.

Verify:
```
aws sts get-caller-identity --profile <profile>
```
This prompts for your MFA code (from `mfa_serial`) and should return an
`Arn` containing `assumed-role/RollbackShieldDeploymentRole/...`.

**Rotation/revocation:** rotate the `rollbackshield-user` access key every
90 days (IAM -> user -> Security credentials -> create new key, update
local config, delete the old key). To revoke immediately, delete the
access key -- the role's trust policy makes it useless without it anyway.

**Migrating to Identity Center later:** once set up, delete the
`rollbackshield-deployer` access key, remove the user (or leave it with no
keys as a break-glass identity), and switch your CLI profile to the SSO
one from section 1. Nothing about the deployed infrastructure needs to
change -- only how you authenticate to deploy it.

---

## 3. Bootstrap and deploy with CDK

All commands below assume your working directory is `infrastructure/` and
`--profile <profile>` selects whichever identity you set up above.

```
cd infrastructure
npm install
```

**One-time per account+region: CDK bootstrap.** This creates the S3 bucket
and IAM roles CDK uses to deploy.
```
npx cdk bootstrap aws://<aws-account-id>/<aws-region> --profile <profile>
```
Expected output ends with:
```
✅  Environment aws://<aws-account-id>/<aws-region> bootstrapped.
```

**Synthesize (no AWS calls, safe to run anytime):**
```
npx cdk synth --all --profile <profile>
```
I already ran this exact command in my own sandbox against these same
stack definitions (no AWS account involved, `--no-lookups` since I have no
account context) and confirmed it produces valid CloudFormation for all
five stacks: `RollbackShield-Network-dev`, `-Data-dev`, `-Identity-dev`,
`-ControlPlane-dev`, `-Observability-dev`. Running it again in your
environment with your real account/region should succeed the same way;
if it doesn't, send me the exact error.

**Deploy the non-compute stacks first** (they have no dependency on a
built container image, so nothing blocks on ECR having content yet):
```
npx cdk deploy RollbackShield-Network-dev RollbackShield-Data-dev RollbackShield-Identity-dev --profile <profile>
```
Each will show a permissions/resource diff and ask `Do you wish to
deploy these changes (y/n)?` -- review, then `y`. Expect this to take
3-6 minutes total (DynamoDB and EventBridge are fast; nothing here is
slow).

**Verify:**
```
aws dynamodb describe-table --table-name rollbackshield --profile <profile> --region <aws-region>
aws events describe-event-bus --name rollbackshield-events --profile <profile> --region <aws-region>
aws sqs get-queue-url --queue-name rollbackshield-work --profile <profile> --region <aws-region>
aws cognito-idp list-user-pools --max-results 10 --profile <profile> --region <aws-region>
```
Each should return JSON describing the resource, not an error.

---

## 4. Build and push the backend image, then deploy the control plane

```
cd ../backend
mvn clean package -DskipTests
```
Expected: `BUILD SUCCESS`, producing `target/rollbackshield-backend-0.1.0-SNAPSHOT.jar`.

Get the ECR repository URI (from the Data/ControlPlane stack outputs, or):
```
aws ecr describe-repositories --repository-names rollbackshield-backend --profile <profile> --region <aws-region>
```

Build and push:
```
aws ecr get-login-password --profile <profile> --region <aws-region> \
  | docker login --username AWS --password-stdin <aws-account-id>.dkr.ecr.<aws-region>.amazonaws.com

docker build -t rollbackshield-backend .
docker tag rollbackshield-backend:latest <aws-account-id>.dkr.ecr.<aws-region>.amazonaws.com/rollbackshield-backend:latest
docker push <aws-account-id>.dkr.ecr.<aws-region>.amazonaws.com/rollbackshield-backend:latest
```

I have not written a `backend/Dockerfile` in this pass -- that's the very
next thing to add (simple `eclipse-temurin:21-jre` base + copy the jar +
`ENTRYPOINT`). Tell me if you want it now or want to proceed to deploy
first and add it right before this step.

Deploy the control plane and observability stacks (this is the step that
actually needs the image in ECR, hence the ordering above):
```
cd ../infrastructure
npx cdk deploy RollbackShield-ControlPlane-dev RollbackShield-Observability-dev --profile <profile>
```
Expected: after 3-5 minutes, an output block including `LoadBalancerDns`.

**Verify the service is actually running:**
```
curl http://<LoadBalancerDns-output>/actuator/health
```
Expected: `{"status":"UP"}`. If it's not, the fastest diagnosis is:
```
aws ecs describe-services --cluster rollbackshield --services <service-name> --profile <profile> --region <aws-region>
aws logs tail /rollbackshield/backend --profile <profile> --region <aws-region> --since 10m
```
Send me the `logs tail` output if the task is failing to start.

---

## 4.5 Connected end-to-end verification (the live-run checklist)

This turns the ECS/ECR adapters from contract-tested into live-verified. All
demo resources are created by CDK (`RollbackShield-Demo-dev`): an ECR
repository, an ECS Fargate cluster with two task-definition revisions, a
service (starting at 0 tasks), a demo SQS queue and a CloudWatch log group.
**Do not create any of it by hand.** Every AWS command uses the profile
alias `rollbackshield-deploy` from §2.3; substitute your region if it is
not `us-east-1`.

### 1. Deploy everything

```
npx cdk deploy --all --profile rollbackshield-deploy
```

Expected: six stacks deploy (`Network`, `Data`, `Identity`, `ControlPlane`,
`Observability`, `Demo`), ending with outputs including:

```
RollbackShield-Demo-dev.DemoClusterName = rollbackshield-demo
RollbackShield-Demo-dev.DemoServiceName = payments
RollbackShield-Demo-dev.DemoRepositoryUri = <account>.dkr.ecr.<region>.amazonaws.com/rollbackshield-demo-payments
RollbackShield-Demo-dev.DemoLogGroup = /rollbackshield/demo-payments
✅  RollbackShield-Demo-dev
```

Note the `DemoRepositoryUri` and the control-plane ALB DNS output from
`RollbackShield-ControlPlane-dev.LoadBalancerDns`.

### 2. Push the two images into the CDK-created repository

```
aws ecr get-login-password --region <aws-region> --profile rollbackshield-deploy | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
docker pull public.ecr.aws/nginx/nginx:1.25
docker tag public.ecr.aws/nginx/nginx:1.25 <account>.dkr.ecr.<region>.amazonaws.com/rollbackshield-demo-payments:v1
docker push <account>.dkr.ecr.<region>.amazonaws.com/rollbackshield-demo-payments:v1
docker pull public.ecr.aws/nginx/nginx:1.27
docker tag public.ecr.aws/nginx/nginx:1.27 <account>.dkr.ecr.<region>.amazonaws.com/rollbackshield-demo-payments:v2
docker push <account>.dkr.ecr.<region>.amazonaws.com/rollbackshield-demo-payments:v2
aws ecr describe-images --repository-name rollbackshield-demo-payments --region <aws-region> --profile rollbackshield-deploy --query "imageDetails[].{tags:imageTags,digest:imageDigest}"
```

Expected: `docker push` ends with `...: digest: sha256:<...> size: ...` for
both tags, and `describe-images` returns two entries, `[v1]` and `[v2]`,
each with a digest.

### 3. Start the service on revision 2 (candidate), revision 1 is previous

```
aws ecs update-service --cluster rollbackshield-demo --service payments --task-definition rollbackshield-demo-payments:2 --desired-count 1 --region <aws-region> --profile rollbackshield-deploy
aws ecs wait services-stable --cluster rollbackshield-demo --services payments --region <aws-region> --profile rollbackshield-deploy
aws ecs describe-services --cluster rollbackshield-demo --services payments --region <aws-region> --profile rollbackshield-deploy --query "services[0].{taskDefinition:taskDefinition,desired:desiredCount,running:runningCount}"
```

Expected last line:

```
{"desired": 1, "running": 1, "taskDefinition": "arn:aws:ecs:<region>:<account>:task-definition/rollbackshield-demo-payments:2"}
```

### 4. Connect AWS in RollbackShield and observe

Sign in to the control room (Cognito Hosted UI through the ALB URL), then
**Integrations → Connect system → AWS**, endpoint `<region>`, credential
`AWS_CONTROL_PLANE_ROLE`, name `aws-live`. The task role already has the
observation and rollback permissions from
`infrastructure/lib/control-plane-stack.ts`.

Expected after **Test**: `CONNECTED · HEALTHY` and message
`sts:GetCallerIdentity ok for account <account>`. A connection that cannot
be verified stays `CONNECTING`/`ERROR` with the STS message -- never a fake
`CONNECTED`.

Then **Sync** → **Resources**: expect `RUNTIME_CLUSTER` `rollbackshield-demo`,
`RUNTIME_SERVICE` `rollbackshield-demo/payments`, an `ARTIFACT_REPOSITORY`
`rollbackshield-demo-payments`, the `QUEUE` `rollbackshield-demo-jobs`,
`EVENT_BUS` `rollbackshield-events`, and `LOG_GROUP`
`/rollbackshield/demo-payments`. **Import as service** `payments`.

### 5. Observe → release → preflight

In the service row: **Observe deployment** then **Create release from
observation**. Expected: candidate
`arn:aws:ecs:...:task-definition/rollbackshield-demo-payments:2`, previous
`...:1`, and the digests resolved from the pushed tags via ECR.

Open the control room and activate a contract. Expected preflight:

```
status REVERSIBLE   verdict CAN_ROLLBACK
PASS Database compatibility        (no migration source mapped -> will read
                                    MIGRATION_ANALYSIS_UNAVAILABLE/UNKNOWN if
                                    no GitHub repo is mapped; that is expected)
PASS Compute restore path          rollback-target task-definition:1
PASS Rollback artifact availability sha256:<v1 digest> exists-in ...
PASS Runtime health observability
```

If you mapped a GitHub repository with a destructive migration, the
Database check becomes `CANNOT_ROLLBACK / DESTRUCTIVE_DATABASE_MIGRATION`;
that is the product working, not a failure.

### 6. Roll back and verify

Click **Roll back** (or `rollbackshield rollback <serviceId>`). Expected:
the release ends `ROLLED_BACK`, and:

```
aws ecs describe-services --cluster rollbackshield-demo --services payments --region <aws-region> --profile rollbackshield-deploy --query "services[0].taskDefinition"
```

returns `"arn:aws:ecs:<region>:<account>:task-definition/rollbackshield-demo-payments:1"`.
The audit trail shows `ROLLBACK_EXECUTION_STEP` and `ROLLBACK_COMPLETED`.
A rollout that fails health checks ends `FAILED` with the ECS reason --
that is also correct behavior, not a bug.

### 7. Record and close out

Paste the outputs of steps 1-6 into the PR/issue, then remove the "No live
AWS run" entry from `docs/product/LIMITATIONS.md`. Tear the demo down with:

```
npx cdk destroy RollbackShield-Demo-dev --profile rollbackshield-deploy
```

(The ECR repository is created with `autoDeleteImages`/`emptyOnDelete`, so
teardown removes the pushed images too.)

### Customer-account mode (production path, not the hackathon run)

Create a role in the customer account trusted by the RollbackShield task
role with an external id, and use `AWS_ASSUME_ROLE`:

```
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

The role needs the read-only observation actions plus `ecs:UpdateService`
if rollback execution is delegated. RollbackShield sets the session name
`rollbackshield-observation` so customer CloudTrail shows exactly who
assumed it.

## 5. What I need from you if something fails

For any step above, paste:
1. The exact command you ran.
2. The full error output (redact the account ID if you want, but keep
   everything else -- error codes and messages are what I need).
3. Which section/step number you were on.

I will not guess at fixes without that -- AWS error messages are usually
specific enough to fix immediately once I can see them.

---

## 6. Cleanup

See `docs/operations/CLEANUP.md` for the full teardown procedure and a
list of what `cdk destroy` does NOT remove automatically (the DynamoDB
table and Cognito user pool are set to `RETAIN` deliberately, so a
mistaken `destroy` doesn't delete your data/users -- delete those by hand
once you're actually done).
