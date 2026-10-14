# App-specific R8 rules. Library modules ship their own consumer rules.

# The Jellyfin SDK's own rules are ignored (see the release build type): they keep every API
# model. kotlinx.serialization's rules keep the serializers of the models the app reads; only the
# SDK's exceptions are kept whole, as its rules did, since logs and error messages name them.
-keep class org.jellyfin.sdk.api.client.exception.** { *; }
