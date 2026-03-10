package com.juanma.ferro.di

import android.content.Context
import androidx.room.Room
import com.juanma.ferro.data.local.FerroDatabase
import com.juanma.ferro.data.local.dao.FerroDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FerroDatabase {
        return Room.databaseBuilder(
            context,
            FerroDatabase::class.java,
            "ferro_db"
        )
        .addMigrations(FerroDatabase.MIGRATION_9_10, FerroDatabase.MIGRATION_10_11)
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideFerroDao(database: FerroDatabase): FerroDao {
        return database.ferroDao()
    }
}
