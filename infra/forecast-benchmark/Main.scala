import besom.*
import besom.api.gcp

/**
 * The forecast benchmark's GCP side (MIP-0083 §5.9): one bucket, the identity the scheduled
 * workflow uses to write it, and a budget alert. `pulumi up` is the owner's, locally (README.md).
 */
@main def main = Pulumi.run {
  val repo = "marola-dev/marola-app"

  val apis = List("iam", "iamcredentials", "sts", "storage", "billingbudgets").map(api =>
    gcp.projects.Service(
      s"api-$api",
      gcp.projects.ServiceArgs(service = s"$api.googleapis.com", disableOnDestroy = false)
    )
  )

  val bucket = gcp.storage.Bucket(
    "forecast-benchmark",
    gcp.storage.BucketArgs(
      // us-central1 is inside GCS's Always Free tier.
      location = "US-CENTRAL1",
      uniformBucketLevelAccess = true,
      publicAccessPrevention = "enforced",
      versioning = gcp.storage.inputs.BucketVersioningArgs(enabled = true),
      lifecycleRules = List(
        gcp.storage.inputs.BucketLifecycleRuleArgs(
          action = gcp.storage.inputs.BucketLifecycleRuleActionArgs(`type` = "Delete"),
          condition = gcp.storage.inputs.BucketLifecycleRuleConditionArgs(
            daysSinceNoncurrentTime = 30,
            withState = "ARCHIVED"
          )
        )
      )
    ),
    opts(dependsOn = apis)
  )

  val pool = gcp.iam.WorkloadIdentityPool(
    "github",
    gcp.iam.WorkloadIdentityPoolArgs(workloadIdentityPoolId = "github"),
    opts(dependsOn = apis)
  )

  // Only main of marola-app: a pull request's token carries refs/pull/N/merge and is refused, so no
  // PR can write the bucket.
  val provider = gcp.iam.WorkloadIdentityPoolProvider(
    "marola-app",
    gcp.iam.WorkloadIdentityPoolProviderArgs(
      workloadIdentityPoolId = pool.workloadIdentityPoolId,
      workloadIdentityPoolProviderId = "marola-app",
      attributeMapping = Map(
        "google.subject" -> "assertion.sub",
        "attribute.repository" -> "assertion.repository",
        "attribute.ref" -> "assertion.ref"
      ),
      attributeCondition =
        s"assertion.repository == '$repo' && assertion.ref == 'refs/heads/main'",
      oidc = gcp.iam.inputs.WorkloadIdentityPoolProviderOidcArgs(
        issuerUri = "https://token.actions.githubusercontent.com"
      )
    )
  )

  val writer = gcp.serviceaccount.Account(
    "forecast-benchmark",
    gcp.serviceaccount.AccountArgs(
      accountId = "forecast-benchmark",
      displayName = "forecast benchmark (marola-app Actions)"
    ),
    opts(dependsOn = apis)
  )

  val writesBucket = gcp.storage.BucketIamMember(
    "writer-object-admin",
    gcp.storage.BucketIamMemberArgs(
      bucket = bucket.name,
      role = "roles/storage.objectAdmin",
      member = writer.email.map(e => s"serviceAccount:$e")
    )
  )

  val actionsAsWriter = gcp.serviceaccount.IamMember(
    "actions-as-writer",
    gcp.serviceaccount.IamMemberArgs(
      serviceAccountId = writer.name,
      role = "roles/iam.workloadIdentityUser",
      member = pool.name.map(p => s"principalSet://iam.googleapis.com/$p/attribute.repository/$repo")
    )
  )

  // No project filter: the alert fires on the whole billing account passing US$1 in a month, which
  // is stricter than this project alone. Creating it needs billing-account permissions.
  val budget = gcp.billing.Budget(
    "forecast-benchmark",
    gcp.billing.BudgetArgs(
      billingAccount = config.requireString("billingAccount"),
      displayName = "forecast-benchmark",
      amount = gcp.billing.inputs.BudgetAmountArgs(
        specifiedAmount =
          gcp.billing.inputs.BudgetAmountSpecifiedAmountArgs(currencyCode = "USD", units = "1")
      ),
      thresholdRules = List(0.5, 1.0, 2.0).map(p =>
        gcp.billing.inputs.BudgetThresholdRuleArgs(thresholdPercent = p)
      )
    ),
    opts(dependsOn = apis)
  )

  Stack(writesBucket, actionsAsWriter, budget).exports(
    bucket = bucket.name,
    workloadIdentityProvider = provider.name,
    serviceAccount = writer.email
  )
}
