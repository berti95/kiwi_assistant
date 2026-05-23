#!/usr/bin/env bash
#
# One-shot, idempotent setup of every GCP resource Kiwi needs.
#
# Run this once from your machine after authenticating with `gcloud auth login`.
# Re-runs are safe: every command either checks for an existing resource or
# uses an "already exists" exit code.
#
# Required env vars (or edit the defaults below):
#   GCP_PROJECT  e.g. kiwi-assistant-494421
#   GCP_REGION   e.g. europe-west1
#   GH_REPO      e.g. berti95/kiwi_assistant
#
# What it provisions:
#   - APIs: run, artifactregistry, secretmanager, aiplatform, iamcredentials,
#     iam, sts.
#   - Artifact Registry repo `kiwi` (Docker, $GCP_REGION).
#   - Runtime SA `kiwi-backend@...` with roles/aiplatform.user and
#     roles/secretmanager.secretAccessor so Cloud Run can call Vertex AI Live
#     and read the tablet API key from Secret Manager.
#   - Deploy SA `gha-deploy@...` with roles/run.admin,
#     roles/artifactregistry.writer and the binding to act as the runtime SA.
#   - Workload Identity Federation pool/provider tied to GitHub Actions for
#     repository $GH_REPO.
#   - Secret Manager secret `KIWI_API_KEY` with a freshly generated value (only
#     created the first time; later runs leave the value alone).
#
# At the end the script prints the GitHub Actions secrets you need to add.

set -euo pipefail

GCP_PROJECT="${GCP_PROJECT:-kiwi-assistant-494421}"
GCP_REGION="${GCP_REGION:-europe-west1}"
GH_REPO="${GH_REPO:-berti95/kiwi_assistant}"

AR_REPO="kiwi"
RUNTIME_SA="kiwi-backend"
DEPLOY_SA="gha-deploy"
WIF_POOL="github-actions"
WIF_PROVIDER="github"
SECRET_NAME="KIWI_API_KEY"

echo "--- Kiwi GCP setup --------------------------------------------"
echo "  Project: $GCP_PROJECT"
echo "  Region:  $GCP_REGION"
echo "  Repo:    $GH_REPO"
echo

gcloud config set project "$GCP_PROJECT" >/dev/null

PROJECT_NUMBER="$(gcloud projects describe "$GCP_PROJECT" --format='value(projectNumber)')"
RUNTIME_SA_EMAIL="${RUNTIME_SA}@${GCP_PROJECT}.iam.gserviceaccount.com"
DEPLOY_SA_EMAIL="${DEPLOY_SA}@${GCP_PROJECT}.iam.gserviceaccount.com"

step() { printf '\n> %s\n' "$*"; }

step "Enabling APIs"
gcloud services enable \
    run.googleapis.com \
    artifactregistry.googleapis.com \
    secretmanager.googleapis.com \
    aiplatform.googleapis.com \
    iamcredentials.googleapis.com \
    iam.googleapis.com \
    sts.googleapis.com

step "Artifact Registry repo $AR_REPO ($GCP_REGION)"
if ! gcloud artifacts repositories describe "$AR_REPO" \
        --location="$GCP_REGION" >/dev/null 2>&1; then
    gcloud artifacts repositories create "$AR_REPO" \
        --location="$GCP_REGION" \
        --repository-format=docker \
        --description="Kiwi container images"
else
    echo "  already exists"
fi

step "Runtime service account $RUNTIME_SA_EMAIL"
if ! gcloud iam service-accounts describe "$RUNTIME_SA_EMAIL" >/dev/null 2>&1; then
    gcloud iam service-accounts create "$RUNTIME_SA" \
        --display-name="Kiwi Cloud Run runtime"
else
    echo "  already exists"
fi

for role in roles/aiplatform.user roles/secretmanager.secretAccessor; do
    gcloud projects add-iam-policy-binding "$GCP_PROJECT" \
        --member="serviceAccount:${RUNTIME_SA_EMAIL}" \
        --role="$role" \
        --condition=None \
        --quiet >/dev/null
done
echo "  bound roles: aiplatform.user, secretmanager.secretAccessor"

step "Deploy service account $DEPLOY_SA_EMAIL"
if ! gcloud iam service-accounts describe "$DEPLOY_SA_EMAIL" >/dev/null 2>&1; then
    gcloud iam service-accounts create "$DEPLOY_SA" \
        --display-name="Kiwi GitHub Actions deployer"
else
    echo "  already exists"
fi

for role in \
    roles/run.admin \
    roles/artifactregistry.writer \
    roles/iam.serviceAccountUser; do
    gcloud projects add-iam-policy-binding "$GCP_PROJECT" \
        --member="serviceAccount:${DEPLOY_SA_EMAIL}" \
        --role="$role" \
        --condition=None \
        --quiet >/dev/null
done
echo "  bound roles: run.admin, artifactregistry.writer, iam.serviceAccountUser"

# Allow the deploy SA to "act as" the runtime SA when deploying Cloud Run.
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA_EMAIL" \
    --member="serviceAccount:${DEPLOY_SA_EMAIL}" \
    --role="roles/iam.serviceAccountUser" \
    --quiet >/dev/null
echo "  $DEPLOY_SA can impersonate $RUNTIME_SA"

step "Workload Identity Federation pool $WIF_POOL"
if ! gcloud iam workload-identity-pools describe "$WIF_POOL" \
        --location=global >/dev/null 2>&1; then
    gcloud iam workload-identity-pools create "$WIF_POOL" \
        --location=global \
        --display-name="GitHub Actions"
else
    echo "  already exists"
fi

step "WIF provider $WIF_PROVIDER (GitHub OIDC)"
if ! gcloud iam workload-identity-pools providers describe "$WIF_PROVIDER" \
        --workload-identity-pool="$WIF_POOL" \
        --location=global >/dev/null 2>&1; then
    gcloud iam workload-identity-pools providers create-oidc "$WIF_PROVIDER" \
        --workload-identity-pool="$WIF_POOL" \
        --location=global \
        --display-name="GitHub" \
        --issuer-uri="https://token.actions.githubusercontent.com" \
        --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner,attribute.ref=assertion.ref" \
        --attribute-condition="assertion.repository == '${GH_REPO}'"
else
    echo "  already exists"
fi

# Allow only the configured GH repo to impersonate the deploy SA.
WIF_PRINCIPAL="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${WIF_POOL}/attribute.repository/${GH_REPO}"
gcloud iam service-accounts add-iam-policy-binding "$DEPLOY_SA_EMAIL" \
    --role="roles/iam.workloadIdentityUser" \
    --member="$WIF_PRINCIPAL" \
    --quiet >/dev/null
echo "  GitHub repo $GH_REPO can mint $DEPLOY_SA tokens"

step "Secret $SECRET_NAME"
if ! gcloud secrets describe "$SECRET_NAME" >/dev/null 2>&1; then
    NEW_KEY="kwi_$(openssl rand -hex 32)"
    printf '%s' "$NEW_KEY" | gcloud secrets create "$SECRET_NAME" \
        --replication-policy=automatic \
        --data-file=-
    echo "  created with a freshly generated value"
    echo
    echo "  Save this somewhere safe (it is the API key the tablet sends):"
    echo "    $NEW_KEY"
    echo "  It is also stored in Secret Manager for Cloud Run."
else
    echo "  already exists; leaving its value alone"
fi

step "Granting runtime SA access to the secret"
gcloud secrets add-iam-policy-binding "$SECRET_NAME" \
    --member="serviceAccount:${RUNTIME_SA_EMAIL}" \
    --role=roles/secretmanager.secretAccessor \
    --quiet >/dev/null

APK_BUCKET="${GCP_PROJECT}-apks"

step "APK bucket gs://${APK_BUCKET}"
if ! gcloud storage buckets describe "gs://${APK_BUCKET}" >/dev/null 2>&1; then
    gcloud storage buckets create "gs://${APK_BUCKET}" \
        --location="$GCP_REGION" \
        --uniform-bucket-level-access
else
    echo "  already exists"
fi

step "Granting deploy SA upload access on gs://${APK_BUCKET}"
gcloud storage buckets add-iam-policy-binding "gs://${APK_BUCKET}" \
    --member="serviceAccount:${DEPLOY_SA_EMAIL}" \
    --role=roles/storage.objectAdmin \
    --quiet >/dev/null

step "Granting public read on gs://${APK_BUCKET}"
if gcloud storage buckets add-iam-policy-binding "gs://${APK_BUCKET}" \
    --member=allUsers \
    --role=roles/storage.objectViewer \
    --quiet >/dev/null 2>&1; then
    echo "  done"
else
    echo "  ! org policy denied allUsers binding (same as Cloud Run)."
    echo "  ! Once the org policy is relaxed for this project, run:"
    echo "  !   gcloud storage buckets add-iam-policy-binding \\"
    echo "  !     gs://${APK_BUCKET} \\"
    echo "  !     --member=allUsers --role=roles/storage.objectViewer"
fi

WIF_PROVIDER_RESOURCE="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${WIF_POOL}/providers/${WIF_PROVIDER}"

cat <<EOF

=== Done =======================================================

Add the following to GitHub > Settings > Secrets and variables >
Actions for $GH_REPO:

  WIF_PROVIDER         $WIF_PROVIDER_RESOURCE
  WIF_SERVICE_ACCOUNT  $DEPLOY_SA_EMAIL

For the Android release pipeline you will also need (generated locally
once with keytool -genkey -v -keystore kiwi-release.jks -keyalg RSA
-keysize 2048 -validity 10000 -alias kiwi):

  ANDROID_KEYSTORE_B64       base64 -w0 kiwi-release.jks (Linux/macOS)
                             [System.Convert]::ToBase64String(
                               [IO.File]::ReadAllBytes("kiwi-release.jks"))
                             (PowerShell)
  ANDROID_KEYSTORE_PASSWORD  storepass set above
  ANDROID_KEY_ALIAS          kiwi
  ANDROID_KEY_PASSWORD       keypass set above

APK bucket created: gs://${APK_BUCKET}
APK URL pattern:    https://storage.googleapis.com/${APK_BUCKET}/kiwi-vN.apk

Next: push to main (or trigger backend-deploy manually) to roll out
the first version of the backend to Cloud Run.
EOF
