# Triche

Un bot pour le jeu « garde la balle en l'air entre les piliers » (balle carrée blanche, piliers roses).
L'app Android regarde l'écran, repère la balle et le trou du prochain pilier, et touche l'écran
à ta place au bon moment.

## Comment ça marche

- **Vue** : capture d'écran (MediaProjection) → lecture directe des pixels : bandes roses plafond/sol,
  balle blanche, piliers roses (et leur trou). Le chiffre du score, dessiné par-dessus les piliers, est ignoré.
- **Cerveau** (`app/src/main/kotlin/fr/triche/bot/logic/`) : physique mesurée sur ta vidéo
  (gravité ≈ 3,4 L/s², saut ≈ −0,6 L/s, défilement ≈ 0,48 L/s, L = largeur de l'écran).
  Il vise le milieu du trou du prochain pilier et touche quand la balle, prédite après la latence,
  passe sous la cible. La latence (capture + injection) est apprise en direct.
- **Mains** : un service d'accessibilité injecte les touchers (`dispatchGesture`).

`sim/` contient les tests exécutés à chaque build : détection sur de vraies images de la vidéo et
simulation complète du jeu (latences de 20 à 160 ms, trous de 300 px, vitesse ×1,4, capture 30 fps).

## Compilation automatique + envoi sur Discord

Chaque push lance `.github/workflows/build-apk.yml` : tests → APK → envoi sur Discord.

1. Discord : *Paramètres du salon* → *Intégrations* → *Webhooks* → *Nouveau webhook* → copier l'URL.
2. GitHub : *Settings* → *Secrets and variables* → *Actions* → *New repository secret*
   nom `DISCORD_WEBHOOK_URL`, valeur = l'URL du webhook.
3. Pousse un commit (ou *Actions* → *Build APK* → *Run workflow*) : l'APK arrive dans le salon Discord.
   Il est aussi toujours téléchargeable dans les artefacts du workflow.

## Installation et utilisation

1. Ouvre l'APK reçu sur Discord et installe-le (autoriser les sources inconnues).
2. Ouvre **Triche** → *Activer le service d'accessibilité* → active « Triche ».
   Android 13+ : si c'est grisé, *Infos de l'appli* → ⋮ → *Autoriser les paramètres restreints*.
3. *Démarrer le bot* → accepter la capture d'écran.
4. Ouvre le jeu, touche l'écran une fois pour lancer la partie : le bot prend le relais.
5. Arrêt : bouton *Arrêter* dans la notification.

Le bot ne touche que pendant une partie détectée ; relancer une partie reste manuel.
