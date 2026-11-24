package moe.matsuri.nb4a

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

@Database(entities = [KeyValuePair::class], version = 1)
abstract class TempDatabase : RoomDatabase() {

    companion object {
        private val instance by lazy {
            val ioExecutor = Dispatchers.IO.asExecutor()
            Room.inMemoryDatabaseBuilder(SagerNet.application, TempDatabase::class.java)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .setQueryExecutor(ioExecutor)
                .setTransactionExecutor(ioExecutor)
                .build()
        }

        val profileCacheDao get() = instance.profileCacheDao()

    }

    abstract fun profileCacheDao(): KeyValuePair.Dao
}