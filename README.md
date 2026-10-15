# Triche Time's Up

Application Android qui joue à **TIME'S UP** (« Regarde une barre se vider… touche quand le temps est écoulé ») avec une
précision de quelques millisecondes.

* Capture d'écran (MediaProjection, 720 px de large) : à chaque image, position du bord haut de la partie pleine de la barre,
  au 1/10 de pixel (interpolation sur la demi-hauteur de la marche).
* La barre se vide à vitesse constante : droite ajustée sur les mesures (écart ≈ 0,5 px), fin = instant où le bord atteint le
  bas de la barre. La barre s'efface à mi-course, d'où l'extrapolation de la seconde moitié.
* Toucher (service d'accessibilité) à `fin − latence`, depuis un fil prioritaire qui attend « à vif » (spin) pour tomber à
  ~1 ms de l'heure visée ; l'heure peut encore être corrigée par l'analyse des images pendant l'attente.
* Latence : première partie recalée d'après le délai envoi → résultat à l'écran ; ensuite on saisit les décalages affichés par
  le jeu (« 0.69 s Trop tôt ! »), et une régression en deux paramètres (latence, position du bas de la barre) s'affine à
  chaque partie.
* Appuie toute seule sur « NIVEAU SUIVANT » (désactivable).
* Journal copiable depuis l'appli.

## Tests

`sim/` : lecture de la barre et du bouton sur de vraies images de la vidéo, ajustement sur les positions réellement mesurées
(fin prévue à 8,853 s et 19,226 s comme l'ajustement indépendant), jeu simulé (3 manches : 3,76 s, 9,83 s, 11,43 s) avec
erreur de toucher de quelques ms, apprentissage de la latence. CI : tests puis APK (artefact `triche-timesup`, envoyé aussi
sur Discord si le secret `DISCORD_WEBHOOK_URL` existe).

## Limites

* Jamais essayée sur un vrai téléphone. Calée sur la vidéo : barre centrée, 29 % de la largeur, bas à 77,8 % de la hauteur,
  fond bleu foncé. Un écran de proportions très différentes peut décaler ces positions.
* Le jeu affiche le décalage au centième : on ne peut pas apprendre la latence mieux qu'à ±5 ms près par partie ; la précision
  vient de la moyenne sur plusieurs parties.
