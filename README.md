# Triche React

APK Android pour le mini-jeu **REACT** : une grille 3×3 de cercles, l'un d'eux s'allume en vert après un délai au hasard,
il faut le toucher le plus vite possible (le score est le temps de réaction moyen).

Le bot regarde l'écran (capture en 360 px de large : on ne lit que le centre des 9 cercles) et touche le cercle vert
par le service d'accessibilité dès l'image où il apparaît. Signature : un seul cercle vert, les huit autres sombres ;
ni le menu, ni la fin de partie, ni les pubs ne la déclenchent.

* `ReactDetector` / `ReactBot` : détection et décision, testées dans `sim/` sur de vraies images de la vidéo du jeu.
* Journal intégré (« Voir / copier le journal ») : temps d'analyse, âge de l'image, durée d'injection du geste.

Installation : APK dans GitHub → Actions → dernier run → Artifacts.
