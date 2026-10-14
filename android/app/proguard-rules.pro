# Project-specific R8 rules for the release build.
#
# Keep this file minimal. MapLibre, Hilt, Room, Compose, Coroutines and (when present) Firebase all ship
# consumer R8 rules inside their AARs, so they need nothing here. Add a rule only with a comment that says
# which library or crash needs it.

# Keep file names and line numbers so release crash stack traces stay readable (deobfuscated with the
# mapping file uploaded to Crashlytics, SECURITY.md section 8). The original source file name is hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
