# Usado pelo seed.cmd: faz login de admin (perfil local, sem 2FA) e imprime só o token.
$ErrorActionPreference = 'Stop'
$body = @{ email = $env:ADMIN_EMAIL; password = $env:ADMIN_PASSWORD } | ConvertTo-Json
$r = Invoke-RestMethod -Method Post -Uri ($env:H + '/api/admin/auth/login') -ContentType 'application/json' -Body $body
if ($r.step -ne 'done') { [Console]::Error.WriteLine('Login incompleto (passo: ' + $r.step + '). O seed so funciona com o perfil local, sem 2FA.'); exit 1 }
Write-Output $r.token
