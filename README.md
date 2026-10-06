# Triche Colis

Bot Android pour **Parcel Panic** (trier les colis par couleur) : l'app regarde l'écran, lit la couleur du
colis le plus bas du tapis et glisse dans le bon sens, bien plus vite qu'un humain.

| Couleur | Glissement |
|---|---|
| rouge | ← gauche |
| jaune | ↑ haut |
| bleu | → droite |

Un bouton flottant **■ STOP n** (déplaçable, `n` = colis triés) permet de s'arrêter au score voulu ;
**▶ GO** pour reprendre.

## Comment ça marche

- **Vue** : capture d'écran (MediaProjection) → repère le tapis (deux rails jaunes) puis les colis par leur
  couleur (teinte/saturation mesurées sur ta vidéo).
- **Cerveau** (`app/src/main/kotlin/fr/triche/colis/logic/`) : trie toujours le colis le plus bas, seulement
  si sa couleur est nette et stable. Il ne confond jamais un colis qui s'envoie vers sa boîte avec un colis du
  tapis (un seul mauvais geste termine la partie) et ne retrie pas le même colis.
- **Mains** : un service d'accessibilité injecte les glissements (`dispatchGesture`).

`sim/` contient les tests exécutés à chaque build : détection sur de vraies images de la vidéo, et un mini-jeu
simulé (latences 10–200 ms, tapis rapide, entrée bloquée pendant l'animation, capture 30 fps).

## Compilation automatique + envoi sur Discord

Chaque push lance `.github/workflows/build-apk.yml` : tests → APK → envoi sur Discord.

1. Discord : *Paramètres du salon* → *Intégrations* → *Webhooks* → *Nouveau webhook* → copier l'URL.
2. GitHub : *Settings* → *Secrets and variables* → *Actions* → *New repository secret*
   nom `DISCORD_WEBHOOK_URL`, valeur = l'URL du webhook.
3. Pousse un commit (ou *Actions* → *Build APK* → *Run workflow*) : l'APK arrive dans le salon Discord.
   Il est aussi toujours téléchargeable dans les artefacts du workflow.

## Installation et utilisation

1. Installe l'APK (autoriser les sources inconnues).
2. Ouvre **Triche Colis** → *Activer le service d'accessibilité* → active « Triche Colis ».
   Android 13+ : si c'est grisé, *Infos de l'appli* → ⋮ → *Autoriser les paramètres restreints*.
3. *Autoriser le bouton STOP flottant*.
4. *Démarrer le bot* → accepter la capture d'écran.
5. Ouvre le jeu et lance la partie : dès que le tapis est visible, le bot trie.
6. Appuie sur le bouton rouge au score voulu. Arrêt complet : bouton *Arrêter* de la notification.
