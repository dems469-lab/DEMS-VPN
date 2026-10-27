# DEMS VPN

Base de client VPN Android construite autour du **cœur Xray** utilisé par
[v2rayNG](https://github.com/2dust/v2rayNG) : `libv2ray` (AndroidLibXrayLite) pour le
protocole, et `hev-socks5-tunnel` pour relier l'interface TUN au proxy local.

> Projet indépendant, non affilié à 2dust, XTLS ou heiher.
> Licence GPL-3.0 (voir [Licence](#licence)).

---

## Ce que contient cette base

| Fonction | État |
|---|---|
| Tunnel VPN complet (VpnService → TUN → tun2socks → Xray) | ✅ |
| VLESS (+ TLS / REALITY / WS / gRPC / HTTPUpgrade / xhttp / TCP-HTTP) | ✅ |
| VMess, Trojan, Shadowsocks | ✅ |
| Import par lien `vless://`, `vmess://`, `trojan://`, `ss://` | ✅ |
| Gestion de plusieurs serveurs (ajout, sélection, suppression) | ✅ |
| DNS résolu **dans** le tunnel : IP, TCP ou DoH | ✅ |
| FakeDNS, MTU, IPv6, blocage QUIC | ✅ |
| Applications contournées (split tunneling), accès LAN | ✅ |
| Sniffing / RouteOnly | ✅ |
| Reconnexion auto (Wi-Fi ↔ 4G), WakeLock | ✅ |
| Ports SOCKS5 / HTTP configurables, UDP via SOCKS5 | ✅ |
| Tampons TCP/UDP (hev), niveau de journalisation | ✅ |
| Test de latence par serveur (mesure du cœur) | ✅ |
| Débits temps réel (accueil + notification) | ✅ |
| Verrouillage de l'app par code PIN | ✅ |
| Version TLS minimale (auto / 1.2 / 1.3) | ✅ |
| Notification de premier plan + arrêt depuis la notification | ✅ |
| UI Jetpack Compose Material 3, thème clair/sombre, FR + EN | ✅ |
| Abonnements, QR code, mode root, UDPGW/DNSTT | ❌ (pistes plus bas) |

## Architecture

```
 Applications Android
        │  (tout le trafic, sauf DEMS VPN lui-même)
        ▼
 ┌──────────────────────────┐
 │  VpnService (TUN)        │  DemsVpnService.kt
 │  10.10.10.2/30, DNS      │
 └────────────┬─────────────┘
              │ fd TUN
              ▼
 ┌──────────────────────────┐
 │  hev-socks5-tunnel       │  hev.htproxy.TProxyService (JNI, AAR)
 │  paquets IP → SOCKS5     │
 └────────────┬─────────────┘
              │ 127.0.0.1:10808 (auth aléatoire par session)
              ▼
 ┌──────────────────────────┐
 │  Cœur Xray (libv2ray)    │  XrayCore.kt + XrayConfigBuilder.kt
 │  sniffing + routage      │
 └────────────┬─────────────┘
              │ VLESS / VMess / Trojan / Shadowsocks
              ▼
          Serveur VPN
```

Points importants :

- **Le processus de l'app est exclu du tunnel** (`addDisallowedApplication`) : les
  connexions sortantes de Xray ne repassent pas dans le TUN (pas de boucle).
- **Routage Xray** : DNS (port 53) → module DNS ; IP privées → `direct` ; le reste → `proxy`.
- **Le SOCKS local est protégé** par un utilisateur/mot de passe aléatoires générés à
  chaque connexion, pour éviter qu'une autre app le réutilise.
- Les AAR natifs ne sont **pas** dans le dépôt : ils sont téléchargés automatiquement
  (voir plus bas).

### Fichiers clés

| Fichier | Rôle |
|---|---|
| `service/DemsVpnService.kt` | Cycle de vie VPN, création du TUN, démarrage Xray + tun2socks, notification |
| `core/XrayCore.kt` | Enveloppe de `libv2ray` (init, start, stop, version) |
| `core/XrayConfigBuilder.kt` | Génération du JSON Xray (inbound SOCKS, outbound, DNS, routage) |
| `data/ProfileParser.kt` | Parsing des liens de partage |
| `data/ProfileStore.kt` | Persistance des serveurs |
| `ui/` | Écrans Compose (accueil, serveurs, paramètres) |

## Construire

### GitHub Actions (recommandé)

Le workflow `.github/workflows/android.yml` construit l'APK à chaque push :

1. télécharge les deux AAR (`scripts/fetch-deps.sh`) ;
2. lance `./gradlew assembleDebug assembleRelease` ;
3. publie `DEMS-VPN-debug` dans les artefacts du run.

Aucune signature n'est configurée : l'APK **debug** est installable directement,
l'APK **release** est non signé.

### En local

Prérequis : **JDK 17**, **Android SDK 35**, et `ANDROID_HOME` configuré.

```bash
bash scripts/fetch-deps.sh      # optionnel : Gradle le fait aussi (tâche fetchDeps)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Les versions des cœurs sont figées dans `app/build.gradle.kts` :

| Cœur | Version | Source |
|---|---|---|
| libv2ray (Xray) | `v26.10.21` | [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite/releases) |
| hev-socks5-tunnel | `2.19.3` | [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel/releases) |

Pour mettre à jour : changez `coreVersion` / `hevVersion` dans `app/build.gradle.kts`
et les valeurs par défaut de `scripts/fetch-deps.sh`, puis supprimez les fichiers de
`app/libs/`.

## Utiliser

1. Installer l'APK, ouvrir **DEMS VPN**.
2. **Serveurs → +** puis coller un lien `vless://…` (ou `vmess://`, `trojan://`, `ss://`).
   Le bouton **Coller** lit le presse-papiers.
3. Sélectionner le serveur, revenir à l'accueil, appuyer sur le bouton d'alimentation.
4. Accepter la demande de permission VPN d'Android.
5. La notification « Connecté à … » permet de déconnecter sans rouvrir l'app.

### Paramètres

**Réseau & protocole** : DNS (IP nue, `tcp://…` ou DoH `https://…`), FakeDNS, MTU
(1280–9000, préréglages), IPv6, blocage de QUIC (UDP 443).

> Xray ne sait faire du DNS distant qu'en DoH (`https://`), `h2c://` ou TCP (`tcp://`).
> Il n'existe pas de DoT (`tls://`) ni de DoQ distant : une valeur `tls://…` est ramenée
> à l'IP correspondante, et un nom d'hôte non numérique retombe sur `1.1.1.1`.

**Routage** : sniffing des domaines, RouteOnly, routage par application dans les
deux sens (exclure les apps choisies du VPN, ou n'autoriser que celles choisies),
accès au proxy SOCKS/HTTP depuis le réseau local.

**Anti-censure** : fragmentation du handshake TLS + bruit aléatoire (`finalmask` du
cœur Xray) pour rendre le trafic peu identifiable par un deep packet inspection.
Transports TCP uniquement (tcp, ws, grpc, h2, httpupgrade, xhttp) ; en REALITY la
fragmentation porte sur 1–3 paquets.

**Moteur de décision** : « Tester les serveurs à la connexion » mesure la latence de
tous les serveurs en parallèle (2 instances Xray temporaires au maximum) et connecte
automatiquement le plus rapide. Pendant la recherche, réappuyer sur le bouton
connecte directement le serveur actuel sans attendre.

**Performance & stabilité** : watchdog toutes les 20 s (état du cœur **et** requête
HTTP réelle via le proxy), reconnexion sur changement de réseau avec backoff
exponentiel 1→30 s (8 essais), WakeLock borné et renouvelé, tampons TCP/UDP avec
profils Stable/Rapide, `sockopt` (TFO, TCP_NODELAY, keepalive), Mux optionnel
(WS + TLS uniquement). Le service ne redémarre plus seul si l'utilisateur avait
arrêté le VPN.

**Ports** : SOCKS5 (10808, authentifié), HTTP (10809, **désactivé par défaut** ;
s'il est activé, authentification obligatoire), UDP via SOCKS5.

**Sécurité** : code PIN au lancement (PBKDF2-HMAC-SHA256 salé, comparaison en temps
constant), version TLS minimale, Hardware ID (clé XUDP de 32 octets) affiché et
régénérable.

**Diagnostics** : écran dédié (temps de connexion, latence, débit moyen sur 10 s,
reconnexions de session, taille décodée de la clé XUDP), URL de test, niveau de
journalisation, test de latence par serveur, débits en temps réel.

## Limites connues / prochaines étapes

- **`minSdk 29`** : imposé par l'AAR officiel de hev-socks5-tunnel. Pour descendre à 24,
  il faut recompiler hev avec le NDK (`Android.mk`, `APP_PLATFORM=android-24`), comme le
  fait v2rayNG.
- **IPv6 désactivé par défaut** : la route `::/0` est posée sur le TUN et Xray bloque
  `::/0` — aucun paquet IPv6 ne peut sortir hors tunnel, mais les applications n'ont
  pas d'IPv6 tant que l'option est désactivée. Activez-la si votre réseau et votre
  serveur le supportent.
- **Bientôt (non implémenté)** : rotation automatique vers un serveur de secours,
  changement de transport automatique (ws → grpc → …), abonnements, import par QR
  code, mode root (proxy transparent hors API VPN), UDPGW/DNSTT, démarrage au boot,
  tuile Quick Settings, compteur de données par application.
- **Non prévu avec ce cœur — vrai multipath (Wi-Fi + 4G simultanés)** : pas réalisable avec l'API VpnService
  (un seul TUN par app) ni avec un noyau Xray seul. La reconnexion automatique fait la
  bascule entre réseaux, mais sans continuité ni agrégation.
- **Non prévu avec ce cœur — FEC / BBR** : Xray n'expose ni FEC ni contrôle de congestion BBR côté client
  (BBR exigerait un noyau modifié, donc root). Approximation possible côté client :
  transports mKCP/QUIC selon ce que le serveur supporte.
- **Non prévu avec ce cœur — compression** : Xray ne compresse pas le trafic
  applicatif ; l'option gzip d'autres clients n'a pas d'équivalent ici.
- **Verrouillage** : code PIN uniquement (pas de biométrie pour l'instant) ; le PIN est
  stocké sous forme de PBKDF2 salé, jamais en clair. PIN oublié = effacement des
  données de l'application.
- **DoT / DoQ distant** : non supportés par le cœur Xray embarqué (voir plus haut).
- **Signature release** : ajoutez un keystore et la configuration `signingConfigs`
  avant de distribuer.
- **Play Store** : l'app utilise `specialUse` comme type de service de premier plan ;
  une justification sera demandée. La licence GPL-3.0 impose aussi la publication du code.

## Dépannage

- **« Permission VPN refusée »** : accorder la permission, ou désactiver un autre VPN actif.
- **La connexion reste sur « Connexion… »** : vérifier le lien du serveur (UUID, TLS/SNI,
  transport). Les logs du cœur sont visibles avec `adb logcat -s DemsXray DemsVpnService`.
- **Pas d'Internet après connexion** : essayer de désactiver IPv6, ou changer le DNS.
- **L'app est tuée en arrière-plan** : désactiver l'optimisation de batterie pour DEMS VPN.

## Licence

Ce projet est distribué sous **GPL-3.0** (fichier `LICENSE`), car il reprend
l'architecture d'intégration de v2rayNG (GPL-3.0).

Composants tiers :

- [Xray-core](https://github.com/XTLS/Xray-core) — MPL-2.0
- [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) — LGPL-3.0
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — MIT
- [v2rayNG](https://github.com/2dust/v2rayNG) — GPL-3.0 (référence d'intégration)

Utilisez DEMS VPN uniquement avec des serveurs et des réseaux que vous êtes autorisé à
utiliser, dans le respect des lois et conditions d'utilisation applicables.
