#!/usr/bin/env bash
# Lists every protected rule in Arin's source as "file|rule|fingerprint of its whole comment block" (the marker line
# and the comment lines that continue it). The baseline and the build check both use this, so a deleted rule, an edited
# rule text or a rule swapped for another all show up.
cd "$(dirname "$0")/.." || exit 1
grep -rlE "ARIN-PROTECTED(-RULE)?: " app/src/main/java | LC_ALL=C sort | while IFS= read -r f; do
  awk -v F="${f#app/src/main/java/com/arin/}" '
    function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
    function flush() { if (name != "") { print F "|" name "|" block; name = ""; block = "" } }
    {
      t = trim($0)
      if (match($0, /ARIN-PROTECTED(-RULE)?: [A-Za-z0-9 _-]+/)) {
        flush()
        m = substr($0, RSTART, RLENGTH); sub(/ARIN-PROTECTED(-RULE)?: /, "", m); m = trim(m)
        if (match(m, /^[a-z0-9-]+( [a-z0-9-]+)*/)) m = substr(m, RSTART, RLENGTH)
        name = m; block = t; inBlock = 1; next
      }
      if (inBlock && (t ~ /^\/\// || t ~ /^\*/ || t ~ /^\/\*/)) { block = block " " t; next }
      inBlock = 0; flush()
    }
    END { flush() }' "$f"
done | while IFS='|' read -r file rule text; do
  echo "$file|$rule|$(printf '%s' "$text" | sha1sum | cut -c1-12)"
done | LC_ALL=C sort -u
