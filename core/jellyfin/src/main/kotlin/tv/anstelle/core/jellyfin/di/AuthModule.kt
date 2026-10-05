package tv.anstelle.core.jellyfin.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tv.anstelle.core.jellyfin.auth.KeystoreTokenStore
import tv.anstelle.core.jellyfin.auth.TokenStore

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AuthModule {
    @Binds
    abstract fun tokenStore(store: KeystoreTokenStore): TokenStore
}
