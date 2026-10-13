# Triche Dice

Application Android qui joue à **DICE ROLL** à ta place (3 manches : additionner 4 dés et taper la somme sur le pavé) et
qui vise **le score total que tu choisis**. Le score du jeu est la somme des temps des manches ; le bot lit les dés, puis
attend et tape le dernier chiffre au moment qui donne à chaque manche la durée voulue.

* Capture d'écran (MediaProjection) : lecture des dés (points noirs sur carrés blancs) et du chronomètre gris (chiffres lus
  par comparaison avec les signatures mesurées sur la vidéo du jeu).
* Touchers (service d'accessibilité) sur le pavé numérique, dont la position est détectée à l'écran.
* Temps de chaque manche = (temps total voulu − temps déjà fait) ÷ manches restantes : les écarts d'une manche sont
  rattrapés sur la suivante.
* Le début d'une manche se déduit des pas du chronomètre (premier pas au départ, puis toutes les 0,1 s).
* **Latence** : délai entre l'envoi du dernier toucher et l'arrêt du chronomètre, vu depuis nos images. Elle dépend du
  téléphone : après la 1re partie, saisis le score affiché par le jeu et appuie sur « Corriger la latence ».
* Journal (« Voir / copier le journal ») : le bot y écrit ce qu'il voit et ce qu'il fait, utile si quelque chose cloche.

## Construire l'APK

GitHub Actions (`.github/workflows/build-apk.yml`) lance les tests de `sim/` (lecture sur de vraies images + simulation d'une
partie) puis compile l'APK (artefact `triche-dice`, envoyé aussi sur Discord si le secret `DISCORD_WEBHOOK_URL` existe).

## Limites

* Jamais essayé sur un vrai téléphone : seule la lecture des images (vraies images de la vidéo) et la logique (jeu simulé)
  sont testées.
* Temps minimum : ≈ 0,5 s par manche (lecture + frappe) ; on ne peut pas descendre en dessous.
* Précision attendue après réglage de la latence : quelques centièmes de seconde par manche (gigue du système).
