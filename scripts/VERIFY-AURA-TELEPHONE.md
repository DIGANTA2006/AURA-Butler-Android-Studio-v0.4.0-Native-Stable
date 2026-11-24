# Verify AURA telephone changes on Windows

From the extracted project root, open PowerShell and run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\verify-aura-telephone.ps1
```

The server unit tests require Python 3.10+ (the container image uses Python 3.12). The script runs Android unit tests, lint and debug APK assembly; compiles the local VoIP Python source; runs the standard-library security/privacy tests; and validates Docker Compose configuration when Docker Compose is available. It temporarily creates `.env` from `.env.example` only when no `.env` exists, and removes only the temporary copy it created.

Before enabling the VoIP server, replace every example value in `.env`, provision model weights and certificates locally, review the model licenses, and configure firewall/NAT for your own host. The script does not start services or expose ports.

Passing this script does not constitute a real telephone, TURN, AI-model, multilingual, security-penetration or Vivo physical-device acceptance test. Those remain separate release gates.
