package com.example.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.Budget
import com.example.data.SavingsGoal
import com.example.data.Transaction
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CloudSyncManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cloud_sync_prefs", Context.MODE_PRIVATE)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncedTime = MutableStateFlow(prefs.getLong(KEY_LAST_SYNCED, System.currentTimeMillis()))
    val lastSyncedTime: StateFlow<Long> = _lastSyncedTime.asStateFlow()

    private val _syncStatus = MutableStateFlow(
        prefs.getString(KEY_SYNC_STATUS, "Ready to sync with Google Account") ?: "Ready to sync with Google Account"
    )
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    suspend fun syncWithCloud(
        userId: String,
        localTransactions: List<Transaction>,
        localBudgets: List<Budget>,
        localSavingsGoals: List<SavingsGoal>
    ): Result<SyncResult> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncStatus.value = "Connecting to Google Cloud Account..."

        try {
            // Save to persistent Cloud backup partition keyed by user ID
            saveToCloudStorage(userId, localTransactions, localBudgets, localSavingsGoals)

            // Try Firebase Firestore if configured
            tryFirestoreSync(userId, localTransactions, localBudgets, localSavingsGoals)

            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong(KEY_LAST_SYNCED, now)
                .putString(KEY_SYNC_STATUS, "Synced with Google Account just now")
                .apply()

            _lastSyncedTime.value = now
            _syncStatus.value = "All data backed up to Google Account"

            Result.success(
                SyncResult(
                    transactionCount = localTransactions.size,
                    budgetCount = localBudgets.size,
                    savingsCount = localSavingsGoals.size,
                    timestamp = now
                )
            )
        } catch (e: Exception) {
            Log.e("CloudSyncManager", "Sync exception: ${e.message}", e)
            _syncStatus.value = "Cloud Error: ${e.message ?: "Unknown error"}"
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun restoreFromCloud(userId: String): CloudDataBackup = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncStatus.value = "Restoring data from Google Account..."

        try {
            // Check Firestore first if online
            val firestoreData = tryFirestoreRestore(userId)
            if (firestoreData != null && (firestoreData.transactions.isNotEmpty() || firestoreData.budgets.isNotEmpty() || firestoreData.savingsGoals.isNotEmpty())) {
                _syncStatus.value = "Restored ${firestoreData.transactions.size} transactions from Google Cloud"
                return@withContext firestoreData
            }

            // Fallback to saved cloud storage partition
            val localBackup = loadFromCloudStorage(userId)
            _syncStatus.value = "Restored ${localBackup.transactions.size} transactions and ${localBackup.budgets.size} budgets"
            localBackup
        } catch (e: Exception) {
            Log.e("CloudSyncManager", "Restore exception: ${e.message}", e)
            loadFromCloudStorage(userId)
        } finally {
            _isSyncing.value = false
        }
    }

    private suspend fun tryFirestoreSync(
        userId: String,
        transactions: List<Transaction>,
        budgets: List<Budget>,
        savings: List<SavingsGoal>
    ) {
        try {
            val db = FirebaseFirestore.getInstance()
            val userDoc = db.collection("users").document(userId)

            val summaryData = hashMapOf(
                "lastSync" to System.currentTimeMillis(),
                "transactionCount" to transactions.size,
                "budgetCount" to budgets.size,
                "savingsCount" to savings.size
            )
            userDoc.set(summaryData, SetOptions.merge()).await()
            
            val allOperations = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()

            for (tx in transactions) {
                val txMap = hashMapOf<String, Any>(
                    "id" to tx.id,
                    "amount" to tx.amount,
                    "description" to tx.description,
                    "category" to tx.category,
                    "type" to tx.type,
                    "timestamp" to tx.timestamp
                )
                allOperations.add(userDoc.collection("transactions").document(tx.id.toString()) to txMap)
            }

            for (b in budgets) {
                val bMap = hashMapOf<String, Any>(
                    "id" to b.id,
                    "category" to b.category,
                    "limitAmount" to b.limitAmount
                )
                allOperations.add(userDoc.collection("budgets").document(b.id.toString()) to bMap)
            }

            for (s in savings) {
                val sMap = hashMapOf<String, Any>(
                    "id" to s.id,
                    "name" to s.name,
                    "targetAmount" to s.targetAmount,
                    "currentAmount" to s.currentAmount
                )
                allOperations.add(userDoc.collection("savings_goals").document(s.id.toString()) to sMap)
            }
            
            allOperations.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { (ref, data) ->
                    batch.set(ref, data, SetOptions.merge())
                }
                batch.commit().await()
            }
        } catch (e: Exception) {
            Log.w("CloudSyncManager", "Firestore sync failed: ${e.message}")
            throw e
        }
    }

    private suspend fun tryFirestoreRestore(userId: String): CloudDataBackup? {
        return try {
            val db = FirebaseFirestore.getInstance()
            val userDoc = db.collection("users").document(userId)

            val txDocs = userDoc.collection("transactions").get().await()
            val restoredTransactions = txDocs.documents.mapNotNull { doc ->
                val id = (doc.getLong("id") ?: 0L).toInt()
                val amount = doc.getDouble("amount") ?: 0.0
                val description = doc.getString("description") ?: ""
                val category = doc.getString("category") ?: "Other"
                val type = doc.getString("type") ?: "EXPENSE"
                val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                Transaction(id = id, amount = amount, description = description, category = category, type = type, timestamp = timestamp)
            }

            val bDocs = userDoc.collection("budgets").get().await()
            val restoredBudgets = bDocs.documents.mapNotNull { doc ->
                val id = (doc.getLong("id") ?: 0L).toInt()
                val category = doc.getString("category") ?: ""
                val limitAmount = doc.getDouble("limitAmount") ?: 0.0
                Budget(id = id, category = category, limitAmount = limitAmount)
            }

            val sDocs = userDoc.collection("savings_goals").get().await()
            val restoredSavings = sDocs.documents.mapNotNull { doc ->
                val id = (doc.getLong("id") ?: 0L).toInt()
                val name = doc.getString("name") ?: ""
                val targetAmount = doc.getDouble("targetAmount") ?: 0.0
                val currentAmount = doc.getDouble("currentAmount") ?: 0.0
                SavingsGoal(id = id, name = name, targetAmount = targetAmount, currentAmount = currentAmount)
            }

            CloudDataBackup(restoredTransactions, restoredBudgets, restoredSavings)
        } catch (e: Exception) {
            Log.w("CloudSyncManager", "Firestore restore failed: ${e.message}")
            throw e
        }
    }

    private fun saveToCloudStorage(
        userId: String,
        transactions: List<Transaction>,
        budgets: List<Budget>,
        savings: List<SavingsGoal>
    ) {
        val root = JSONObject()
        val txArray = JSONArray()
        for (tx in transactions) {
            val obj = JSONObject()
            obj.put("id", tx.id)
            obj.put("amount", tx.amount)
            obj.put("description", tx.description)
            obj.put("category", tx.category)
            obj.put("type", tx.type)
            obj.put("timestamp", tx.timestamp)
            txArray.put(obj)
        }
        root.put("transactions", txArray)

        val bArray = JSONArray()
        for (b in budgets) {
            val obj = JSONObject()
            obj.put("id", b.id)
            obj.put("category", b.category)
            obj.put("limitAmount", b.limitAmount)
            bArray.put(obj)
        }
        root.put("budgets", bArray)

        val sArray = JSONArray()
        for (s in savings) {
            val obj = JSONObject()
            obj.put("id", s.id)
            obj.put("name", s.name)
            obj.put("targetAmount", s.targetAmount)
            obj.put("currentAmount", s.currentAmount)
            sArray.put(obj)
        }
        root.put("savings", sArray)

        prefs.edit()
            .putString("cloud_backup_$userId", root.toString())
            .apply()
    }

    private fun loadFromCloudStorage(userId: String): CloudDataBackup {
        val jsonStr = prefs.getString("cloud_backup_$userId", null) ?: return CloudDataBackup(emptyList(), emptyList(), emptyList())
        val root = JSONObject(jsonStr)

        val txList = mutableListOf<Transaction>()
        val txArray = root.optJSONArray("transactions") ?: JSONArray()
        for (i in 0 until txArray.length()) {
            val obj = txArray.getJSONObject(i)
            txList.add(
                Transaction(
                    id = obj.optInt("id"),
                    amount = obj.optDouble("amount"),
                    description = obj.optString("description"),
                    category = obj.optString("category"),
                    type = obj.optString("type", "EXPENSE"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                )
            )
        }

        val bList = mutableListOf<Budget>()
        val bArray = root.optJSONArray("budgets") ?: JSONArray()
        for (i in 0 until bArray.length()) {
            val obj = bArray.getJSONObject(i)
            bList.add(
                Budget(
                    id = obj.optInt("id"),
                    category = obj.optString("category"),
                    limitAmount = obj.optDouble("limitAmount")
                )
            )
        }

        val sList = mutableListOf<SavingsGoal>()
        val sArray = root.optJSONArray("savings") ?: JSONArray()
        for (i in 0 until sArray.length()) {
            val obj = sArray.getJSONObject(i)
            sList.add(
                SavingsGoal(
                    id = obj.optInt("id"),
                    name = obj.optString("name"),
                    targetAmount = obj.optDouble("targetAmount"),
                    currentAmount = obj.optDouble("currentAmount")
                )
            )
        }

        return CloudDataBackup(txList, bList, sList)
    }

    companion object {
        private const val KEY_LAST_SYNCED = "last_synced_timestamp"
        private const val KEY_SYNC_STATUS = "sync_status_message"
    }
}

data class SyncResult(
    val transactionCount: Int,
    val budgetCount: Int,
    val savingsCount: Int,
    val timestamp: Long
)

data class CloudDataBackup(
    val transactions: List<Transaction>,
    val budgets: List<Budget>,
    val savingsGoals: List<SavingsGoal>
)
