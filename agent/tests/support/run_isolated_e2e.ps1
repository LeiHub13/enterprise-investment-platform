$ErrorActionPreference = 'Stop'

function Get-FreeTestPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$projectName = 'agent-e2e-' + [guid]::NewGuid().ToString('N')
$mockName = $projectName + '-model'
$compose = @('-p', $projectName, '-f', (Join-Path $root 'docker-compose.yml'),
    '-f', (Join-Path $PSScriptRoot 'docker-compose.e2e.yml'), '--profile', 'app')
$env:MYSQL_PORT = [string](Get-FreeTestPort)
$env:AGENT_POSTGRES_PORT = [string](Get-FreeTestPort)
$env:APP_WEB_PORT = [string](Get-FreeTestPort)
$env:APP_ADMIN_USERNAME = 'agent_e2e_admin'
$env:APP_ADMIN_PASSWORD = 'IsolatedAgentTest123!'
$env:MYSQL_USER = 'agent_test_db'
$env:MYSQL_PASSWORD = 'isolated-db-password'
$env:MYSQL_ROOT_PASSWORD = 'isolated-root-password'
$env:AGENT_POSTGRES_USER = 'agent_test'
$env:AGENT_POSTGRES_PASSWORD = 'isolated-pg-password'
$env:AGENT_POSTGRES_DB = 'agent_e2e_checkpoints'
$env:RABBITMQ_USER = 'agent_e2e_rabbit'
$env:RABBITMQ_PASSWORD = 'isolated-rabbit-password'
$env:RABBITMQ_PORT = [string](Get-FreeTestPort)
$env:RABBITMQ_MANAGEMENT_PORT = [string](Get-FreeTestPort)
$env:APP_CONTACT_ENCRYPTION_KEY = ''
$env:APP_OPENAPI_ENABLED = 'true'
$env:APP_CORS_ALLOWED_ORIGINS = 'http://127.0.0.1:5173'
$env:APP_JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:COLLECTOR_TOKEN = 'isolated-test-only'
$env:COLLECTOR_SKIP_ENV_FILE = '1'
$env:QCC_APP_KEY = ''
$env:QCC_SECRET_KEY = ''
$env:AGENT_EVENT_API_TOKEN = 'isolated-invalid-token'
$env:DEEPSEEK_API_KEY = 'isolated-test-model-key'
$env:DEEPSEEK_BASE_URL = 'http://agent-e2e-model:8999/v1'
$env:DEEPSEEK_MODEL = 'isolated-test-model'
$env:AGENT_CHECKPOINT_POSTGRES_URI = ''
$env:AGENT_E2E_ISOLATED = '1'
$env:AGENT_E2E_BASE_URL = 'http://127.0.0.1:' + $env:APP_WEB_PORT
$env:AGENT_E2E_RABBITMQ_URL = 'amqp://agent_e2e_rabbit:isolated-rabbit-password@127.0.0.1:' + $env:RABBITMQ_PORT + '/%2F'
$env:AGENT_E2E_NETWORK = $projectName + '_default'

try {
    docker compose @compose up -d --no-build --wait postgres rabbitmq
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated PostgreSQL and RabbitMQ services' }
    $mount = 'type=bind,source=' + (Join-Path $PSScriptRoot 'mock_openai.py') + ',target=/app/mock_openai.py,readonly'
    docker run --rm -d --name $mockName --network ($projectName + '_default') --network-alias agent-e2e-model `
        --mount $mount python:3.13-slim python /app/mock_openai.py | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the isolated mock model' }
    docker compose @compose up -d --no-build --wait business-api agent web
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the isolated application stack' }
    & (Join-Path $root 'agent/.venv/Scripts/python.exe') (Join-Path $PSScriptRoot 'run_fullstack.py')
    if ($LASTEXITCODE -ne 0) { throw 'Isolated end-to-end test failed' }
    docker compose @compose --profile collector up -d --no-build --wait collector-monitor collector-publisher
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated collector services' }
    Start-Sleep -Seconds 2
    $running = @(docker compose @compose --profile collector ps --services --status running collector-monitor collector-publisher)
    if ($running -notcontains 'collector-monitor' -or $running -notcontains 'collector-publisher') {
        throw 'Isolated collector services did not remain running'
    }
    'PASS: isolated Compose collector-monitor and collector-publisher started'
    docker compose @compose --profile collector --profile automation up -d --no-build --wait collector-consumer
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated event consumer' }
    Start-Sleep -Seconds 2
    $runningConsumer = @(docker compose @compose --profile collector --profile automation ps --services --status running collector-consumer)
    if ($runningConsumer -notcontains 'collector-consumer') { throw 'Isolated event consumer did not remain running' }
    'PASS: isolated Compose event consumer started'
} finally {
    $ErrorActionPreference = 'Continue'
    docker rm -f $mockName 2>$null | Out-Null
    docker compose @compose --profile collector --profile automation down --volumes --remove-orphans
}
