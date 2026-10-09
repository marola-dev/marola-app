# forecast-benchmark infrastructure

The GCP resources of the forecast benchmark
([MIP-0083](https://docs.marola.dev/6-MIPs/MIP-0083-forecast-benchmark-job/) §5.9), as a
[Besom](https://virtuslab.github.io/besom/) program: Pulumi in Scala. It is a scala-cli project,
not an sbt module, so `besom-gcp` never loads into the app's build.

| Resource | What for |
|---|---|
| bucket `forecast-benchmark` (us-central1, versioned, noncurrent versions deleted after 30 days) | `mlflow.db`, the benchmark state and the archived forecasts |
| Workload Identity pool `github`, provider `marola-app` | lets GitHub Actions on `main` of marola-dev/marola-app sign in without a key; a pull request's token is refused |
| service account `forecast-benchmark` | object admin on that bucket only, impersonated by the workflow |
| budget `forecast-benchmark` | e-mails the billing admins at 50 %, 100 % and 200 % of US$1 a month, across the billing account |
| the APIs `iam`, `iamcredentials`, `sts`, `storage`, `billingbudgets` | needed by the above |

## Who runs it

The owner, by hand. CI only compiles it (`infra.yml`). Giving Actions an identity that can create
IAM bindings would be a bigger risk than the convenience is worth, and every `up` that changes a
paid resource needs a person's confirmation first (AGENTS.md, cost rule). The first `up` cannot
run in Actions anyway: it creates the identity Actions would sign in with.

Before the first `up`, recompute MIP-0083's weekly cost table and confirm it.

```bash
# once, on the owner's machine, with gcloud signed in
gcloud auth application-default login
gcloud storage buckets create gs://<state-bucket> --location=us-central1 \
  --uniform-bucket-level-access --public-access-prevention
gcloud storage buckets update gs://<state-bucket> --versioning
pulumi login gs://<state-bucket>
pulumi plugin install language scala 0.5.2 --server github://api.github.com/VirtusLab/besom
pulumi plugin install resource gcp 9.0.0

cd infra/forecast-benchmark
pulumi stack init prod --secrets-provider=passphrase
pulumi config set gcp:project <project-id>
pulumi config set billingAccount <billing-account-id>
pulumi preview
pulumi up
```

`pulumi stack output` then gives `bucket`, `workloadIdentityProvider` and `serviceAccount`. They
go into marola-app's repository variables (not secrets: none of them is a credential) for the
scheduled workflow (MIP-0083 task 8).

Creating the budget needs billing-account permissions (`roles/billing.costsManager` or more) on
top of the project's.
