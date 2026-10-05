# Consumer R8 rules for webserver.

# Ktor's IntelliJ debugger detection uses JVM management APIs that Android does not have.
# The call is wrapped in a try/catch and falls back to "no debugger" on Android.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
