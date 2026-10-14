# Compteur de cubes

Application Android qui **filme l'écran d'un autre appareil** (tablette, téléphone…) où tourne le jeu « Combien de boîtes
avez-vous vues ? » et **affiche le nombre de boîtes** qui viennent de passer sur la plateforme orange.

* Caméra arrière (Camera2) en ~720p, 60 images/s si le téléphone le permet ; aperçu à l'écran.
* Chaque image est réduite puis classée par couleur : plateforme orange, dessus clair des boîtes (un par boîte), autres
  faces. Le nombre d'un passage est celui du plateau de plusieurs images de suite où il ne bouge plus (filtre médian
  contre les images floues).
* Affichage : nombre du dernier passage (la réponse à taper), historique par niveau et total. Boutons −1 / +1 pour
  corriger le dernier nombre, remise à zéro, et journal copiable.
* Aucune permission autre que la caméra ; aucun accès au jeu : l'application ne fait que regarder.

## Tests

`sim/` rejoue la vraie vidéo (330 images, niveaux 1 et 2 : 3 puis 5 boîtes) et la même vidéo avec une plateforme 2 à 3 fois
plus rapide (une image sur deux ou trois). CI : tests puis compilation de l'APK (artefact `triche-cubes`, envoyé aussi sur
Discord si le secret `DISCORD_WEBHOOK_URL` existe).

## Limites

* Calé sur les niveaux 1 et 2 de la vidéo (boîtes dorées à dessus clair sur plateforme orange). Pas testé sur un vrai
  téléphone ni sur les niveaux suivants : boîtes empilées ou cachées derrière d'autres, couleurs changées, plateforme plus
  rapide que la caméra.
* Il faut cadrer l'écran du jeu en entier, sans reflet, avec la luminosité de l'écran assez haute.
