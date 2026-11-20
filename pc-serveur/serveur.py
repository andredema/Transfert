#!/usr/bin/env python3
"""
TransfertWifi - serveur de réception (côté PC)
------------------------------------------------
Reçoit les fichiers envoyés par l'appli Android, calcule leur SHA-256
et renvoie l'empreinte au téléphone. Le téléphone ne supprime un fichier
que si l'empreinte du PC correspond à la sienne.

Aucune dépendance : Python 3.8+ suffit.

Utilisation :
    python serveur.py                      # dossier par défaut : ./Transferts
    python serveur.py --dossier "D:/Sauvegarde téléphone" --port 8765
"""
import argparse
import hashlib
import json
import os
import secrets
import shutil
import socket
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote_plus

DOSSIER_SCRIPT = os.path.dirname(os.path.abspath(__file__))
FICHIER_CONFIG = os.path.join(DOSSIER_SCRIPT, "serveur_config.json")
TAILLE_BLOC = 1024 * 1024  # 1 Mo


def charger_jeton(jeton_force=None):
    """Garde le même jeton d'une session à l'autre (pas besoin de le retaper sur le téléphone)."""
    if jeton_force:
        return jeton_force
    try:
        with open(FICHIER_CONFIG, encoding="utf-8") as f:
            jeton = json.load(f).get("jeton")
            if jeton:
                return jeton
    except (OSError, ValueError):
        pass
    jeton = secrets.token_hex(3).upper()  # 6 caractères, facile à taper
    with open(FICHIER_CONFIG, "w", encoding="utf-8") as f:
        json.dump({"jeton": jeton}, f)
    return jeton


def ip_locale():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))  # aucun paquet n'est envoyé
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return socket.gethostbyname(socket.gethostname())


def chemin_sur(racine, relatif):
    """Empêche d'écrire hors du dossier de destination (../, chemins absolus...)."""
    relatif = relatif.replace("\\", "/").lstrip("/")
    morceaux = []
    for m in relatif.split("/"):
        m = m.strip()
        if m in ("", ".", ".."):
            continue
        for c in '<>:"|?*':  # caractères interdits sous Windows
            m = m.replace(c, "_")
        morceaux.append(m)
    if not morceaux:
        return None
    complet = os.path.abspath(os.path.join(racine, *morceaux))
    if os.path.commonpath([complet, os.path.abspath(racine)]) != os.path.abspath(racine):
        return None
    return complet


def sha256_fichier(chemin):
    h = hashlib.sha256()
    with open(chemin, "rb") as f:
        for bloc in iter(lambda: f.read(TAILLE_BLOC), b""):
            h.update(bloc)
    return h.hexdigest()


class Gestionnaire(BaseHTTPRequestHandler):
    server_version = "TransfertWifi/1.0"

    def _json(self, code, donnees):
        corps = json.dumps(donnees).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(corps)))
        self.end_headers()
        self.wfile.write(corps)

    def _jeton_ok(self):
        if self.headers.get("X-Jeton", "") != self.server.jeton:
            self._json(403, {"ok": False, "erreur": "Jeton invalide"})
            return False
        return True

    def log_message(self, fmt, *args):  # journal plus discret
        pass

    def do_GET(self):
        if self.path != "/ping":
            return self._json(404, {"ok": False, "erreur": "Inconnu"})
        if not self._jeton_ok():
            return
        libre = shutil.disk_usage(self.server.racine).free
        self._json(200, {"ok": True, "nom": socket.gethostname(), "espace_libre": libre})

    def do_POST(self):
        if self.path != "/upload":
            return self._json(404, {"ok": False, "erreur": "Inconnu"})
        if not self._jeton_ok():
            return
        try:
            taille = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            taille = -1
        if taille < 0:
            return self._json(411, {"ok": False, "erreur": "Taille manquante"})

        relatif = unquote_plus(self.headers.get("X-Chemin", ""))
        dest = chemin_sur(self.server.racine, relatif)
        if not dest:
            return self._json(400, {"ok": False, "erreur": "Chemin invalide"})

        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if shutil.disk_usage(os.path.dirname(dest)).free < taille + 50 * 1024 * 1024:
            return self._json(507, {"ok": False, "erreur": "Espace disque insuffisant sur le PC"})

        # 1) Réception dans un fichier temporaire + calcul de l'empreinte
        temp = dest + ".part"
        h = hashlib.sha256()
        recu = 0
        try:
            with open(temp, "wb") as f:
                while recu < taille:
                    bloc = self.rfile.read(min(TAILLE_BLOC, taille - recu))
                    if not bloc:
                        break
                    f.write(bloc)
                    h.update(bloc)
                    recu += len(bloc)
                f.flush()
                os.fsync(f.fileno())
        except OSError as e:
            _supprimer(temp)
            return self._json(500, {"ok": False, "erreur": f"Écriture impossible : {e}"})

        if recu != taille:
            _supprimer(temp)
            return self._json(400, {"ok": False, "erreur": "Transfert incomplet"})

        empreinte = h.hexdigest()

        # 2) Gestion des doublons : même contenu -> on garde l'existant ; sinon on renomme
        final = dest
        if os.path.exists(final):
            if os.path.getsize(final) == taille and sha256_fichier(final) == empreinte:
                _supprimer(temp)
                print(f"  = déjà présent  {relatif}")
                return self._json(200, {"ok": True, "sha256": empreinte, "doublon": True})
            base, ext = os.path.splitext(dest)
            n = 1
            while os.path.exists(f"{base} ({n}){ext}"):
                n += 1
            final = f"{base} ({n}){ext}"
        os.replace(temp, final)

        # 3) Relecture depuis le disque : on confirme ce qui est réellement écrit
        empreinte_disque = sha256_fichier(final)
        if empreinte_disque != empreinte:
            return self._json(500, {"ok": False, "erreur": "Fichier corrompu sur le disque"})

        # 4) Conserver la date d'origine du fichier
        try:
            ms = int(self.headers.get("X-Modifie", "0"))
            if ms > 0:
                os.utime(final, (ms / 1000, ms / 1000))
        except (ValueError, OSError):
            pass

        print(f"  + {taille / 1048576:8.1f} Mo  {os.path.relpath(final, self.server.racine)}")
        self._json(200, {"ok": True, "sha256": empreinte_disque, "doublon": False})


def _supprimer(chemin):
    try:
        os.remove(chemin)
    except OSError:
        pass


def main():
    p = argparse.ArgumentParser(description="Serveur de réception TransfertWifi")
    p.add_argument("--dossier", default=os.path.join(DOSSIER_SCRIPT, "Transferts"),
                   help="Dossier où ranger les fichiers reçus")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--jeton", help="Imposer un jeton (sinon généré et mémorisé)")
    a = p.parse_args()

    os.makedirs(a.dossier, exist_ok=True)
    serveur = ThreadingHTTPServer(("0.0.0.0", a.port), Gestionnaire)
    serveur.racine = os.path.abspath(a.dossier)
    serveur.jeton = charger_jeton(a.jeton)

    print("=" * 52)
    print("  TransfertWifi - serveur prêt")
    print(f"  Adresse à saisir sur le téléphone : {ip_locale()}:{a.port}")
    print(f"  Jeton                             : {serveur.jeton}")
    print(f"  Dossier de réception              : {serveur.racine}")
    print("  (Ctrl+C pour arrêter)")
    print("=" * 52)
    try:
        serveur.serve_forever()
    except KeyboardInterrupt:
        print("\nArrêt du serveur.")
        sys.exit(0)


if __name__ == "__main__":
    main()
