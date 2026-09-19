# ECR connector

`connectors/aws/adapter/EcrArtifactAdapter` — capabilities
`ARTIFACT_DISCOVERY`, `ARTIFACT_VERIFICATION`.

- Discovery: `DescribeRepositories` → `ARTIFACT_REPOSITORY` resources with
  URI, tag mutability and scan-on-push.
- Verification: `DescribeImages` by **digest**. Tags are listed for human
  context only; existence is decided on the digest, because tags are
  mutable.
- Tag resolution: the ECS adapter resolves a tag-only deployed image to its
  immutable digest through ECR at observation time, so releases created
  from tag-deployed services still get verifiable artifact identity.

Preflight/rollback use this to answer "does the rollback artifact still
exist?". When it does not:

```
blocker: ROLLBACK_ARTIFACT_MISSING   verdict: CANNOT_ROLLBACK
```

A blank digest never triggers a call: the check fails with
`ARTIFACT_IDENTITY_UNKNOWN` instead (the runtime was deployed from a tag,
so no immutable artifact identity was ever recorded).

IAM: `VerifyArtifacts` in `control-plane-stack.ts`
(`ecr:DescribeRepositories`, `ecr:DescribeImages`, `ecr:BatchGetImage` on
`repository/*`).

Verification status: contract tests cover found-by-digest,
missing-digest-as-absent, blank-digest-no-call and repository discovery.
Live verification pending (ECR is LocalStack Pro).
