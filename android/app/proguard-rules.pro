# Project-specific R8 rules for the release build.
#
# Keep this file minimal. MapLibre, Hilt, Room, Compose, Coroutines and (when present) Firebase all ship
# consumer R8 rules inside their AARs, so they need nothing here. Add a rule only with a comment that says
# which library or crash needs it.

# Keep file names and line numbers so release crash stack traces stay readable (deobfuscated with the
# mapping file uploaded to Crashlytics, SECURITY.md section 8). The original source file name is hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager (M5-4). WorkManager stores the worker class name and HiltWorkerFactory looks the worker up by its
# source name, so worker classes must keep their names. The libraries' own rules should already cover this; it is
# repeated here because an obfuscated name would silently stop uploads in release builds only.
-keepnames class * extends androidx.work.ListenableWorker
