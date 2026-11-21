"""Generate credentials and a TLS certificate for a new loopback-only synthetic environment."""
import argparse
import base64
import json
import secrets
import subprocess
import uuid
from pathlib import Path


def write(path, contents):
    with path.open("x", encoding="utf-8") as stream:
        stream.write(contents)


def execution(priority, requirement, **fields):
    return {"priority": priority, "requirement": requirement, **fields}


def realm(origin, client_secret, people):
    users = []
    for username, person in people.items():
        otp = {
            "type": "otp",
            "userLabel": "Synthetic TOTP",
            "secretData": json.dumps({"value": person["otp"]}),
            "credentialData": json.dumps({"subType": "totp", "digits": 6, "counter": 0, "period": 30,
                                          "algorithm": "HmacSHA1", "secretEncoding": "BASE32"}),
        }
        users.append({
            "id": person["id"], "username": username, "enabled": True,
            "firstName": "Synthetic", "lastName": username, "email": username + "@example.invalid",
            "emailVerified": True, "credentials": [{"type": "password", "value": person["password"]}, otp],
        })
    flows = [
        {"alias": "care-browser", "providerId": "basic-flow", "topLevel": True, "builtIn": False,
         "authenticationExecutions": [
             execution(10, "REQUIRED", authenticator="auth-username-password-form"),
             execution(20, "CONDITIONAL", flowAlias="care-mfa", authenticatorFlow=True),
         ]},
        {"alias": "care-mfa", "providerId": "basic-flow", "topLevel": False, "builtIn": False,
         "authenticationExecutions": [
             execution(10, "REQUIRED", authenticator="conditional-level-of-authentication",
                       authenticatorConfig="care-mfa-level"),
             execution(20, "REQUIRED", authenticator="auth-otp-form"),
         ]},
    ]
    return {
        "realm": "care", "enabled": True, "sslRequired": "external",
        "registrationAllowed": False, "resetPasswordAllowed": False,
        "bruteForceProtected": True,
        "browserFlow": "care-browser", "authenticationFlows": flows,
        "authenticatorConfig": [{"alias": "care-mfa-level", "config": {
            "loa-condition-level": "2", "loa-max-age": "0"}}],
        "users": users,
        "clients": [{
            "clientId": "carecontinuo", "enabled": True, "protocol": "openid-connect",
            "secret": client_secret, "publicClient": False, "standardFlowEnabled": True,
            "directAccessGrantsEnabled": False, "implicitFlowEnabled": False,
            "redirectUris": [origin + "/login/oauth2/code/hospital"],
            "attributes": {"pkce.code.challenge.method": "S256", "default.acr.values": "2"},
            "protocolMappers": [{
                "name": "ACR", "protocol": "openid-connect", "protocolMapper": "oidc-acr-mapper",
                "config": {"id.token.claim": "true"},
            }],
        }],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--port", type=int, default=18443, metavar="PORT")
    parser.add_argument("--openssl", default="openssl")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("port must be between 1024 and 65535")
    directory = args.directory.resolve()
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    origin = f"https://127.0.0.1:{args.port}"
    for name in ("postgres_password", "migrator_password", "application_password", "agent_key", "care_oidc_client_secret"):
        write(directory / name, secrets.token_hex(32))
    people = {}
    for username in ("administrator", "operator-a", "operator-b", "doctor-a", "reviewer", "followup-a", "followup-b", "unregistered"):
        people[username] = {
            "id": str(uuid.uuid4()), "password": secrets.token_hex(18),
            "otp": base64.b32encode(secrets.token_bytes(20)).decode("ascii"),
        }
    client_secret = (directory / "care_oidc_client_secret").read_text()
    write(directory / "realm.json", json.dumps(realm(origin, client_secret, people)))
    write(directory / "staff.json", json.dumps(people))
    write(directory / "patient_password", secrets.token_hex(18))
    subprocess.run([args.openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "14",
                    "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1",
                    "-keyout", str(directory / "tls_key.pem"), "-out", str(directory / "tls_cert.pem")],
                   check=True, stdout=subprocess.DEVNULL)
    write(directory / "compose.env", chr(10).join([
        f"CARE_PREPROD_SECRETS={directory.as_posix()}",
        f"CARE_PREPROD_ORIGIN={origin}",
        f"CARE_PREPROD_PORT={args.port}",
        "",
    ]))
    print(directory / "compose.env")


if __name__ == "__main__":
    main()
