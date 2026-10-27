#!/usr/bin/env bash
# Rinomina il progetto: nome visibile, package e nome del progetto Gradle.
# Uso: ./rinomina.sh "Nome App" com.tuonome.nuovaapp
# Esempio: ./rinomina.sh "Mio Assistente" com.mario.assistente
set -euo pipefail
if [ $# -ne 2 ]; then
  echo 'Uso: ./rinomina.sh "Nome App" com.tuonome.nuovaapp'; exit 1
fi
NAME="$1"; PKG="$2"
if ! [[ "$PKG" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]]; then
  echo "Package non valido: usa minuscole, cifre, underscore e almeno due parti separate da punti (es. com.mario.assistente)"; exit 1
fi
OLD_PKG="com.megacity.app"
OLD_DIR="app/src/main/java/com/megacity/app"
NEW_DIR="app/src/main/java/$(echo "$PKG" | tr . /)"
SLUG=$(echo "$NAME" | iconv -f utf-8 -t ascii//TRANSLIT 2>/dev/null | tr 'A-Z' 'a-z' | sed 's/[^a-z0-9]\+/-/g; s/^-\|-$//g')
PROJ=$(echo "$NAME" | iconv -f utf-8 -t ascii//TRANSLIT 2>/dev/null | sed 's/[^A-Za-z0-9]//g')
[ -n "$SLUG" ] || SLUG="app"; [ -n "$PROJ" ] || PROJ="App"

[ -d "$OLD_DIR" ] || { echo "Cartella $OLD_DIR non trovata: il progetto è già stato rinominato?"; exit 1; }

# 1) package nei sorgenti e nei file Gradle/Manifest
grep -rlI --include=*.kt --include=*.kts --include=*.xml "$OLD_PKG" . | while read -r f; do
  sed -i "s/$OLD_PKG/$PKG/g" "$f"
done

# 2) sposta i sorgenti nella nuova cartella del package
mkdir -p "$NEW_DIR"
mv "$OLD_DIR"/* "$NEW_DIR"/
find app/src/main/java -type d -empty -delete

# 3) nome visibile, nome progetto Gradle, nome artefatto della build
sed -i "s|<string name=\"app_name\">.*</string>|<string name=\"app_name\">$NAME</string>|" app/src/main/res/values/strings.xml
sed -i "s|rootProject.name = \".*\"|rootProject.name = \"$PROJ\"|" settings.gradle.kts
sed -i "s|name: megacity-2436-debug|name: $SLUG-debug|" .github/workflows/build.yml
sed -i "1s|.*|# $NAME (Android)|" README.md
sed -i "s|megacity-2436-debug|$SLUG-debug|g" README.md

echo "Fatto. Nome: $NAME | package: $PKG | artefatto: $SLUG-debug"
echo "Ora: git add . && git commit && git push, poi scarica l'APK dalla scheda Actions."
