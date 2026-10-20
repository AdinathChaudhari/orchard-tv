# Orchard toolchain environment. Usage: source tools/env.sh
# Detects JAVA_HOME (JDK 17+), ANDROID_HOME (SDK with build-tools 34.0.0 + platforms;android-30).
# Override any of them by exporting JAVA_HOME / ANDROID_HOME / BT_VERSION before sourcing.
_orchard_err() { printf 'env.sh: %s\n' "$*" >&2; return 1; }

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  JAVA_HOME=
  if [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)
  fi
  if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    JAVA_HOME=
    for _p in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
              /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
              /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64; do
      [ -x "$_p/bin/javac" ] && { JAVA_HOME=$_p; break; }
    done
  fi
fi
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ] || \
  { _orchard_err "no JDK 17+ found. Install one: brew install openjdk@21, or set JAVA_HOME."; return 1 2>/dev/null || exit 1; }
export JAVA_HOME

if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
  ANDROID_HOME=
  for _p in /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools \
            "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -d "$_p/build-tools" ] && { ANDROID_HOME=$_p; break; }
  done
fi
[ -n "$ANDROID_HOME" ] || \
  { _orchard_err "no Android SDK found. Install: brew install --cask android-commandlinetools, then sdkmanager 'build-tools;34.0.0' 'platforms;android-30'; or set ANDROID_HOME."; return 1 2>/dev/null || exit 1; }
export ANDROID_HOME

export BT="$ANDROID_HOME/build-tools/${BT_VERSION:-34.0.0}"
export JAR="$ANDROID_HOME/platforms/android-30/android.jar"
[ -x "$BT/aapt2" ] || \
  { _orchard_err "build-tools missing: $BT. Run: sdkmanager 'build-tools;34.0.0'"; return 1 2>/dev/null || exit 1; }
[ -f "$JAR" ] || \
  { _orchard_err "android-30 platform missing: $JAR. Run: sdkmanager 'platforms;android-30'"; return 1 2>/dev/null || exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"
unset _p
