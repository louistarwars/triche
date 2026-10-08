# Triche Stack

APK Android qui joue à un jeu de tour (type *Stack*) : un bloc glisse au-dessus de la tour, il faut toucher
l'écran quand il est pile au-dessus du sommet. L'appli regarde l'écran (capture d'écran Android), suit le bloc,
calcule à quel instant il sera aligné avec la tour et touche l'écran (service d'accessibilité) un peu avant, pour
compenser le délai du téléphone.

## Installer

1. Récupérer l'APK `triche-stack.apk` : onglet **Actions** du dépôt → dernier run → *Artifacts* (et sur Discord
   si le secret `DISCORD_WEBHOOK_URL` est défini ; Discord n'est qu'un moyen de livraison, pas une obligation).
2. Installer, ouvrir « Triche Stack », activer le service d'accessibilité, puis « Démarrer le bot ».
3. Ouvrir le jeu, toucher l'écran pour lancer la partie : le bot prend le relais.

## Réglages

* **Score visé** : nombre de blocs posés (0 = jamais s'arrêter).
* **Rater volontairement au score visé** : coché, le bot fait tomber les blocs à côté pour terminer la partie ;
  décoché, il arrête simplement de jouer.

## Comment ça marche

* `Segmenter` : repère les faces supérieures des blocs (zones de couleur unie, claires et saturées ; le socle gris du
  départ aussi). Deux blocs voisins de couleurs différentes (≥ 9 d'écart, comme dans le jeu) restent séparés.
* `StackBot` :
  * le bloc mobile = la zone dont la couleur n'est pas celle d'un bloc déjà posé ; son centre (x) est ajusté par
    régression sur les dernières images (vitesse ≈ 350–500 px/s, constante pendant un bloc) ;
  * la cible = le centre du sommet de la tour, mesuré avant que le bloc ne le recouvre ;
  * le toucher est programmé à `instant d'alignement − latence` (le tir est planifié sur horloge, pas sur les
    images) ;
  * après chaque pose, le bloc est rogné du décalage : en comparant le sommet de la tour avant/après, on lit
    l'erreur en pixels, donc en millisecondes, et on corrige la latence (une par sens de déplacement). Elle est
    mémorisée d'une partie à l'autre.
* `sim/` : tests JVM de la même logique — segmentation sur de vraies images du jeu, simulateur de partie
  (latences, gigue, images perdues, vitesses différentes, démarrage en cours de partie, fin volontaire).
  `./gradlew -p sim test`. Avec `STACK_FRAMES=<dossier d'images d'une vidéo>`, un test rejoue une partie humaine.

## Précision (mode « tout pile »)

Le jeu avance par pas d'image (60/s) et prend un toucher à l'image qui suit son arrivée. Le bot :

1. **calibre** pendant ses 8 premiers blocs le délai entre l'envoi du toucher et l'image du jeu qui le prend (heure de
   l'image où le bloc s'est figé − heure d'envoi) ;
2. ensuite il vise **le milieu de la fenêtre** de prise en compte (insensible à une gigue de ± une demi-image) ;
3. il ne touche que si une image du jeu tombe **à moins de ~1,5 px** du centre de la tour : sinon il laisse passer le
   bloc, qui repasse toutes les ~2 s (il est donc lent : plusieurs secondes par bloc) ;
4. la trajectoire est ajustée par une oscillation sinusoïdale (le bloc accélère vers le centre) ;
5. si le fil qui touche l'écran part avec plus de 4 ms de retard, le toucher est **abandonné** (attente du prochain passage).

## Limites

Pas testé sur un vrai téléphone (aucun accès à un appareil ici) : la latence réelle de `dispatchGesture` et de la
capture est inconnue, d'où le calibrage automatique sur les premiers blocs. Si le bot se comporte mal, une vidéo
de l'écran pendant qu'il joue permet de recaler.
