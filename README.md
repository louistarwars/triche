# Triche Dangerwall

Bot Android pour **Dangerwall** : un carré rebondit entre deux murs hérissés de pics, on touche l'écran pour
sauter, chaque rebond contre un mur fait +1 point. Le bot passe entre les pics tout seul.

## Comment ça marche

- **Vue** (`logic/Detector.kt`) : capture d'écran (MediaProjection). Il lit la balle (carré blanc qui tourne, avec
  des « fantômes » derrière), les pics rouges de chaque mur et les lignes du terrain. Validé sur de vraies images.
- **Physique** (`logic/Pilot.kt`) : mesurée sur ta vidéo — la balle traverse l'écran de plus en plus vite
  (4,4 px/image au 1er rebond, 8 au 17e) et la gravité et la force du saut grandissent avec elle. Le pilote apprend
  en direct la gravité, la force du saut et le délai entre l'envoi d'un tap et son effet.
- **Plan** (`logic/Planner.kt`) : à chaque image, il simule des suites de taps pour que la balle arrive au mur
  d'en face dans un trou entre les pics (de préférence en haut d'un saut, là où la hauteur ne dépend pas du timing),
  sans toucher plafond/sol, et dans un état d'où le trou suivant reste atteignable. Les pics ne descendent jamais
  très bas : il se tient de préférence dans la bande libre du bas.
- **Mains** : un service d'accessibilité injecte les touchers.

`sim/` contient les tests exécutés à chaque build : détection sur de vraies images de la vidéo et un jeu simulé
d'après ta vidéo (vitesse croissante, pics qui se multiplient, latence et bruit de mesure).

## Réglages

- **Marge de sécurité** (px, 26 par défaut) : distance supplémentaire gardée entre la balle et les pics. Plus
  grande = plus prudent.
- **S'arrêter après N points** (0 = jamais).

## Compilation automatique + envoi sur Discord (facultatif)

Chaque push lance `.github/workflows/build-apk.yml` : tests → APK. L'APK est toujours téléchargeable dans les
*artefacts* du workflow. Pour le recevoir aussi dans un salon Discord, ajoute un secret `DISCORD_WEBHOOK_URL`
(URL d'un webhook) dans *Settings → Secrets and variables → Actions*.

## Installation et utilisation

1. Installe l'APK (autoriser les sources inconnues).
2. Ouvre **Triche Dangerwall**, puis *Activer le service d'accessibilité* → active « Triche Dangerwall ».
   Android 13+ : si c'est grisé, *Infos de l'appli* → ⋮ → *Autoriser les paramètres restreints*.
3. *Démarrer le bot* → accepter la capture d'écran.
4. Ouvre le jeu et touche l'écran une fois pour lancer la partie : le bot prend le relais dès que la balle bouge.
5. Arrêt : bouton *Arrêter* de la notification.
