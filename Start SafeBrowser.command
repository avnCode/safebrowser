#!/bin/bash
# Double-click this file in Finder to launch SafeBrowser.
cd "$(dirname "$0")/desktop" || exit 1

# Install dependencies on first run
if [ ! -d "node_modules" ]; then
  echo "Installing dependencies (first run)..."
  npm install || { echo "npm install failed"; read -n 1 -s -r -p "Press any key to close..."; exit 1; }
fi

npm start
