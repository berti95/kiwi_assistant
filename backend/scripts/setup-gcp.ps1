<#
.SYNOPSIS
  Idempotent one-shot bootstrap of every GCP resource Kiwi needs (Windows port).

.DESCRIPTION
  PowerShell mirror of scripts/setup-gcp.sh. Run after `gcloud auth login`.
  Re-runs are safe: every step skips work that is already done.

.PARAMETER GcpProject
  GCP project ID. Defaults to $env:GCP_PROJECT or kiwi-assistant-494421.

.PARAMETER GcpRegion
  Cloud Run / Vertex region. Defaults to $env:GCP_REGION or europe-west1.

.PARAMETER GhRepo
  GitHub repo (owner/name) allowed to mint deploy-SA tokens via WIF.
  Defaults to $env:GH_REPO or berti95/kiwi_assistant.

.EXAMPLE
  .\scripts\setup-gcp.ps1

.EXAMPLE
  .\scripts\setup-gcp.ps1 -GcpProject other-proj -GcpRegion europe-west4
#>

[CmdletBinding()]
param(
    [string]$GcpProject = $(if ($env:GCP_PROJECT) { $env:GCP_PROJECT } else { "kiwi-assistant-494421" }),
    [string]$GcpRegion  = $(if ($env:GCP_REGION)  { $env:GCP_REGION }  else { "europe-west1" }),
    [string]$GhRepo     = $(if ($env:GH_REPO)     { $env:GH_REPO }     else { "berti95/kiwi_assistant" })
)

$ErrorActionPreference = 'Stop'

$ArRepo      = "kiwi"
$RuntimeSa   = "kiwi-backend"
$DeploySa    = "gha-deploy"
$WifPool     = "github-actions"
$WifProvider = "github"
$SecretName  = "KIWI_API_KEY"

function Step([string]$Msg) {
    Write-Host ""
    Write-Host "> $Msg" -ForegroundColor Cyan
}

function Invoke-GcloudCheck {
    <# Runs a gcloud "describe" command silently and returns $true if it
       succeeded (resource exists), $false otherwise.

       gcloud.ps1 on Windows wraps python.exe and forwards stderr, which
       under $ErrorActionPreference='Stop' makes a NOT_FOUND look like a
       terminating PowerShell error. We lower the preference for the
       duration of the call and merge stderr into stdout so the only
       signal that matters is $LASTEXITCODE. #>
    param([Parameter(Mandatory=$true)][scriptblock]$Block)
    $oldEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Block 2>&1 | Out-Null
    } catch {
        # Native command "error" wrapped as a terminating exception; the
        # exit code is still authoritative, fall through.
    } finally {
        $ErrorActionPreference = $oldEAP
    }
    return ($LASTEXITCODE -eq 0)
}

Write-Host "--- Kiwi GCP setup --------------------------------------------"
Write-Host "  Project: $GcpProject"
Write-Host "  Region:  $GcpRegion"
Write-Host "  Repo:    $GhRepo"
Write-Host ""

gcloud config set project $GcpProject | Out-Null

$projectNumber = (& gcloud projects describe $GcpProject --format='value(projectNumber)').Trim()
$runtimeSaEmail = "$RuntimeSa@$GcpProject.iam.gserviceaccount.com"
$deploySaEmail  = "$DeploySa@$GcpProject.iam.gserviceaccount.com"

Step "Enabling APIs"
gcloud services enable `
    run.googleapis.com `
    artifactregistry.googleapis.com `
    secretmanager.googleapis.com `
    aiplatform.googleapis.com `
    iamcredentials.googleapis.com `
    iam.googleapis.com `
    sts.googleapis.com

Step "Artifact Registry repo $ArRepo ($GcpRegion)"
if (Invoke-GcloudCheck { gcloud artifacts repositories describe $ArRepo --location=$GcpRegion }) {
    Write-Host "  already exists"
} else {
    gcloud artifacts repositories create $ArRepo `
        --location=$GcpRegion `
        --repository-format=docker `
        --description="Kiwi container images"
}

Step "Runtime service account $runtimeSaEmail"
if (Invoke-GcloudCheck { gcloud iam service-accounts describe $runtimeSaEmail }) {
    Write-Host "  already exists"
} else {
    gcloud iam service-accounts create $RuntimeSa --display-name="Kiwi Cloud Run runtime"
}

foreach ($role in @("roles/aiplatform.user", "roles/secretmanager.secretAccessor")) {
    gcloud projects add-iam-policy-binding $GcpProject `
        --member="serviceAccount:$runtimeSaEmail" `
        --role=$role `
        --condition=None `
        --quiet | Out-Null
}
Write-Host "  bound roles: aiplatform.user, secretmanager.secretAccessor"

Step "Deploy service account $deploySaEmail"
if (Invoke-GcloudCheck { gcloud iam service-accounts describe $deploySaEmail }) {
    Write-Host "  already exists"
} else {
    gcloud iam service-accounts create $DeploySa --display-name="Kiwi GitHub Actions deployer"
}

foreach ($role in @("roles/run.admin", "roles/artifactregistry.writer", "roles/iam.serviceAccountUser")) {
    gcloud projects add-iam-policy-binding $GcpProject `
        --member="serviceAccount:$deploySaEmail" `
        --role=$role `
        --condition=None `
        --quiet | Out-Null
}
Write-Host "  bound roles: run.admin, artifactregistry.writer, iam.serviceAccountUser"

gcloud iam service-accounts add-iam-policy-binding $runtimeSaEmail `
    --member="serviceAccount:$deploySaEmail" `
    --role="roles/iam.serviceAccountUser" `
    --quiet | Out-Null
Write-Host "  $DeploySa can impersonate $RuntimeSa"

Step "Workload Identity Federation pool $WifPool"
if (Invoke-GcloudCheck { gcloud iam workload-identity-pools describe $WifPool --location=global }) {
    Write-Host "  already exists"
} else {
    gcloud iam workload-identity-pools create $WifPool `
        --location=global `
        --display-name="GitHub Actions"
}

Step "WIF provider $WifProvider (GitHub OIDC)"
if (Invoke-GcloudCheck {
        gcloud iam workload-identity-pools providers describe $WifProvider `
            --workload-identity-pool=$WifPool --location=global
    }) {
    Write-Host "  already exists"
} else {
    gcloud iam workload-identity-pools providers create-oidc $WifProvider `
        --workload-identity-pool=$WifPool `
        --location=global `
        --display-name="GitHub" `
        --issuer-uri="https://token.actions.githubusercontent.com" `
        --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner,attribute.ref=assertion.ref" `
        --attribute-condition="assertion.repository == '$GhRepo'"
}

$wifPrincipal = "principalSet://iam.googleapis.com/projects/$projectNumber/locations/global/workloadIdentityPools/$WifPool/attribute.repository/$GhRepo"
gcloud iam service-accounts add-iam-policy-binding $deploySaEmail `
    --role="roles/iam.workloadIdentityUser" `
    --member=$wifPrincipal `
    --quiet | Out-Null
Write-Host "  GitHub repo $GhRepo can mint $DeploySa tokens"

Step "Secret $SecretName"
if (Invoke-GcloudCheck { gcloud secrets describe $SecretName }) {
    Write-Host "  already exists; leaving its value alone"
} else {
    # Generate 32 random bytes -> 64-char hex, prefixed with kwi_.
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $hex = (-join ($bytes | ForEach-Object { '{0:x2}' -f $_ }))
    $newKey = "kwi_$hex"

    # gcloud secrets create reads the value from a file with no trailing
    # newline. PowerShell's `Set-Content` adds a BOM/EOL by default, so we
    # write the bytes directly and clean up the temp file in finally.
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText(
            $tmp, $newKey, [System.Text.UTF8Encoding]::new($false))
        gcloud secrets create $SecretName `
            --replication-policy=automatic `
            --data-file=$tmp | Out-Null
    } finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }

    Write-Host "  created with a freshly generated value"
    Write-Host ""
    Write-Host "  Save this somewhere safe (it is the API key the tablet sends):"
    Write-Host "    $newKey"
    Write-Host "  It is also stored in Secret Manager for Cloud Run."
}

Step "Granting runtime SA access to the secret"
gcloud secrets add-iam-policy-binding $SecretName `
    --member="serviceAccount:$runtimeSaEmail" `
    --role=roles/secretmanager.secretAccessor `
    --quiet | Out-Null

$wifProviderResource = "projects/$projectNumber/locations/global/workloadIdentityPools/$WifPool/providers/$WifProvider"

Write-Host ""
Write-Host "=== Done ======================================================="
Write-Host ""
Write-Host "Add the following to GitHub > Settings > Secrets and variables > Actions for ${GhRepo}:"
Write-Host ""
Write-Host "  WIF_PROVIDER         $wifProviderResource"
Write-Host "  WIF_SERVICE_ACCOUNT  $deploySaEmail"
Write-Host ""
Write-Host "Next: push to main (or trigger backend-deploy manually) to roll out"
Write-Host "the first version of the backend to Cloud Run."
