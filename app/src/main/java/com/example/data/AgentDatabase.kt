package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userPrompt: String,
    val agentReply: String,
    val actionsSummary: String,
    val primaryActionType: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_memory_notes")
data class AgentMemoryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val category: String = "সাধারণ মেমোরি", // USER_PREFERENCE, WEBSITE_KNOWLEDGE, PLATFORM_ID, CONVERSATION_FACT
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "today_tasks")
data class TodayTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val autoCommandPrompt: String,
    val platformTag: String = "SYSTEM",
    val scheduledTimeLabel: String = "যেকোনো সময়",
    val isCompleted: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "platform_accounts")
data class PlatformAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val platformName: String, // WhatsApp, Telegram, Facebook, Chrome Site, Email
    val contactOrGroupName: String,
    val handleOrNumberOrUrl: String,
    val notes: String = ""
)

@Dao
interface CommandHistoryDao {
    @Query("SELECT * FROM command_history ORDER BY timestamp DESC LIMIT 60")
    fun getAllHistory(): Flow<List<CommandHistoryEntity>>

    @Query("SELECT * FROM command_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentHistorySnapshot(limit: Int = 15): List<CommandHistoryEntity>

    @Query("SELECT * FROM command_history WHERE userPrompt LIKE '%' || :query || '%' OR agentReply LIKE '%' || :query || '%' OR actionsSummary LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchHistory(query: String, limit: Int = 15): List<CommandHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: CommandHistoryEntity)

    @Query("DELETE FROM command_history WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM command_history WHERE id NOT IN (SELECT id FROM command_history ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun pruneOldHistory(keepCount: Int = 25)

    @Query("DELETE FROM command_history")
    suspend fun clearAll()
}

@Dao
interface AgentMemoryNoteDao {
    @Query("SELECT * FROM agent_memory_notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<AgentMemoryNoteEntity>>

    @Query("SELECT * FROM agent_memory_notes ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentNotesSnapshot(limit: Int = 30): List<AgentMemoryNoteEntity>

    @Query("SELECT * FROM agent_memory_notes ORDER BY timestamp DESC")
    suspend fun getAllNotesSnapshot(): List<AgentMemoryNoteEntity>

    @Query("SELECT * FROM agent_memory_notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchNotes(query: String, limit: Int = 20): List<AgentMemoryNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: AgentMemoryNoteEntity)

    @Query("DELETE FROM agent_memory_notes WHERE id = :id")
    suspend fun deleteNoteById(id: Int)

    @Query("DELETE FROM agent_memory_notes WHERE id NOT IN (SELECT id FROM agent_memory_notes ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun pruneOldNotes(keepCount: Int = 30)

    @Query("DELETE FROM agent_memory_notes")
    suspend fun clearAllNotes()
}

@Dao
interface TodayTaskDao {
    @Query("SELECT * FROM today_tasks ORDER BY isCompleted ASC, timestamp DESC")
    fun getAllTasks(): Flow<List<TodayTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TodayTaskEntity)

    @Query("UPDATE today_tasks SET isCompleted = :completed WHERE id = :id")
    suspend fun setTaskCompleted(id: Int, completed: Boolean)

    @Query("DELETE FROM today_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Int)
}

@Dao
interface PlatformAccountDao {
    @Query("SELECT * FROM platform_accounts ORDER BY id DESC")
    fun getAllAccounts(): Flow<List<PlatformAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: PlatformAccountEntity)

    @Query("DELETE FROM platform_accounts WHERE id = :id")
    suspend fun deleteAccountById(id: Int)
}

@Database(
    entities = [
        CommandHistoryEntity::class,
        AgentMemoryNoteEntity::class,
        TodayTaskEntity::class,
        PlatformAccountEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun agentMemoryNoteDao(): AgentMemoryNoteDao
    abstract fun todayTaskDao(): TodayTaskDao
    abstract fun platformAccountDao(): PlatformAccountDao

    companion object {
        @Volatile
        private var INSTANCE: AgentDatabase? = null

        fun getInstance(context: Context): AgentDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AgentDatabase::class.java,
                    "omni_agent_db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class CommandHistoryRepository(
    private val historyDao: CommandHistoryDao,
    private val memoryDao: AgentMemoryNoteDao,
    private val taskDao: TodayTaskDao,
    private val platformDao: PlatformAccountDao
) {
    val historyFlow: Flow<List<CommandHistoryEntity>> = historyDao.getAllHistory()
    val memoryNotesFlow: Flow<List<AgentMemoryNoteEntity>> = memoryDao.getAllNotes()
    val todayTasksFlow: Flow<List<TodayTaskEntity>> = taskDao.getAllTasks()
    val platformAccountsFlow: Flow<List<PlatformAccountEntity>> = platformDao.getAllAccounts()

    suspend fun logCommand(
        userPrompt: String,
        agentReply: String,
        actionsSummary: String,
        primaryActionType: String
    ) {
        historyDao.insertHistory(
            CommandHistoryEntity(
                userPrompt = userPrompt,
                agentReply = agentReply,
                actionsSummary = actionsSummary,
                primaryActionType = primaryActionType
            )
        )
    }

    suspend fun deleteHistoryItem(id: Int) = historyDao.deleteById(id)

    suspend fun clearHistory() = historyDao.clearAll()

    suspend fun saveMemoryNote(title: String, content: String, category: String = "কিউব মেমোরি") {
        if (content.isBlank()) return
        memoryDao.insertNote(
            AgentMemoryNoteEntity(
                title = title.ifBlank { "কিউব নিউরাল মেমোরি" },
                content = content.trim(),
                category = category
            )
        )
    }

    suspend fun searchLocalMemoryTimeMachine(query: String, limit: Int = 15): String {
        val cleanQuery = query.trim()
        val notes = if (cleanQuery.isBlank() || cleanQuery.equals("ALL", ignoreCase = true)) {
            memoryDao.getRecentNotesSnapshot(limit)
        } else {
            memoryDao.searchNotes(cleanQuery, limit)
        }
        val historyMatches = if (cleanQuery.isBlank() || cleanQuery.equals("ALL", ignoreCase = true)) {
            historyDao.getRecentHistorySnapshot(8)
        } else {
            historyDao.searchHistory(cleanQuery, 8)
        }
        val fmt = java.text.SimpleDateFormat("dd MMM hh:mm a", java.util.Locale.getDefault())
        return buildString {
            if (notes.isEmpty() && historyMatches.isEmpty()) {
                append("SQLITE_TIME_MACHINE: '$cleanQuery' সম্পর্কিত কোনো মেমোরি বা পুরনো কথোপকথন পাওয়া যায়নি।")
            } else {
                if (notes.isNotEmpty()) {
                    append("SAVED SQLITE MEMORIES (${notes.size}):\n")
                    notes.forEachIndexed { idx, n ->
                        val t = fmt.format(java.util.Date(n.timestamp))
                        append("${idx + 1}. [${n.category} • $t] ${n.title}: ${n.content}\n")
                    }
                }
                if (historyMatches.isNotEmpty()) {
                    append("PAST CONVERSATION SUMMARIES (${historyMatches.size}):\n")
                    historyMatches.forEachIndexed { idx, h ->
                        val t = fmt.format(java.util.Date(h.timestamp))
                        append("${idx + 1}. [$t] User: '${h.userPrompt.take(80)}' -> AI: '${h.agentReply.take(100)}'\n")
                    }
                }
            }
        }.trim()
    }

    suspend fun buildStartupMemoryInjectionBlock(): String {
        val recentNotes = memoryDao.getRecentNotesSnapshot(25)
        val recentHistory = historyDao.getRecentHistorySnapshot(8)
        val fmt = java.text.SimpleDateFormat("dd MMM hh:mm a", java.util.Locale.getDefault())
        return buildString {
            if (recentNotes.isNotEmpty()) {
                append("\n=== ON-DEVICE SQLITE TIME MACHINE: SAVED USER MEMORIES & SCREEN NOTES ===\n")
                recentNotes.forEachIndexed { idx, n ->
                    val t = fmt.format(java.util.Date(n.timestamp))
                    append("${idx + 1}. [${n.category} • $t] ${n.title}: ${n.content}\n")
                }
            }
            if (recentHistory.isNotEmpty()) {
                append("\n=== ON-DEVICE SQLITE TIME MACHINE: RECENT CONVERSATION SUMMARIES ===\n")
                recentHistory.forEachIndexed { idx, h ->
                    val t = fmt.format(java.util.Date(h.timestamp))
                    append("${idx + 1}. [$t] User: ${h.userPrompt.take(70)} | Assistant: ${h.agentReply.take(90)}\n")
                }
            }
        }
    }

    suspend fun deleteMemoryNote(id: Int) = memoryDao.deleteNoteById(id)

    suspend fun clearAllMemoryNotes() = memoryDao.clearAllNotes()

    /**
     * Keeps only the fast-startup recent window (30 notes & 25 history items) in local SQLite
     * once records are backed up to the user's 5TB Google Drive (`AI_OS_Memory`), ensuring zero
     * impact on main phone storage.
     */
    suspend fun pruneLocalCacheForZeroPhoneStorageImpact() {
        try {
            memoryDao.pruneOldNotes(30)
            historyDao.pruneOldHistory(25)
        } catch (_: Exception) {
        }
    }

    suspend fun addTask(title: String, autoCommandPrompt: String, platformTag: String = "AUTO", scheduledTimeLabel: String = "আজ") {
        taskDao.insertTask(
            TodayTaskEntity(
                title = title,
                autoCommandPrompt = autoCommandPrompt.ifBlank { title },
                platformTag = platformTag,
                scheduledTimeLabel = scheduledTimeLabel
            )
        )
    }

    suspend fun toggleTaskCompleted(id: Int, completed: Boolean) = taskDao.setTaskCompleted(id, completed)

    suspend fun deleteTask(id: Int) = taskDao.deleteTaskById(id)

    suspend fun addPlatformAccount(platformName: String, contactOrGroupName: String, handleOrNumberOrUrl: String, notes: String = "") {
        platformDao.insertAccount(
            PlatformAccountEntity(
                platformName = platformName,
                contactOrGroupName = contactOrGroupName,
                handleOrNumberOrUrl = handleOrNumberOrUrl,
                notes = notes
            )
        )
    }

    suspend fun deletePlatformAccount(id: Int) = platformDao.deleteAccountById(id)
}
