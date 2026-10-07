# Triche Maths

Bot Android pour **Quick Maths** (5 équations, on touche la bonne réponse parmi 4, le score est le temps total).
Le bot lit chaque équation, la résout, et touche la bonne réponse **au moment voulu** pour que le chrono du jeu
s'arrête pile au temps final que tu as choisi dans l'app.

## Temps final réglable

Dans l'app : *Temps final visé* (en secondes) et la *répartition* :

- **Naturelle** : temps de réflexion variés sur les 5 questions (qui s'ajoutent au temps visé).
- **Rapide puis attente** : les 4 premières réponses très vite, puis attente du bon moment avant la dernière.

Mesuré sur ta vidéo : le chrono du jeu court de l'affichage de la question 1 jusqu'au dernier tap, et il y a
toujours 0,40 s entre un tap et la question suivante. Le minimum possible est donc ≈ 3,1 s.
Après chaque partie, l'app lit le temps affiché sur l'écran de fin et corrige son réglage fin (ms) pour que
la partie suivante tombe plus près de la cible (désactivable).

## Comment ça marche

- **Vue** : capture d'écran (MediaProjection). L'écran de question est repéré (carte blanche + 4 boutons
  rose/vert/bleu/jaune), le numéro de la question est lu sur les points de progression.
- **Lecture** (`logic/Glyphs.kt`, `QuizReader.kt`) : les caractères sont isolés puis reconnus par gabarits
  (`TemplateData.kt`, mesurés sur la vidéo) : chiffres, + − × ÷ = ?. L'équation est résolue et comparée aux 4 boutons ;
  le bot ne touche que si **un seul** bouton correspond (une erreur coûte +3 s).
- **Plan** (`Planner.kt`, `QuizBot.kt`) : calcule à quel instant toucher chaque réponse. Le tap est programmé
  indépendamment des images (l'écran est figé pendant l'attente).
- **Mains** : un service d'accessibilité injecte les touchers.

`sim/` contient les tests exécutés à chaque build : lecture sur de vraies images de la vidéo (y compris avec des
gabarits venant uniquement des *autres* questions), et le jeu complet simulé avec latences aléatoires.

## Compilation automatique + envoi sur Discord (facultatif)

Chaque push lance `.github/workflows/build-apk.yml` : tests → APK. L'APK est toujours téléchargeable dans les
*artefacts* du workflow. L'envoi sur Discord est facultatif : ajoute un secret `DISCORD_WEBHOOK_URL` (URL d'un
webhook Discord) dans *Settings → Secrets and variables → Actions* pour recevoir l'APK dans un salon.

## Installation et utilisation

1. Installe l'APK (autoriser les sources inconnues).
2. Ouvre **Triche Maths**, règle le temps visé, puis *Activer le service d'accessibilité* → active « Triche Maths ».
   Android 13+ : si c'est grisé, *Infos de l'appli* → ⋮ → *Autoriser les paramètres restreints*.
3. *Démarrer le bot* → accepter la capture d'écran.
4. Ouvre le jeu et lance la partie. Arrêt : bouton *Arrêter* de la notification.
