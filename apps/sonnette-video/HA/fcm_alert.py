#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fcm_alert.py — envoie une notification DOUCE "type=motion" à l'app custom Sonnette Vidéo
(détection livreur / présence). ADDITIF : ne modifie PAS fcm_send_ha.py (register/ring/cancel
restent intacts). Réutilise la même service-account et le même fichier de tokens.

Tourne dans le conteneur HA Core (qui a python3 + cryptography), appelé par un shell_command.

Usage :
  python3 /config/fcm_alert.py alert "<title>" "<text>" "<image_url>"

Sortie : diagnostic sur stdout (nb de tokens, code HTTP par token) → visible via
shell_command return_response ou les logs HA.
"""
import base64
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

SA_PATH = "/config/fcm-service-account.json"
TOKENS_PATH = "/config/sonnette_tokens.json"
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


def _b64url(b: bytes) -> bytes:
    return base64.urlsafe_b64encode(b).rstrip(b"=")


def get_access_token(sa: dict) -> str:
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    claim = {
        "iss": sa["client_email"],
        "scope": FCM_SCOPE,
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now,
        "exp": now + 3600,
    }
    signing_input = (
        _b64url(json.dumps(header).encode()) + b"." + _b64url(json.dumps(claim).encode())
    )
    key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    sig = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
    jwt = signing_input + b"." + _b64url(sig)
    data = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": jwt.decode(),
        }
    ).encode()
    req = urllib.request.Request("https://oauth2.googleapis.com/token", data=data)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)["access_token"]


def _extract_token(v):
    """Retourne le token FCM depuis une valeur qui peut être : une str brute, une str
    'device|token', ou un dict {token|fcm_token|value: ...}."""
    if isinstance(v, str):
        return v.rsplit("|", 1)[-1].strip() if "|" in v else v.strip()
    if isinstance(v, dict):
        for k in ("token", "fcm_token", "value", "registration"):
            if v.get(k):
                return _extract_token(v[k])
    return None


def load_tokens() -> list:
    """Charge les tokens quel que soit le format du JSON (liste ou dict, valeurs str
    ou 'device|token' ou sous-dict). Robuste : ignore les entrées vides/dupliquées."""
    with open(TOKENS_PATH, encoding="utf-8") as f:
        raw = json.load(f)

    candidates = []
    if isinstance(raw, list):
        candidates = raw
    elif isinstance(raw, dict):
        if isinstance(raw.get("tokens"), list):
            candidates = raw["tokens"]
        else:
            # {device: token}  ou  {device: {...}}  ou  {token: device}
            for k, v in raw.items():
                tok = _extract_token(v)
                # cas {token: device} : la valeur est courte (device), la CLE est le token
                if (not tok or len(tok) <= 20) and isinstance(k, str) and (":" in k or len(k) > 20):
                    tok = k
                if tok:
                    candidates.append(tok)

    seen, out = set(), []
    for c in candidates:
        tok = _extract_token(c)
        if tok and tok not in seen and len(tok) > 20:
            seen.add(tok)
            out.append(tok)
    return out


def send(access: str, project: str, token: str, title: str, text: str, image_url: str):
    msg = {
        "message": {
            "token": token,
            "data": {
                "type": "motion",
                "title": title,
                "text": text,
                "image_url": image_url,
            },
            "android": {"priority": "high"},
        }
    }
    req = urllib.request.Request(
        f"https://fcm.googleapis.com/v1/projects/{project}/messages:send",
        data=json.dumps(msg).encode(),
        headers={
            "Authorization": f"Bearer {access}",
            "Content-Type": "application/json; charset=UTF-8",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


def main():
    if len(sys.argv) < 2 or sys.argv[1] != "alert":
        print('usage: fcm_alert.py alert "<title>" "<text>" "<image_url>"')
        return
    title = sys.argv[2] if len(sys.argv) > 2 else "Quelqu'un à la porte"
    text = sys.argv[3] if len(sys.argv) > 3 else "Présence détectée"
    image = sys.argv[4] if len(sys.argv) > 4 else ""

    with open(SA_PATH, encoding="utf-8") as f:
        sa = json.load(f)
    project = sa["project_id"]

    tokens = load_tokens()
    print(f"tokens={len(tokens)} project={project} title={title!r}")
    if not tokens:
        print("AUCUN token trouvé — vérifier /config/sonnette_tokens.json")
        return

    access = get_access_token(sa)
    for i, t in enumerate(tokens):
        code, err = send(access, project, t, title, text, image)
        print(f"  #{i} …{t[-8:]} -> HTTP {code}" + (f" {err}" if err else ""))


if __name__ == "__main__":
    main()
