@echo off
rem Publica o plugin de teste "Exemplo" na loja (produto + jar v1.0.0).
rem Rode a partir da pasta coreplugins, com o license-server ja no ar (perfil local). Depois: http://127.0.0.1:8080/
rem Precisa de ADMIN_EMAIL e ADMIN_PASSWORD (os mesmos com que o servidor foi iniciado).
set H=http://127.0.0.1:8080
if "%ADMIN_EMAIL%"=="" (echo Defina ADMIN_EMAIL e ADMIN_PASSWORD, os mesmos do servidor & exit /b 1)
if "%ADMIN_PASSWORD%"=="" (echo Defina ADMIN_EMAIL e ADMIN_PASSWORD, os mesmos do servidor & exit /b 1)
set JAR=example-product\target\example-product-1.0.0.jar
if not exist %JAR% (echo Falta %JAR%. Rode: mvn clean package & exit /b 1)
set TOKEN=
for /f "delims=" %%t in ('powershell -NoProfile -ExecutionPolicy Bypass -File local\admin-login.ps1') do set TOKEN=%%t
if "%TOKEN%"=="" (echo Login de admin falhou. Confira ADMIN_EMAIL/ADMIN_PASSWORD e se o servidor esta no perfil local. & exit /b 1)
set T=Authorization: Bearer %TOKEN%
echo -- produto
curl.exe -sS -H "%T%" -H "Content-Type: application/json" -X POST %H%/api/admin/products -d @local\product.json
echo.
echo -- jar v1.0.0
curl.exe -sS -H "%T%" -X POST "%H%/api/admin/products/exemplo/versions?version=1.0.0" -F "file=@%JAR%"
echo.
echo Pronto. Abra http://127.0.0.1:8080/ , crie uma conta e compre (modo teste).
