# Triche Piano

Application Android qui joue à **PIANO** (des touches blanches descendent, il faut toucher chacune une fois ; toucher le vide
termine la partie).

* Capture d'écran (MediaProjection, 720 px de large). Toutes les touches partagent la même position de défilement : on la
  mesure (modulo 300 px) sur les bords des touches blanches de quatre colonnes, on la « déroule » dans le temps, on en
  déduit la vitesse (qui augmente pendant la partie) et la position de chaque case (rangée, colonne).
* Chaque case blanche est touchée une seule fois, au centre de l'endroit où elle sera quand le toucher arrivera dans le jeu.
  La latence est apprise pendant la partie (instant où la touche s'éteint après le toucher). Une touche qui reste blanche
  0,45 s après son toucher est retouchée (3 essais au plus).
* Plusieurs touches à la fois : un seul geste de l'accessibilité, touchers de 1 ms espacés de 12 ms (jamais deux doigts
  simultanés), la plus basse d'abord. Un geste n'est envoyé qu'une fois le précédent terminé.
* Rien n'est touché hors du jeu : le décor doit être le violet foncé du jeu et le suivi verrouillé sur la grille.
* Journal copiable depuis l'appli.

## Tests

`sim/` : suivi du défilement sur la vraie vidéo (vitesses 700 → 1250 px/s mesurées comme à part), phases sur images pleine
définition, aucun toucher sur les menus et écrans de fin, parties simulées (500 puis 2600 px/s, latences de 30 à 120 ms, 30 à
120 images/s) sans un seul toucher à côté. CI : tests puis APK (artefact `triche-piano`, envoyé aussi sur Discord si le secret
`DISCORD_WEBHOOK_URL` existe).

## Limites

* Jamais essayée sur un vrai téléphone. Géométrie calée sur la vidéo (grille de 4 colonnes, rangées de 300 px sur 720, zone de
  jeu à 19,2 % de la hauteur) : un écran de proportions très différentes peut décaler ces positions.
* Au-delà d'environ 5000 px/s (loin de ce que montre la vidéo, 1370 px/s à 27 s) la simulation perd.
* Le jeu pourrait réserver des surprises hors de la vidéo (touches d'une autre couleur, rangées plus grandes à plus haut score).
